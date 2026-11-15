package com.shilapi.xcertplay.mfi

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One open connection to the car's MFi daemon (`mfid`). Closing it releases the socket. */
interface CarMfiConnection : Closeable {
    val input: InputStream
    val output: OutputStream
}

/** A failed call to the car's MFi daemon: transport, framing, or a non-zero status from mfid. */
class CarMfiException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Wire format of the car's MFi socket: LE u32 length, then a protobuf RPCProtoMessage. */
object CarMfiProtocol {
    const val OP_GET_DEVICE_ID = 0
    const val OP_GET_CERTIFICATE = 1
    const val OP_CREATE_SIGNATURE = 2
    const val OP_READ_CERTIFICATE_SERIAL = 3

    const val TYPE_U32 = 4
    const val TYPE_S32 = 5
    const val TYPE_BLOB = 8

    /** The stock client reads responses into a 1024-byte buffer, so mfid keeps them under this. */
    const val MAX_RESPONSE_BYTES = 1020
    const val CERTIFICATE_CAPACITY = 1024

    class Arg(val datatype: Int, val data: ByteArray)

    class Reply(val txId: Int, val opcode: Int, val args: List<Arg>)

    /** The protobuf body, without the length prefix. */
    fun encodeBody(txId: Int, opcode: Int, args: List<Arg>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x08); writeVarint(out, txId)
        out.write(0x10); writeVarint(out, opcode)
        for (arg in args) {
            val inner = ByteArrayOutputStream()
            inner.write(0x08); writeVarint(inner, arg.datatype)
            inner.write(0x12); writeVarint(inner, arg.data.size); inner.write(arg.data)
            out.write(0x1A); writeVarint(out, inner.size()); inner.writeTo(out)
        }
        return out.toByteArray()
    }

    /** The full frame: LE u32 body length, then the body. */
    fun encodeFrame(txId: Int, opcode: Int, args: List<Arg>): ByteArray {
        val body = encodeBody(txId, opcode, args)
        return le32(body.size) + body
    }

    fun decodeBody(body: ByteArray): Reply {
        var p = 0
        fun varint(): Int {
            var shift = 0
            var value = 0L
            while (true) {
                if (p >= body.size || shift > 28) throw CarMfiException("truncated or oversized varint")
                val b = body[p++].toInt() and 0xFF
                value = value or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) break
                shift += 7
            }
            return value.toInt()
        }
        var txId = -1
        var opcode = -1
        val args = ArrayList<Arg>()
        while (p < body.size) {
            when (val tag = varint()) {
                0x08 -> txId = varint()
                0x10 -> opcode = varint()
                0x1A -> {
                    val length = varint()
                    val end = p + length
                    if (length < 0 || end > body.size) throw CarMfiException("argument length $length overruns the reply")
                    var datatype = -1
                    var data: ByteArray? = null
                    while (p < end) {
                        when (val inner = varint()) {
                            0x08 -> datatype = varint()
                            0x12 -> {
                                val n = varint()
                                if (n < 0 || p + n > end) throw CarMfiException("argument data overruns the argument")
                                data = body.copyOfRange(p, p + n)
                                p += n
                            }
                            else -> throw CarMfiException("unexpected argument tag 0x${inner.toString(16)}")
                        }
                    }
                    if (datatype < 0 || data == null) throw CarMfiException("argument is missing a field")
                    args += Arg(datatype, data)
                }
                else -> throw CarMfiException("unexpected tag 0x${tag.toString(16)}")
            }
        }
        if (txId < 0 || opcode < 0) throw CarMfiException("reply is missing tx_id or opcode")
        return Reply(txId, opcode, args)
    }

    fun le32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    fun readLe32(bytes: ByteArray): Int {
        if (bytes.size != 4) throw CarMfiException("expected a 4-byte value, got ${bytes.size}")
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v >= 0x80) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }
}

/**
 * Client for the car's own MFi authentication chip, through the `mfid` daemon's socket
 * (`/dev/socket/MFISocket`). Blocking: call it from a worker thread. One call at a time, across
 * every instance. One connection per call.
 *
 * It only reads identity and asks for signatures, the same as the stock CarPlay app. [connector]
 * opens the socket; the real one sets its own read timeout.
 */
class CarMfiClient(private val connector: () -> CarMfiConnection) {
    private var txCounter = 0

    /** 0x200 is an MFi 2.0C part, 0x300 is 3.0 (unconfirmed on this car until the probe runs). */
    fun getDeviceId(): Long {
        val reply = call(CarMfiProtocol.OP_GET_DEVICE_ID, emptyList())
        val value = reply.args.getOrNull(0)
        if (reply.args.size != 2 || value?.datatype != CarMfiProtocol.TYPE_U32) {
            throw CarMfiException("unexpected GetDeviceId reply shape: ${shape(reply)}")
        }
        return CarMfiProtocol.readLe32(value.data).toLong() and 0xFFFFFFFFL
    }

    /** The certificate, DER, as the chip holds it. */
    fun getCertificate(capacity: Int = CarMfiProtocol.CERTIFICATE_CAPACITY): ByteArray {
        val request = listOf(CarMfiProtocol.Arg(CarMfiProtocol.TYPE_U32, CarMfiProtocol.le32(capacity)))
        return blobReply(call(CarMfiProtocol.OP_GET_CERTIFICATE, request), "GetCertificate")
    }

    /** Signs [digest] as given: the chip does not hash. 20 bytes for 2.0C, 32 for 3.0. */
    fun createSignature(digest: ByteArray): ByteArray {
        if (digest.isEmpty()) throw CarMfiException("empty digest")
        val request = listOf(CarMfiProtocol.Arg(CarMfiProtocol.TYPE_BLOB, digest))
        return blobReply(call(CarMfiProtocol.OP_CREATE_SIGNATURE, request), "CreateSignature")
    }

    /** The certificate serial number as the daemon returns it (expected: ASCII hex, up to 30 chars). */
    fun readCertificateSerial(): ByteArray =
        blobReply(call(CarMfiProtocol.OP_READ_CERTIFICATE_SERIAL, emptyList()), "ReadCertificateSerialNum")

    private fun blobReply(reply: CarMfiProtocol.Reply, name: String): ByteArray {
        val blob = reply.args.getOrNull(0)
        if (reply.args.size != 2 || blob?.datatype != CarMfiProtocol.TYPE_BLOB) {
            throw CarMfiException("unexpected $name reply shape: ${shape(reply)}")
        }
        return blob.data
    }

    private fun shape(reply: CarMfiProtocol.Reply) =
        reply.args.joinToString(prefix = "[", postfix = "]") { "${it.datatype}:${it.data.size}" }

    private fun call(opcode: Int, args: List<CarMfiProtocol.Arg>): CarMfiProtocol.Reply =
        synchronized(LOCK) {
            val txId = txCounter++ and 0xFFFF
            val frame = CarMfiProtocol.encodeFrame(txId, opcode, args)
            val connection = try {
                connector()
            } catch (error: IOException) {
                throw CarMfiException("connect failed: ${describe(error)}", error)
            }
            try {
                connection.output.write(frame)
                connection.output.flush()
                val input = DataInputStream(connection.input)
                val header = ByteArray(4)
                input.readFully(header)
                val length = CarMfiProtocol.readLe32(header)
                if (length <= 0 || length > CarMfiProtocol.MAX_RESPONSE_BYTES) {
                    throw CarMfiException("bad reply length $length")
                }
                val body = ByteArray(length)
                input.readFully(body)
                val reply = CarMfiProtocol.decodeBody(body)
                if (reply.txId != txId) throw CarMfiException("tx_id mismatch: sent $txId, got ${reply.txId}")
                if (reply.opcode != opcode) throw CarMfiException("opcode mismatch: sent $opcode, got ${reply.opcode}")
                val status = reply.args.lastOrNull()
                if (status != null && status.datatype == CarMfiProtocol.TYPE_S32 && status.data.size == 4) {
                    val code = CarMfiProtocol.readLe32(status.data)
                    if (code != 0) throw CarMfiException("mfid returned status $code")
                } else {
                    throw CarMfiException("reply has no status: ${shape(reply)}")
                }
                reply
            } catch (error: CarMfiException) {
                throw error
            } catch (error: IOException) {
                throw CarMfiException("socket failed: ${describe(error)}", error)
            } finally {
                try { connection.close() } catch (_: IOException) {}
            }
        }

    companion object {
        const val SOCKET_PATH = "/dev/socket/MFISocket"
        const val READ_TIMEOUT_MILLIS = 3_000
        private val LOCK = Any()

        /** `EACCES`, `ENOENT`, `ECONNREFUSED` and timeouts show in the exception text. */
        fun describe(error: Throwable): String = generateSequence(error) { it.cause }
            .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }

        /** The real socket: a filesystem-namespace `LocalSocket` with its own read timeout. */
        fun local(path: String = SOCKET_PATH, timeoutMillis: Int = READ_TIMEOUT_MILLIS): CarMfiClient =
            CarMfiClient {
                val socket = android.net.LocalSocket(android.net.LocalSocket.SOCKET_STREAM)
                try {
                    socket.connect(
                        android.net.LocalSocketAddress(path, android.net.LocalSocketAddress.Namespace.FILESYSTEM),
                    )
                    socket.soTimeout = timeoutMillis
                } catch (error: IOException) {
                    try { socket.close() } catch (_: IOException) {}
                    throw error
                }
                object : CarMfiConnection {
                    override val input: InputStream get() = socket.inputStream
                    override val output: OutputStream get() = socket.outputStream
                    override fun close() = socket.close()
                }
            }
    }
}
