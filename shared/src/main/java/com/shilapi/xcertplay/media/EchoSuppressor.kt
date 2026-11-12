package com.shilapi.xcertplay.media

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Test switch for the call echo suppressor, set by the host from settings before CarPlay starts.
 * On by default: this head unit has no Android echo canceller (`aec=unavailable`, car test
 * 2026-11-13), so the person on the other end of a call heard themselves.
 */
object CallEchoSuppression {
    @Volatile var enabled: Boolean = true
}

/**
 * Remembers how loud the call audio coming from the iPhone was, 10 ms at a time, so the
 * microphone path can tell when the car speakers are playing the other person's voice.
 * The sink adds each block it hands to the call track; [EchoSuppressor] reads the history.
 */
internal class EchoReference(private val clock: () -> Long = System::nanoTime) {
    private val ring = FloatArray(HISTORY)
    private var latest = -1L
    private var timelineNs = 0L
    private var accumulator = 0.0
    private var accumulated = 0
    private var rate = 0
    private var channels = 0

    /** Adds 16-bit little-endian PCM that was just written to the call track. */
    @Synchronized
    fun push(data: ByteArray, offset: Int, length: Int, sampleRate: Int, channelCount: Int) {
        if (sampleRate <= 0 || channelCount <= 0 || length < 2) return
        if (sampleRate != rate || channelCount != channels) {
            rate = sampleRate
            channels = channelCount
            accumulator = 0.0
            accumulated = 0
        }
        val now = clock()
        // After a pause in the call audio, fill the gap with silence so old speech ages out.
        if (timelineNs != 0L && now - timelineNs > GAP_NS) {
            var gapHops = min((now - timelineNs) / HOP_NS, HISTORY.toLong())
            while (gapHops-- > 0) ring[(++latest % HISTORY).toInt()] = 0f
        }
        val hopSamples = rate * channels * HOP_MS / 1000
        var i = offset
        val end = offset + length - 1
        while (i < end) {
            val sample = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xff)).toShort().toInt()
            accumulator += sample.toDouble() * sample
            accumulated++
            if (accumulated >= hopSamples) {
                ring[(++latest % HISTORY).toInt()] = sqrt(accumulator / accumulated).toFloat()
                accumulator = 0.0
                accumulated = 0
            }
            i += 2
        }
        val durationNs = (length / 2L) * 1_000_000_000L / (rate.toLong() * channels)
        timelineNs = max(timelineNs, now) + durationNs
    }

    /**
     * Fills [out] with the call audio's loudness (RMS, 16-bit units) per 10 ms, newest first:
     * `out[0]` is the most recent hop. Returns false when nothing was played yet.
     */
    @Synchronized
    fun history(out: FloatArray): Boolean {
        if (latest < 0) {
            out.fill(0f)
            return false
        }
        val now = clock()
        val gap = if (now > timelineNs) ((now - timelineNs) / HOP_NS).toInt() else 0
        for (lag in out.indices) {
            val back = lag - gap
            out[lag] = if (back < 0 || back >= HISTORY || latest - back < 0) {
                0f
            } else {
                ring[((latest - back) % HISTORY).toInt()]
            }
        }
        return true
    }

    @Synchronized
    fun reset() {
        ring.fill(0f)
        latest = -1L
        timelineNs = 0L
        accumulator = 0.0
        accumulated = 0
    }

    companion object {
        const val HOP_MS = 10
        const val HISTORY = 128
        private const val HOP_NS = HOP_MS * 1_000_000L
        private const val GAP_NS = 60_000_000L

        /** The one call stream, shared by the speaker side and the microphone side. */
        val shared = EchoReference()
    }
}

/**
 * Software echo suppression for the call microphone.
 *
 * The call plays through the car speakers and the microphone hears it, so the other person
 * hears themselves a moment later. Android's `AcousticEchoCanceler` is not available on this
 * head unit, so this works from loudness only: it learns the delay and strength of the echo
 * from the call audio that was played, then turns the microphone down while the microphone is
 * no louder than that echo. When you speak over the other person, your voice is louder than
 * the echo, so it passes through (the echo is still sent under it).
 */
internal class EchoSuppressor(
    private val reference: EchoReference,
    private val sampleRate: Int,
) {
    private val history = FloatArray(LAGS)
    private val emF = DoubleArray(LAGS)
    private val emMF = DoubleArray(LAGS)
    private val emFF = DoubleArray(LAGS)
    private var emM = 0.0
    private var emMM = 0.0
    private var echoGain = 1.0
    private var bestLag = -1
    private var bestCorr = 0.0
    private var gain = 1.0
    private var blocks = 0
    private var suppressedBlocks = 0

    /** Suppresses echo in place in 16-bit little-endian mono PCM. */
    fun process(data: ByteArray, length: Int = data.size) {
        val hop = max(1, sampleRate * EchoReference.HOP_MS / 1000)
        val total = length / 2
        var start = 0
        while (start < total) {
            val count = min(hop, total - start)
            processBlock(data, start, count)
            start += count
        }
    }

    private fun processBlock(data: ByteArray, start: Int, count: Int) {
        var sum = 0.0
        for (n in 0 until count) {
            val s = sample(data, start + n)
            sum += s.toDouble() * s
        }
        val mic = sqrt(sum / count)
        val hasReference = reference.history(history)
        if (hasReference) learnDelay(mic)

        val locked = bestLag >= 0 && bestCorr >= LOCK_CORRELATION
        var farRef = 0f
        if (locked) {
            farRef = windowMax()
        } else {
            for (lag in 0 until LAGS) farRef = max(farRef, history[lag])
        }

        val target: Double
        if (!hasReference || farRef < FAR_THRESHOLD) {
            target = 1.0
        } else {
            if (locked) learnCoupling(mic, farRef)
            val echo = MARGIN * echoGain * farRef
            val ratioSquared = (mic * mic) / (echo * echo + 1e-6)
            target = if (ratioSquared <= 1.0) FLOOR else max(FLOOR, sqrt(1.0 - 1.0 / ratioSquared))
        }

        val previous = gain
        gain += (target - gain) * (if (target > gain) ATTACK else RELEASE)
        blocks++
        if (gain < 0.5) suppressedBlocks++
        if (previous == 1.0 && gain == 1.0) return
        for (n in 0 until count) {
            val g = previous + (gain - previous) * (n + 1) / count
            val scaled = Math.round(sample(data, start + n) * g).toInt().coerceIn(-32768, 32767)
            val at = (start + n) * 2
            data[at] = scaled.toByte()
            data[at + 1] = (scaled shr 8).toByte()
        }
    }

    private fun windowMax(): Float {
        var top = 0f
        for (lag in max(0, bestLag - LAG_SPREAD)..min(LAGS - 1, bestLag + LAG_SPREAD)) top = max(top, history[lag])
        return top
    }

    /**
     * How loud the echo is compared with the call audio. Only learned while the call audio is
     * steady around the echo's delay, so the start and end of a sentence don't skew it, and
     * it drops quickly but rises slowly so your own voice doesn't raise it.
     */
    private fun learnCoupling(mic: Double, top: Float) {
        var bottom = top
        for (lag in max(0, bestLag - LAG_SPREAD)..min(LAGS - 1, bestLag + LAG_SPREAD)) bottom = min(bottom, history[lag])
        if (bottom < STEADY * top) return
        val ratio = mic / history[bestLag].coerceAtLeast(1f)
        echoGain += if (ratio < echoGain) {
            FALL_RATE * (ratio - echoGain)
        } else {
            RISE_RATE * (min(ratio, RISE_CAP * echoGain) - echoGain)
        }
        echoGain = echoGain.coerceIn(MIN_ECHO_GAIN, MAX_ECHO_GAIN)
    }

    /** Which delay (in 10 ms hops) makes the microphone's loudness follow the call audio's. */
    private fun learnDelay(mic: Double) {
        emM += ALPHA * (mic - emM)
        emMM += ALPHA * (mic * mic - emMM)
        val varM = emMM - emM * emM
        var best = -1
        var bestScore = 0.0
        for (lag in 0 until LAGS) {
            val f = history[lag].toDouble()
            emF[lag] += ALPHA * (f - emF[lag])
            emMF[lag] += ALPHA * (mic * f - emMF[lag])
            emFF[lag] += ALPHA * (f * f - emFF[lag])
            val varF = emFF[lag] - emF[lag] * emF[lag]
            val score = (emMF[lag] - emM * emF[lag]) / sqrt(max(varF, 0.0) * max(varM, 0.0) + 1e-6)
            if (score > bestScore) {
                bestScore = score
                best = lag
            }
        }
        bestLag = best
        bestCorr = bestScore
    }

    /** A short word list for the diagnostic report. */
    fun summary(): String {
        val percent = if (blocks > 0) suppressedBlocks * 100 / blocks else 0
        val lag = if (bestLag >= 0 && bestCorr >= LOCK_CORRELATION) "${bestLag * EchoReference.HOP_MS}ms" else "none"
        return "echo=on lag=$lag corr=${"%.2f".format(java.util.Locale.ROOT, bestCorr)} " +
            "coupling=${"%.2f".format(java.util.Locale.ROOT, echoGain)} suppressed=$percent%"
    }

    private fun sample(data: ByteArray, index: Int): Int {
        val at = index * 2
        return ((data[at + 1].toInt() shl 8) or (data[at].toInt() and 0xff)).toShort().toInt()
    }

    private companion object {
        const val LAGS = 80 // 0 to 790 ms of delay between the call audio and its echo
        const val LAG_SPREAD = 3
        const val ALPHA = 0.01
        const val LOCK_CORRELATION = 0.25
        const val FAR_THRESHOLD = 60f
        const val MARGIN = 1.5
        const val FLOOR = 0.08 // about -22 dB
        const val ATTACK = 0.5
        const val RELEASE = 0.6
        const val FALL_RATE = 0.1
        const val RISE_RATE = 0.003
        const val RISE_CAP = 1.5
        const val STEADY = 0.6f
        const val MIN_ECHO_GAIN = 0.01
        const val MAX_ECHO_GAIN = 4.0
    }
}
