package com.shilapi.xcertplay.mfi

import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MG4 only, read only: asks the car's MFi chip for its id, serial and certificate, and signs a fixed
 * test digest, then logs what happened. Runs once per process on its own thread and never throws.
 * The lines go to the session log (and so the diagnostic report); a later session replays them.
 *
 * Log lines avoid the words the report redactor drops (certificate, token) and long hex runs.
 */
object CarMfiProbe {
    private const val TAG = "CarMfiProbe"
    private val started = AtomicBoolean(false)
    private val lines = ArrayList<String>()

    @Volatile var summary: String = "not run yet"
        private set

    /** 20 bytes, the SHA-1 digest size a 2.0C chip signs. Not a real digest of anything. */
    val TEST_DIGEST = ByteArray(20) { (it + 1).toByte() }

    /** 32 bytes, the SHA-256 digest size a 3.0 chip signs. Sending it 20 bytes leaves mfid waiting. */
    val TEST_DIGEST_3 = ByteArray(32) { (it + 1).toByte() }

    /** The digest the chip with [deviceId] expects: 32 bytes for 0x300, 20 otherwise. */
    fun testDigestFor(deviceId: Long?): ByteArray = if (deviceId == 0x300L) TEST_DIGEST_3 else TEST_DIGEST

    fun start(log: (String) -> Unit) {
        if (!started.compareAndSet(false, true)) {
            synchronized(lines) { lines.toList() }.forEach(log)
            return
        }
        Thread({
            val emit: (String) -> Unit = { line ->
                synchronized(lines) { lines += line }
                Log.i(TAG, line)
                try { log(line) } catch (_: Exception) {}
            }
            try {
                run(CarMfiClient.local(), emit)
            } catch (error: Throwable) {
                summary = "probe error ${error.javaClass.simpleName}"
                emit("Car MFi chip: probe stopped: ${CarMfiClient.describe(error)}")
            }
        }, "car-mfi-probe").start()
    }

    /** Runs the probe on the calling thread. Separate from [start] so tests can drive it. */
    fun run(client: CarMfiClient, emit: (String) -> Unit) {
        emit("Car MFi chip: probe start socket=${CarMfiClient.SOCKET_PATH}")
        emit("Car MFi chip: selinux context=${readText("/proc/self/attr/current") ?: "unreadable"} " +
            "enforce=${readText("/sys/fs/selinux/enforce") ?: "unreadable"}")
        val socketFile = File(CarMfiClient.SOCKET_PATH)
        emit("Car MFi chip: socket exists=${socketFile.exists()} canWrite=${socketFile.canWrite()} " +
            "mfid service=${systemProperty("init.svc.mfid") ?: "unknown"}")

        var id: Long? = null
        var certBytes = -1
        var signatureBytes = -1
        var failed: String? = null

        fun <T> step(name: String, block: () -> T): T? {
            val started = System.nanoTime()
            return try {
                block().also { emit("Car MFi chip: $name ok ms=${elapsed(started)}") }
            } catch (error: Throwable) {
                val text = "$name failed ms=${elapsed(started)}: ${CarMfiClient.describe(error)}"
                emit("Car MFi chip: $text")
                if (failed == null) failed = text
                null
            }
        }

        step("GetDeviceId") { client.getDeviceId() }?.let {
            id = it
            val meaning = when (it) {
                0x200L -> "MFi 2.0C"
                0x300L -> "MFi 3.0"
                else -> "unknown"
            }
            emit("Car MFi chip: deviceId=0x${it.toString(16)} ($meaning)")
        }
        step("ReadSerial") { client.readCertificateSerial() }?.let { raw ->
            val text = String(raw, Charsets.ISO_8859_1).trimEnd('\u0000')
            val printable = text.isNotEmpty() && text.all { it in ' '..'~' }
            val shown = if (printable) text.chunked(8).joinToString(" ") else "binary ${raw.size} bytes"
            emit("Car MFi chip: serial bytes=${raw.size} value=$shown")
        }
        step("GetCert") { client.getCertificate() }?.let { cert ->
            certBytes = cert.size
            val sha = MessageDigest.getInstance("SHA-256").digest(cert)
            // Short on purpose: the report redactor replaces any hex run of 24 or more.
            emit("Car MFi chip: cert bytes=${cert.size} sha256=${sha.take(8).joinToString("") { "%02x".format(it) }}...")
        }
        val digest = testDigestFor(id)
        step("CreateSignature") { client.createSignature(digest) }?.let { signature ->
            signatureBytes = signature.size
            emit("Car MFi chip: signature bytes=${signature.size} digestBytes=${digest.size}")
        }

        val version = id?.let { CarMfiAuthenticator.supportedMajor(it) }
        summary = when {
            failed != null && id == null -> "unavailable: ${failed}"
            failed != null -> "partly working (id=0x${id?.toString(16)}): $failed"
            version == null -> "id=0x${id?.toString(16)} not supported by this build"
            else -> "working id=0x${id?.toString(16)} mfi=$version certBytes=$certBytes signatureBytes=$signatureBytes"
        }
        emit("Car MFi chip: probe result $summary")
    }

    private fun elapsed(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000

    private fun readText(path: String): String? = try {
        File(path).readText().trim().trimEnd('\u0000').ifEmpty { null }
    } catch (_: Throwable) {
        null
    }

    private fun systemProperty(name: String): String? = try {
        val clazz = Class.forName("android.os.SystemProperties")
        (clazz.getMethod("get", String::class.java).invoke(null, name) as? String)?.ifEmpty { null }
    } catch (_: Throwable) {
        null
    }
}
