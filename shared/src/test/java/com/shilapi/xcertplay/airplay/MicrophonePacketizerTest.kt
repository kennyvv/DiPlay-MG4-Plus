package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class MicrophonePacketizerTest {
    @Test
    fun packetUsesRtpHeaderAndInputKeyAad() {
        val key = ByteArray(32) { index -> (index + 1).toByte() }
        val counters = MicrophoneCounters()
        val body = byteArrayOf(0x28, 0x01, 0x02, 0x03)

        val packet = MicrophonePacketizer.sealPacket(key, 100, counters, body, 480)

        assertEquals(0x80, packet[0].toInt() and 0xff)
        assertEquals(100, packet[1].toInt() and 0xff)
        assertEquals(0, ((packet[2].toInt() and 0xff) shl 8) or (packet[3].toInt() and 0xff))
        assertEquals(
            0,
            ((packet[4].toInt() and 0xff) shl 24) or
                ((packet[5].toInt() and 0xff) shl 16) or
                ((packet[6].toInt() and 0xff) shl 8) or
                (packet[7].toInt() and 0xff),
        )

        val sealedEnd = packet.size - MicrophonePacketizer.NONCE_LEN
        val nonce = ByteArray(12)
        packet.copyInto(nonce, 4, sealedEnd, packet.size)
        val opened = AirPlayCrypto.chachaOpen(
            key,
            nonce,
            packet.copyOfRange(MicrophonePacketizer.RTP_HEADER_LEN, sealedEnd),
            packet.copyOfRange(4, MicrophonePacketizer.RTP_HEADER_LEN),
        )

        assertArrayEquals(body, opened)
        assertEquals(1, counters.sequence)
        assertEquals(480, counters.timestamp)
        assertEquals(1L, counters.nonce)
    }

    private fun opusConfig(rtpClockRate: Int) = MicrophoneConfig(
        audioType = "speechrecognition",
        sampleRate = 48_000,
        channels = 1,
        payloadType = 100,
        frameMillis = 20,
        host = InetAddress.getLoopbackAddress(),
        port = 7000,
        key = ByteArray(32),
        codec = AudioCodecKind.OPUS,
        bitrate = 64_000,
        rtpClockRate = rtpClockRate,
    )

    @Test
    fun opusTimestampStepFollowsNegotiatedRtpClock() {
        assertEquals(320, opusConfig(16_000).rtpTimestampStep)
        assertEquals(480, opusConfig(24_000).rtpTimestampStep)
        assertEquals(960, opusConfig(48_000).rtpTimestampStep)

        // Capture stays at 48 kHz: 960 samples and 1,920 bytes of mono PCM per 20 ms frame.
        val config = opusConfig(24_000)
        assertEquals(960, config.samplesPerPacket)
        assertEquals(1920, config.frameBytes)
    }

    @Test
    fun rtpClockDefaultsToSampleRate() {
        val config = MicrophoneConfig(
            audioType = "telephony",
            sampleRate = 16_000,
            channels = 1,
            payloadType = 100,
            frameMillis = 20,
            host = InetAddress.getLoopbackAddress(),
            port = 7000,
            key = ByteArray(32),
        )
        assertEquals(16_000, config.rtpClockRate)
        assertEquals(320, config.rtpTimestampStep)
    }

    @Test
    fun pcmTimestampStepIsSamplesPerPacket() {
        val config = MicrophoneConfig(
            audioType = "speechrecognition",
            sampleRate = 24_000,
            channels = 1,
            payloadType = 100,
            frameMillis = 20,
            host = InetAddress.getLoopbackAddress(),
            port = 7000,
            key = ByteArray(32),
        )
        assertEquals(480, config.rtpTimestampStep)
        assertEquals(config.samplesPerPacket, config.rtpTimestampStep)
    }

    @Test
    fun opusPacketsAdvanceTimestampByNegotiatedStep() {
        val config = opusConfig(24_000)
        val counters = MicrophoneCounters()
        val body = ByteArray(40)

        MicrophonePacketizer.sealPacket(config.key, config.payloadType, counters, body, config.rtpTimestampStep)
        assertEquals(480, counters.timestamp)
        MicrophonePacketizer.sealPacket(config.key, config.payloadType, counters, body, config.rtpTimestampStep)
        assertEquals(960, counters.timestamp)
    }

    @Test
    fun pcmIsConvertedToBigEndian() {
        assertArrayEquals(
            byteArrayOf(0x12, 0x34, 0x56, 0x78),
            MicrophonePacketizer.toWirePcm(
                byteArrayOf(0x34, 0x12, 0x78, 0x56),
            ),
        )
    }

    @Test
    fun pcmPacketsFitOneLinkMtuWithTheirIpv6AndUdpHeaders() {
        // Upstream DiPlay #1094: 20 ms of 48 kHz mono sealed to 1,956 bytes and left as IP fragments.
        assertEquals(10, MicrophoneConfig.pcmFrameMillis(48_000, 1))
        assertEquals(20, MicrophoneConfig.pcmFrameMillis(24_000, 1))
        assertEquals(20, MicrophoneConfig.pcmFrameMillis(16_000, 1))
        assertEquals(5, MicrophoneConfig.pcmFrameMillis(48_000, 2))

        for ((sampleRate, channels) in listOf(48_000 to 1, 48_000 to 2, 44_100 to 1, 24_000 to 1, 16_000 to 1, 8_000 to 1)) {
            val pcm = MicrophoneConfig(
                audioType = "speechrecognition",
                sampleRate = sampleRate,
                channels = channels,
                payloadType = 100,
                frameMillis = MicrophoneConfig.pcmFrameMillis(sampleRate, channels),
                host = InetAddress.getLoopbackAddress(),
                port = 7000,
                key = ByteArray(32),
            )
            val packet = MicrophonePacketizer.sealPacket(ByteArray(32), 100, MicrophoneCounters(),
                ByteArray(pcm.frameBytes), pcm.samplesPerPacket)
            assertTrue("$sampleRate Hz x$channels: ${packet.size} bytes", 40 + 8 + packet.size <= 1500)
        }
    }
}
