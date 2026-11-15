package com.shilapi.xcertplay.mfi

import java.util.concurrent.atomic.AtomicInteger

/**
 * The car's own MFi chip as an [MfiAuthenticator]. The certificate and every signature come from
 * the same chip; nothing here mixes in the bundled identity. If a call fails part way, the
 * session fails and [CarMfiState] makes the next connection use the bundled identity.
 *
 * Version, from the chip's device id: 0x200 (2.0C) answers with RSA-1024 over a SHA-1 digest
 * (128-byte signature), 0x300 (3.0) with ECDSA P-256 over a SHA-256 digest (64 bytes). The
 * AirPlay auth-setup path picks the digest from [protocolMajor]; the iAP2 path forwards the
 * phone's challenge unchanged. Any other id is refused by [open].
 */
class CarMfiAuthenticator private constructor(
    private val client: CarMfiClient,
    val deviceId: Long,
    private val major: Int,
    private val certificate: ByteArray,
    private val log: (String) -> Unit,
) : MfiAuthenticator {
    private val signatures = AtomicInteger(0)

    override fun protocolMajor(): Int = major

    override fun readCertificate(maximumOutputLength: Int): ByteArray {
        require(maximumOutputLength > 0 && certificate.size <= maximumOutputLength) {
            "Car MFi certificate exceeds the requested output limit"
        }
        return certificate.copyOf()
    }

    override fun signChallenge(challenge: ByteArray): ByteArray {
        val started = System.nanoTime()
        val signature = try {
            client.createSignature(challenge)
        } catch (error: Exception) {
            fail("sign failed digestBytes=${challenge.size}: ${CarMfiClient.describe(error)}")
            throw MfiAuthenticationFailedException(null, error)
        }
        val expected = expectedSignatureBytes(major)
        if (signature.isEmpty() || signature.size > MAX_SIGNATURE_BYTES || signature.size != expected) {
            fail("sign returned ${signature.size} bytes, expected $expected for MFi $major")
            throw MfiAuthenticationFailedException(null)
        }
        log(
            "Car MFi chip: signature count=${signatures.incrementAndGet()} digestBytes=${challenge.size} " +
                "signatureBytes=${signature.size} ms=${(System.nanoTime() - started) / 1_000_000}",
        )
        return signature
    }

    private fun fail(reason: String) {
        CarMfiState.markFailed(reason)
        log("Car MFi chip: $reason; the next connection uses the bundled identity")
    }

    companion object {
        private const val MAX_SIGNATURE_BYTES = 128

        /** The MFi major version for a device id, or null when this build cannot serve it. */
        fun supportedMajor(deviceId: Long): Int? = when (deviceId) {
            0x200L -> 2
            0x300L -> 3
            else -> null
        }

        fun expectedSignatureBytes(major: Int): Int = if (major == 2) 128 else 64

        /**
         * Reads the id and certificate once and checks the version. Throws [CarMfiException] or
         * [MfiInvalidDataException] if the chip cannot be used; the caller then falls back to
         * the bundled identity before anything has been sent to the phone.
         */
        fun open(client: CarMfiClient, log: (String) -> Unit): CarMfiAuthenticator {
            val id = client.getDeviceId()
            val major = supportedMajor(id)
                ?: throw MfiInvalidDataException(
                    "car MFi chip version 0x${id.toString(16)} not supported by this build",
                )
            val certificate = client.getCertificate()
            if (certificate.isEmpty()) throw MfiInvalidDataException("car MFi chip returned an empty certificate")
            log("Car MFi chip: ready deviceId=0x${id.toString(16)} mfi=$major certBytes=${certificate.size}")
            return CarMfiAuthenticator(client, id, major, certificate, log)
        }
    }
}

/** Process-wide: once the car chip fails a real exchange, stop using it until the app restarts. */
object CarMfiState {
    @Volatile var failure: String? = null
        private set

    fun markFailed(reason: String) {
        failure = reason
    }

    fun reset() {
        failure = null
    }
}
