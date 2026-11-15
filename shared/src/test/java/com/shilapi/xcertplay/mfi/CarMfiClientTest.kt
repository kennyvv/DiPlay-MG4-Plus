package com.shilapi.xcertplay.mfi

import com.shilapi.xcertplay.mfi.CarMfiProtocol.Arg
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class CarMfiClientTest {
    private fun hex(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun u32(value: Int) = CarMfiProtocol.le32(value)

    /** A reply frame body that echoes [request]'s tx_id and opcode. */
    private fun reply(request: CarMfiProtocol.Reply, vararg args: Arg, status: Int = 0): ByteArray =
        CarMfiProtocol.encodeBody(
            request.txId,
            request.opcode,
            args.toList() + Arg(CarMfiProtocol.TYPE_S32, u32(status)),
        )

    /** Serves one canned reply after reading the request, and records the request frame. */
    private class FakeConnection(
        private val responder: (CarMfiProtocol.Reply) -> ByteArray,
        private val rawLength: Int? = null,
        private val truncateTo: Int? = null,
    ) : CarMfiConnection {
        val written = ByteArrayOutputStream()
        var closed = false
        override val output: OutputStream get() = written
        override val input: InputStream by lazy {
            val frame = written.toByteArray()
            val request = CarMfiProtocol.decodeBody(frame.copyOfRange(4, frame.size))
            val body = responder(request)
            val bytes = CarMfiProtocol.le32(rawLength ?: body.size) + body
            ByteArrayInputStream(if (truncateTo != null) bytes.copyOf(truncateTo) else bytes)
        }
        override fun close() { closed = true }
    }

    private lateinit var connections: MutableList<FakeConnection>

    @Before fun setUp() { connections = mutableListOf() }

    private fun client(
        rawLength: Int? = null,
        truncateTo: Int? = null,
        responder: (CarMfiProtocol.Reply) -> ByteArray,
    ) = CarMfiClient {
        FakeConnection(responder, rawLength, truncateTo).also { connections += it }
    }

    // --- Spec example frames ---

    @Test fun getDeviceIdFrameMatchesSpec() {
        assertArrayEquals(
            hex(0x04, 0, 0, 0, 0x08, 0x00, 0x10, 0x00),
            CarMfiProtocol.encodeFrame(0, CarMfiProtocol.OP_GET_DEVICE_ID, emptyList()),
        )
    }

    @Test fun getCertificateFrameMatchesSpec() {
        val frame = CarMfiProtocol.encodeFrame(
            0,
            CarMfiProtocol.OP_GET_CERTIFICATE,
            listOf(Arg(CarMfiProtocol.TYPE_U32, u32(1024))),
        )
        assertArrayEquals(
            hex(0x0E, 0, 0, 0, 0x08, 0x00, 0x10, 0x01, 0x1A, 0x08, 0x08, 0x04, 0x12, 0x04, 0x00, 0x04, 0x00, 0x00),
            frame,
        )
    }

    @Test fun createSignatureFrameMatchesSpec() {
        val challenge = ByteArray(20) { (0xC0 + it).toByte() }
        val frame = CarMfiProtocol.encodeFrame(1, CarMfiProtocol.OP_CREATE_SIGNATURE, listOf(Arg(8, challenge)))
        val expected = hex(0x1E, 0, 0, 0, 0x08, 0x01, 0x10, 0x02, 0x1A, 0x18, 0x08, 0x08, 0x12, 0x14) + challenge
        assertArrayEquals(expected, frame)
    }

    @Test fun readSerialFrameMatchesSpec() {
        assertArrayEquals(
            hex(0x04, 0, 0, 0, 0x08, 0x00, 0x10, 0x03),
            CarMfiProtocol.encodeFrame(0, CarMfiProtocol.OP_READ_CERTIFICATE_SERIAL, emptyList()),
        )
    }

    @Test fun certificateLengthUsesTwoByteVarint() {
        val cert = ByteArray(700) { it.toByte() }
        val body = CarMfiProtocol.encodeBody(5, 1, listOf(Arg(8, cert), Arg(5, u32(0))))
        val decoded = CarMfiProtocol.decodeBody(body)
        assertEquals(5, decoded.txId)
        assertEquals(1, decoded.opcode)
        assertEquals(2, decoded.args.size)
        assertArrayEquals(cert, decoded.args[0].data)
        assertEquals(8, decoded.args[0].datatype)
    }

    // --- Client over a fake stream ---

    @Test fun getDeviceIdReadsU32() {
        val client = client { req -> reply(req, Arg(4, u32(0x200))) }
        assertEquals(0x200L, client.getDeviceId())
        assertTrue(connections.single().closed)
        assertArrayEquals(
            hex(0x04, 0, 0, 0, 0x08, 0x00, 0x10, 0x00),
            connections.single().written.toByteArray(),
        )
    }

    @Test fun getCertificateSendsCapacityAndReturnsBlob() {
        val cert = ByteArray(650) { (it * 3).toByte() }
        val client = client { req ->
            assertEquals(CarMfiProtocol.OP_GET_CERTIFICATE, req.opcode)
            assertEquals(1024, CarMfiProtocol.readLe32(req.args.single().data))
            reply(req, Arg(8, cert))
        }
        assertArrayEquals(cert, client.getCertificate())
    }

    @Test fun createSignatureSendsDigestAndReturnsSignature() {
        val signature = ByteArray(128) { 0x55 }
        val digest = ByteArray(20) { it.toByte() }
        val client = client { req ->
            assertArrayEquals(digest, req.args.single().data)
            reply(req, Arg(8, signature))
        }
        assertArrayEquals(signature, client.createSignature(digest))
    }

    @Test fun readSerialReturnsBlob() {
        val client = client { req -> reply(req, Arg(8, "0A1B2C".toByteArray())) }
        assertEquals("0A1B2C", String(client.readCertificateSerial()))
    }

    @Test fun txIdIncrementsPerCall() {
        val seen = mutableListOf<Int>()
        val client = client { req -> seen += req.txId; reply(req, Arg(4, u32(0x200))) }
        client.getDeviceId(); client.getDeviceId(); client.getDeviceId()
        assertEquals(listOf(0, 1, 2), seen)
        assertEquals(3, connections.size)
    }

    @Test fun nonZeroStatusIsAnError() {
        val client = client { req -> reply(req, status = -5) }
        val error = try { client.getCertificate(); null } catch (e: CarMfiException) { e }
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("status -5"))
        assertTrue(connections.single().closed)
    }

    @Test fun txIdMismatchIsAnError() {
        val client = client { req -> CarMfiProtocol.encodeBody(req.txId + 1, req.opcode, listOf(Arg(4, u32(1)), Arg(5, u32(0)))) }
        try { client.getDeviceId(); fail() } catch (e: CarMfiException) {
            assertTrue(e.message!!.contains("tx_id mismatch"))
        }
    }

    @Test fun wrongOpcodeIsAnError() {
        val client = client { req -> CarMfiProtocol.encodeBody(req.txId, 3, listOf(Arg(4, u32(1)), Arg(5, u32(0)))) }
        try { client.getDeviceId(); fail() } catch (e: CarMfiException) {
            assertTrue(e.message!!.contains("opcode mismatch"))
        }
    }

    @Test fun oversizeReplyIsRejectedBeforeReading() {
        val client = client(rawLength = 1021) { req -> reply(req, Arg(4, u32(1))) }
        try { client.getDeviceId(); fail() } catch (e: CarMfiException) {
            assertTrue(e.message!!.contains("bad reply length 1021"))
        }
    }

    @Test fun zeroLengthReplyIsRejected() {
        val client = client(rawLength = 0) { req -> reply(req, Arg(4, u32(1))) }
        try { client.getDeviceId(); fail() } catch (e: CarMfiException) {
            assertTrue(e.message!!.contains("bad reply length 0"))
        }
    }

    @Test fun truncatedStreamIsAnError() {
        val client = client(truncateTo = 8) { req -> reply(req, Arg(4, u32(1))) }
        try { client.getDeviceId(); fail() } catch (e: CarMfiException) {
            assertTrue(e.message!!.startsWith("socket failed"))
        }
    }

    @Test fun wrongReplyShapeIsAnError() {
        val client = client { req -> reply(req, Arg(8, ByteArray(4))) }
        try { client.getDeviceId(); fail() } catch (e: CarMfiException) {
            assertTrue(e.message!!.contains("reply shape"))
        }
    }

    @Test fun connectFailureKeepsTheExactMessage() {
        val client = CarMfiClient { throw IOException("connect failed: ECONNREFUSED (Connection refused)") }
        try { client.getDeviceId(); fail() } catch (e: CarMfiException) {
            assertTrue(e.message!!.contains("ECONNREFUSED"))
        }
    }

    @Test fun timeoutFromTheSocketIsReported() {
        val client = CarMfiClient {
            object : CarMfiConnection {
                override val output: OutputStream = ByteArrayOutputStream()
                override val input: InputStream = object : InputStream() {
                    override fun read(): Int = throw java.net.SocketTimeoutException("Read timed out")
                }
                override fun close() {}
            }
        }
        try { client.getDeviceId(); fail() } catch (e: CarMfiException) {
            assertTrue(e.message!!.contains("SocketTimeoutException"))
        }
    }

    @Test fun decoderRejectsGarbage() {
        try { CarMfiProtocol.decodeBody(hex(0x08, 0x00, 0x10)); fail() } catch (_: CarMfiException) {}
        try { CarMfiProtocol.decodeBody(hex(0x08, 0x00, 0x10, 0x00, 0x1A, 0x7F)); fail() } catch (_: CarMfiException) {}
        try { CarMfiProtocol.decodeBody(hex(0x22, 0x00)); fail() } catch (_: CarMfiException) {}
        try { CarMfiProtocol.decodeBody(hex(0x08, 0x00)); fail() } catch (_: CarMfiException) {}
    }

    // --- Authenticator ---

    private fun chipClient(deviceId: Int, certSize: Int = 600, signatureSize: Int = 128): CarMfiClient =
        client { req ->
            when (req.opcode) {
                CarMfiProtocol.OP_GET_DEVICE_ID -> reply(req, Arg(4, u32(deviceId)))
                CarMfiProtocol.OP_GET_CERTIFICATE -> reply(req, Arg(8, ByteArray(certSize) { 7 }))
                CarMfiProtocol.OP_CREATE_SIGNATURE -> reply(req, Arg(8, ByteArray(signatureSize) { 9 }))
                else -> reply(req, status = -1)
            }
        }

    @Before fun resetState() { CarMfiState.reset() }

    @Test fun twoPointZeroCChipIsMajor2() {
        val lines = mutableListOf<String>()
        val auth = CarMfiAuthenticator.open(chipClient(0x200)) { lines += it }
        assertEquals(2, auth.protocolMajor())
        assertEquals(600, auth.readCertificate().size)
        assertEquals(128, auth.signChallenge(ByteArray(20)).size)
        assertTrue(lines.any { it.contains("deviceId=0x200") })
    }

    @Test fun threePointZeroChipIsMajor3() {
        val auth = CarMfiAuthenticator.open(chipClient(0x300, signatureSize = 64)) {}
        assertEquals(3, auth.protocolMajor())
        assertEquals(64, auth.signChallenge(ByteArray(32)).size)
    }

    @Test fun unknownChipVersionIsRefused() {
        val lines = mutableListOf<String>()
        try { CarMfiAuthenticator.open(chipClient(0x100)) { lines += it }; fail() } catch (e: MfiInvalidDataException) {
            assertTrue(e.message!!.contains("not supported by this build"))
        }
        // Nothing but the id was asked for.
        assertEquals(1, connections.size)
    }

    @Test fun unexpectedSignatureLengthFailsAndDisablesForTheRun() {
        val lines = mutableListOf<String>()
        val auth = CarMfiAuthenticator.open(chipClient(0x200, signatureSize = 64)) { lines += it }
        try { auth.signChallenge(ByteArray(20)); fail() } catch (_: MfiAuthenticationFailedException) {}
        assertNotNull(CarMfiState.failure)
        assertTrue(lines.any { it.contains("uses the bundled identity") })
    }

    @Test fun signingErrorFailsAndDisablesForTheRun() {
        var calls = 0
        val client = client { req ->
            when (req.opcode) {
                CarMfiProtocol.OP_GET_DEVICE_ID -> reply(req, Arg(4, u32(0x200)))
                CarMfiProtocol.OP_GET_CERTIFICATE -> reply(req, Arg(8, ByteArray(10)))
                else -> { calls++; reply(req, status = -2) }
            }
        }
        val auth = CarMfiAuthenticator.open(client) {}
        assertNull(CarMfiState.failure)
        try { auth.signChallenge(ByteArray(20)); fail() } catch (_: MfiAuthenticationFailedException) {}
        assertEquals(1, calls)
        assertNotNull(CarMfiState.failure)
        CarMfiState.reset()
        assertNull(CarMfiState.failure)
    }

    @Test fun emptyCertificateIsRefused() {
        try { CarMfiAuthenticator.open(chipClient(0x200, certSize = 0)) {}; fail() } catch (_: Exception) {}
    }

    @Test fun airPlayAuthSetupSendsTheDigestSizeForTheChipVersion() {
        for ((deviceId, digestBytes, signatureBytes) in listOf(Triple(0x200, 20, 128), Triple(0x300, 32, 64))) {
            val sizes = mutableListOf<Int>()
            val client = client { req ->
                when (req.opcode) {
                    CarMfiProtocol.OP_GET_DEVICE_ID -> reply(req, Arg(4, u32(deviceId)))
                    CarMfiProtocol.OP_GET_CERTIFICATE -> reply(req, Arg(8, ByteArray(600) { 3 }))
                    else -> { sizes += req.args.single().data.size; reply(req, Arg(8, ByteArray(signatureBytes))) }
                }
            }
            val auth = CarMfiAuthenticator.open(client) {}
            val peerKey = ByteArray(32) { (it + 1).toByte() }
            val response = com.shilapi.xcertplay.airplay.MfiSapAuthSetup.handle(byteArrayOf(1) + peerKey, auth)
            assertNotNull(response)
            assertEquals(listOf(digestBytes), sizes)
            // 32-byte key, u32 + certificate, u32 + signature.
            assertEquals(32 + 4 + 600 + 4 + signatureBytes, response!!.size)
        }
    }

    // --- Probe ---

    @Test fun probeReportsEachStepAndSummary() {
        val lines = mutableListOf<String>()
        val client = client { req ->
            when (req.opcode) {
                CarMfiProtocol.OP_GET_DEVICE_ID -> reply(req, Arg(4, u32(0x200)))
                CarMfiProtocol.OP_READ_CERTIFICATE_SERIAL -> reply(req, Arg(8, "0123456789ABCDEF0123456789AB".toByteArray()))
                CarMfiProtocol.OP_GET_CERTIFICATE -> reply(req, Arg(8, ByteArray(500) { 1 }))
                else -> {
                    assertArrayEquals(CarMfiProbe.TEST_DIGEST, req.args.single().data)
                    reply(req, Arg(8, ByteArray(128)))
                }
            }
        }
        CarMfiProbe.run(client) { lines += it }
        assertTrue(lines.any { it.contains("deviceId=0x200 (MFi 2.0C)") })
        assertTrue(lines.any { it.contains("cert bytes=500") })
        assertTrue(lines.any { it.contains("signature bytes=128") })
        assertTrue(CarMfiProbe.summary.startsWith("working id=0x200"))
        // The report redactor drops these words and replaces long hex runs.
        assertFalse(lines.any { it.contains("certificate", ignoreCase = true) || Regex("[0-9a-f]{24,}").containsMatchIn(it) })
    }

    @Test fun probeSendsA32ByteDigestToAnMfi3Chip() {
        val lines = mutableListOf<String>()
        val client = client { req ->
            when (req.opcode) {
                CarMfiProtocol.OP_GET_DEVICE_ID -> reply(req, Arg(4, u32(0x300)))
                CarMfiProtocol.OP_READ_CERTIFICATE_SERIAL -> reply(req, Arg(8, "0123456789ABCDEF0123456789AB".toByteArray()))
                CarMfiProtocol.OP_GET_CERTIFICATE -> reply(req, Arg(8, ByteArray(608) { 1 }))
                else -> {
                    assertEquals(32, req.args.single().data.size)
                    reply(req, Arg(8, ByteArray(64)))
                }
            }
        }
        CarMfiProbe.run(client) { lines += it }
        assertTrue(lines.any { it.contains("signature bytes=64 digestBytes=32") })
        assertTrue(CarMfiProbe.summary.startsWith("working id=0x300"))
    }

    @Test fun probeKeepsGoingAndLogsTheExactFailure() {
        val lines = mutableListOf<String>()
        CarMfiProbe.run(CarMfiClient { throw IOException("connect: EACCES (Permission denied)") }) { lines += it }
        assertEquals(4, lines.count { Regex("^Car MFi chip: \\w+ failed ms=").containsMatchIn(it) })
        assertTrue(lines.any { it.contains("EACCES") })
        assertTrue(CarMfiProbe.summary.startsWith("unavailable"))
    }
}
