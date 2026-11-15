package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class HalfRateDownsamplerTest {
    private fun pcm(samples: IntArray): ByteArray = ByteArray(samples.size * 2).also { out ->
        samples.forEachIndexed { i, v -> out[i * 2] = v.toByte(); out[i * 2 + 1] = (v shr 8).toByte() }
    }

    private fun samples(bytes: ByteArray, count: Int): IntArray =
        IntArray(count / 2) { i -> (bytes[i * 2].toInt() and 0xff) or (bytes[i * 2 + 1].toInt() shl 8) }

    private fun tone(hz: Double, n: Int, start: Int = 0) =
        IntArray(n) { i -> (10_000 * sin(2 * PI * hz * (start + i) / 48_000)).toInt() }

    private fun rms(values: IntArray) = sqrt(values.map { it.toDouble() * it }.average())

    @Test
    fun halvesTheSampleCountAndKeepsDc() {
        val down = HalfRateDownsampler()
        val out = ByteArray(2_000)
        var last = IntArray(0)
        repeat(3) {
            val bytes = down.process(pcm(IntArray(960) { 1_000 }), 0, 1_920, out)
            assertEquals(960, bytes)
            last = samples(out, bytes)
        }
        last.forEach { assertTrue("got $it", abs(it - 1_000) <= 2) }
    }

    @Test
    fun passesSpeechAndCutsAboveTheNewNyquist() {
        fun outputRms(hz: Double): Double {
            val down = HalfRateDownsampler()
            val out = ByteArray(2_000)
            var result = IntArray(0)
            for (block in 0 until 5) {
                val bytes = down.process(pcm(tone(hz, 960, block * 960)), 0, 1_920, out)
                result = samples(out, bytes)
            }
            return rms(result)
        }
        val speech = outputRms(1_000.0)
        assertTrue("1 kHz rms $speech", speech > 6_500 && speech < 7_600)
        assertTrue("18 kHz rms ${outputRms(18_000.0)}", outputRms(18_000.0) < 1_500)
    }
}

class DoubleRateUpsamplerTest {
    @Test
    fun doublesTheSampleCountAndKeepsDc() {
        val up = DoubleRateUpsampler()
        val input = ByteArray(960).also { b -> for (i in 0 until 480) { b[i * 2] = (1_000 and 0xff).toByte(); b[i * 2 + 1] = (1_000 shr 8).toByte() } }
        val out = ByteArray(1_920)
        var bytes = 0
        repeat(3) { bytes = up.process(input, 0, input.size, out) }
        assertEquals(1_920, bytes)
        for (i in 0 until 960) {
            val v = (out[i * 2].toInt() and 0xff) or (out[i * 2 + 1].toInt() shl 8)
            assertTrue("got $v at $i", kotlin.math.abs(v - 1_000) <= 3)
        }
    }
}
