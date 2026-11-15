package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt
import android.media.AudioFormat as AndroidAudioFormat

/**
 * Measures call echo without a phone call: plays a speech-like test signal through a call output
 * while recording the call microphone, then says how loud the signal came back.
 *
 * Runs twice: the normal call path (a Java call track and the plain microphone, as calls use today),
 * then the car's own phone path ([StockCallAudio]: direct phone output and the 0x200 microphone, where
 * the audio HAL cancels echo). The difference between the two echo levels is what the car's canceller
 * removes. Each recording is saved as a WAV in the directory passed to [runBoth].
 */
object CallEchoSelfTest {
    private const val TAG = "DiPlay-EchoTest"
    private const val RATE = 48_000
    private const val LEAD_MS = 1_000
    private const val SPEECH_MS = 6_000
    private const val TAIL_MS = 1_000
    private const val EXTRA_RECORD_MS = 500
    @Volatile private var saveDirectory: File? = null

    data class Result(
        val path: String,
        val ok: Boolean,
        val detail: String,
        val playedDb: Double = Double.NaN,
        val quietDb: Double = Double.NaN,
        val duringDb: Double = Double.NaN,
        val file: String? = null,
    ) {
        /** How loud the test signal came back in the microphone, relative to what was played. */
        val echoDb: Double get() = duringDb - playedDb

        fun line(): String = if (!ok) {
            "Call echo test: path=$path failed: $detail"
        } else {
            "Call echo test: path=$path played=${fmt(playedDb)}dBFS micQuiet=${fmt(quietDb)}dBFS " +
                "micDuring=${fmt(duringDb)}dBFS echo=${fmt(echoDb)}dB aboveQuiet=${fmt(duringDb - quietDb)}dB" +
                (file?.let { " file=$it" } ?: "") + " $detail"
        }
    }

    /** Runs both paths one after the other. Blocks for about 20 s; call off the main thread. */
    fun runBoth(context: Context, directory: File?, report: (String) -> Unit): List<Result> {
        saveDirectory = directory
        val results = mutableListOf<Result>()
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .build()
        val previousMode = manager.mode
        val granted = runCatching { manager.requestAudioFocus(focus) }.getOrDefault(-1)
        runCatching { manager.mode = AudioManager.MODE_IN_CALL }
        report("Call echo test: started focus=$granted mode=${manager.mode} carPath=${StockCallAudio.status()}")
        try {
            results += runCatching { runNormal() }.getOrElse { Result("normal", false, it.toString()) }
                .also { report(it.line()) }
            Thread.sleep(500)
            results += runCatching { runCar() }.getOrElse { Result("car", false, it.toString()) }
                .also { report(it.line()) }
        } finally {
            runCatching { manager.mode = previousMode }
            runCatching { manager.abandonAudioFocusRequest(focus) }
        }
        val normal = results.firstOrNull { it.path == "normal" && it.ok }
        val car = results.firstOrNull { it.path == "car" && it.ok }
        if (normal != null && car != null) {
            report(
                "Call echo test: car path echo ${fmt(car.echoDb)}dB vs normal ${fmt(normal.echoDb)}dB, " +
                    "${fmt(normal.echoDb - car.echoDb)}dB less echo",
            )
        }
        return results
    }

    private fun runNormal(): Result {
        val signal = testSignal()
        val trackAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .also { Mg4CarAudioSource.tag(it, Mg4CarAudioSource.CARPLAY_PHONE) }
            .build()
        val outFormat = AndroidAudioFormat.Builder()
            .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AndroidAudioFormat.CHANNEL_OUT_MONO)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(trackAttributes)
            .setAudioFormat(outFormat)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(RATE, AndroidAudioFormat.CHANNEL_OUT_MONO, AndroidAudioFormat.ENCODING_PCM_16BIT), 9_600))
            .build()
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, RATE, AndroidAudioFormat.CHANNEL_IN_MONO,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
            maxOf(AudioRecord.getMinBufferSize(RATE, AndroidAudioFormat.CHANNEL_IN_MONO, AndroidAudioFormat.ENCODING_PCM_16BIT) * 2, 19_200),
        )
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) return Result("normal", false, "microphone refused")
            recorder.startRecording()
            track.play()
            val writer = Thread({ writeAll(signal) { b, o, l -> track.write(b, o, l, AudioTrack.WRITE_BLOCKING) } }, "echo-test-out")
            writer.start()
            val recorded = readAll { b, o, l -> recorder.read(b, o, l, AudioRecord.READ_BLOCKING) }
            writer.join(2_000)
            return analyse("normal", signal, recorded, "out=VOICE_COMMUNICATION+53 mic=MIC")
        } finally {
            runCatching { track.stop() }
            track.release()
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    private fun runCar(): Result {
        val status = StockCallAudio.status()
        if (status != "ok") return Result("car", false, status)
        val signal = testSignal()
        val output = StockCallAudio.Output.openForTest().getOrElse { return Result("car", false, "output: ${it.message}") }
        val input = StockCallAudio.Input.openForTest(StockCallAudio.INPUT_RATE).getOrElse {
            output.close()
            return Result("car", false, "microphone: ${it.message}")
        }
        try {
            val downsampler = HalfRateDownsampler()
            val half = ByteArray(signal.size / 2 + 2)
            val writer = Thread({
                writeAll(signal) { b, o, l ->
                    val n = downsampler.process(b, o, l, half)
                    val written = output.write(half, 0, n)
                    if (written < 0) written else l
                }
            }, "echo-test-out")
            writer.start()
            val upsampler = DoubleRateUpsampler()
            val low = ByteArray(960)
            val recorded = readAll { b, o, l ->
                val n = input.read(low, 0, minOf(low.size, l / 2 / 2 * 2))
                if (n <= 0) n else {
                    val doubled = ByteArray(n * 2)
                    val bytes = upsampler.process(low, 0, n, doubled)
                    doubled.copyInto(b, o, 0, bytes)
                    bytes
                }
            }
            writer.join(2_000)
            if (input.starved) return Result("car", false, "car phone microphone gave no audio in ${StockCallAudio.Input.FIRST_DATA_TIMEOUT_MS} ms")
            return analyse("car", signal, recorded, "out=direct VOICE_CALL 24kHz io=${output.ioHandle} mic=MIC+0x200 24kHz")
        } finally {
            input.stop()
            input.close()
            output.close()
        }
    }

    private fun writeAll(signal: ByteArray, write: (ByteArray, Int, Int) -> Int) {
        val chunk = RATE / 50 * 2 // 20 ms
        var offset = 0
        while (offset < signal.size) {
            val n = write(signal, offset, minOf(chunk, signal.size - offset))
            if (n <= 0) {
                Log.w(TAG, "write returned $n at $offset")
                return
            }
            offset += n
        }
    }

    private fun readAll(read: (ByteArray, Int, Int) -> Int): ByteArray {
        val total = (LEAD_MS + SPEECH_MS + TAIL_MS + EXTRA_RECORD_MS) * RATE / 1000 * 2
        val out = ByteArray(total)
        var offset = 0
        while (offset < total) {
            val n = read(out, offset, minOf(1_920, total - offset))
            if (n <= 0) break
            offset += n
        }
        return out.copyOf(offset)
    }

    private fun analyse(path: String, signal: ByteArray, recorded: ByteArray, detail: String): Result {
        if (recorded.size < (LEAD_MS + SPEECH_MS) * RATE / 1000 * 2) {
            return Result(path, false, "recorded only ${recorded.size / (RATE / 500)} ms; $detail")
        }
        val file = save(path, recorded)
        // Quiet: before the signal starts. During: the speech part, allowing up to 500 ms of output latency.
        val quiet = rmsDb(recorded, 200, LEAD_MS - 100)
        val during = rmsDb(recorded, LEAD_MS + 500, LEAD_MS + SPEECH_MS)
        val played = rmsDb(signal, LEAD_MS, LEAD_MS + SPEECH_MS)
        return Result(path, true, detail, played, quiet, during, file)
    }

    private fun save(path: String, pcm: ByteArray): String? {
        val directory = saveDirectory ?: return null
        return runCatching {
            directory.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(directory, "echo-test-$stamp-$path.wav")
            WavWriter(file, RATE, 1, pcm.size).use { it.write(pcm) }
            file.name
        }.getOrNull()
    }

    private fun rmsDb(pcm: ByteArray, fromMs: Int, toMs: Int): Double {
        val from = (fromMs * RATE / 1000).coerceAtMost(pcm.size / 2)
        val to = (toMs * RATE / 1000).coerceAtMost(pcm.size / 2)
        if (to <= from) return Double.NaN
        var sum = 0.0
        for (i in from until to) {
            val s = ((pcm[i * 2].toInt() and 0xff) or (pcm[i * 2 + 1].toInt() shl 8)).toDouble()
            sum += s * s
        }
        val rms = sqrt(sum / (to - from))
        return if (rms <= 0.0) -120.0 else 20 * log10(rms / 32768.0)
    }

    /** 1 s silence, 6 s of syllable-like bursts of band-limited noise, 1 s silence. Deterministic. */
    internal fun testSignal(): ByteArray {
        val samples = (LEAD_MS + SPEECH_MS + TAIL_MS) * RATE / 1000
        val out = ByteArray(samples * 2)
        var seed = 0x2545F491L
        var low = 0.0
        var high = 0.0
        val start = LEAD_MS * RATE / 1000
        val end = (LEAD_MS + SPEECH_MS) * RATE / 1000
        for (i in start until end) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            val white = ((seed ushr 33).toInt() and 0xffff) / 32768.0 - 1.0
            low += 0.25 * (white - low) // low-pass, roughly 2 kHz
            high = 0.97 * high + low // gentle high-pass on what follows
            val band = low - high * 0.03
            val t = (i - start) * 1000 / RATE
            val inPhrase = t % 2_000 < 1_600 // 1.6 s phrases with 0.4 s pauses
            val syllable = (t % 250) < 180 // 4 syllables a second
            val ramp = minOf(t % 250, 180 - t % 250).coerceIn(0, 20) / 20.0
            val gain = if (inPhrase && syllable) ramp else 0.0
            val value = (band * gain * 9_000).toInt().coerceIn(-32_768, 32_767)
            out[i * 2] = value.toByte()
            out[i * 2 + 1] = (value shr 8).toByte()
        }
        return out
    }

    private fun fmt(value: Double) = if (value.isNaN()) "?" else String.format(Locale.US, "%.1f", value)
}
