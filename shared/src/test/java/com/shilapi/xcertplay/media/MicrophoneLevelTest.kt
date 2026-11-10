package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophoneLevelTest {
    private fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { i, s -> bytes[i * 2] = (s and 0xff).toByte(); bytes[i * 2 + 1] = (s shr 8).toByte() }
    }

    @Test
    fun reportsPeakRmsAndClipping() {
        val level = MicrophoneLevel()
        level.add(pcm(16_384, -16_384, 16_384, -16_384))
        assertEquals("peakDb=-6.0 rmsDb=-6.0 clipped=0 floorDb=-6.0", level.summary())
        level.add(pcm(32_767, -32_768))
        assertEquals(2, Regex("clipped=(\\d+)").find(level.summary())!!.groupValues[1].toInt())
        level.reset()
        assertEquals("level=none", level.summary())
    }

    @Test
    fun floorIsTheQuietestFrame() {
        val level = MicrophoneLevel()
        level.add(pcm(16_384, -16_384))
        level.add(pcm(1_024, -1_024))
        level.add(pcm(8_192, -8_192))
        assertEquals("-30.1", Regex("floorDb=(\\S+)").find(level.summary())!!.groupValues[1])
    }

    @Test
    fun silenceIsMinusInfinity() {
        val level = MicrophoneLevel()
        level.add(pcm(0, 0, 0))
        assertEquals("peakDb=-inf rmsDb=-inf clipped=0 floorDb=-inf", level.summary())
    }
}
