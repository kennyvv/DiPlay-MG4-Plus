package com.shilapi.xcertplay.media

import java.util.Random
import kotlin.math.sqrt
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class EchoSuppressorTest {
    private val rate = 48_000
    private val hop = rate / 100

    private fun pcm(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[i * 2] = s.toInt().toByte()
            out[i * 2 + 1] = (s.toInt() shr 8).toByte()
        }
        return out
    }

    private fun rms(data: ByteArray): Double {
        var sum = 0.0
        for (i in 0 until data.size / 2) {
            val s = ((data[i * 2 + 1].toInt() shl 8) or (data[i * 2].toInt() and 0xff)).toShort().toInt()
            sum += s.toDouble() * s
        }
        return sqrt(sum / (data.size / 2))
    }

    private class Scenario(val seconds: Int, val farActive: Boolean, val nearActive: Boolean)

    private fun run(scenarios: List<Scenario>, delayHops: Int = 25, coupling: Double = 0.3): List<Pair<Double, Double>> {
        var now = 1_000_000_000L
        val reference = EchoReference { now }
        val suppressor = EchoSuppressor(reference, rate)
        val random = Random(7)
        val farLevels = ArrayList<Double>()
        var burstLeft = 0
        var burstOn = true
        val results = ArrayList<Pair<Double, Double>>()
        for (scenario in scenarios) {
            var inSum = 0.0
            var outSum = 0.0
            var counted = 0
            val hops = scenario.seconds * 100
            for (h in 0 until hops) {
                if (burstLeft == 0) {
                    burstOn = !burstOn
                    burstLeft = 10 + random.nextInt(40)
                }
                burstLeft--
                val farAmp = if (scenario.farActive && burstOn) 3000.0 + random.nextInt(3000) else 0.0
                farLevels.add(farAmp)
                val far = ShortArray(hop) { (random.nextGaussian() * farAmp / 2).toInt().toShort() }
                reference.push(pcm(far), 0, far.size * 2, rate, 1)
                val echoAmp = farLevels.getOrNull(farLevels.size - 1 - delayHops) ?: 0.0
                val mic = ShortArray(hop) {
                    val echo = random.nextGaussian() * echoAmp / 2 * coupling
                    val near = if (scenario.nearActive) random.nextGaussian() * 2500 else 0.0
                    (echo + near + random.nextGaussian() * 5).toInt().coerceIn(-32768, 32767).toShort()
                }
                val data = pcm(mic)
                val before = rms(data)
                suppressor.process(data)
                if (h >= hops / 2) {
                    inSum += before * before
                    outSum += rms(data).let { it * it }
                    counted++
                }
                now += 10_000_000L
            }
            results.add(sqrt(inSum / counted) to sqrt(outSum / counted))
        }
        return results
    }

    @Test
    fun removesEchoOfTheOtherPerson() {
        val (echoIn, echoOut) = run(listOf(Scenario(12, farActive = true, nearActive = false))).single()
        assertTrue("echo $echoIn became $echoOut", echoOut < echoIn * 0.25)
    }

    @Test
    fun keepsYourOwnVoiceWhenTheOtherPersonIsSilent() {
        val results = run(
            listOf(
                Scenario(12, farActive = true, nearActive = false),
                Scenario(6, farActive = false, nearActive = true),
            ),
        )
        val (nearIn, nearOut) = results[1]
        assertTrue("near voice $nearIn became $nearOut", nearOut > nearIn * 0.97)
    }

    @Test
    fun keepsYourVoiceWhenYouSpeakOverTheOtherPerson() {
        val results = run(
            listOf(
                Scenario(12, farActive = true, nearActive = false),
                Scenario(6, farActive = true, nearActive = true),
            ),
        )
        val (talkIn, talkOut) = results[1]
        assertTrue("double talk $talkIn became $talkOut", talkOut > talkIn * 0.75)
    }

    @Test
    fun leavesAudioAloneWhenNothingPlays() {
        val suppressor = EchoSuppressor(EchoReference { 0L }, rate)
        val mic = pcm(ShortArray(hop * 2) { (it % 200 * 40 - 4000).toShort() })
        val copy = mic.copyOf()
        suppressor.process(mic)
        assertEquals(copy.toList(), mic.toList())
    }

    @Test
    fun forgetsCallAudioAfterItStops() {
        var now = 1_000_000_000L
        val reference = EchoReference { now }
        val loud = pcm(ShortArray(hop) { if (it % 2 == 0) 8000 else -8000 })
        repeat(50) {
            reference.push(loud, 0, loud.size, rate, 1)
            now += 10_000_000L
        }
        now += 2_000_000_000L
        val history = FloatArray(80)
        reference.history(history)
        assertTrue("history should be silent after a pause", history.all { it == 0f })
    }
}
