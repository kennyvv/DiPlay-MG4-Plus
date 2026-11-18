package com.shilapi.xcertplay.media

import android.os.Build
import android.util.Log

/**
 * The MG4 head unit's own CarPlay call path (test), so calls get the audio HAL's echo canceller.
 *
 * The HAL cancels echo (Nuance ECNR) only for call audio on its "Phone Direct Out" output while the
 * "Carplay Phone input" microphone is open. Java cannot reach either on Android 9, so
 * `libmg4_call_audio.so` opens them the way the stock AllGo library does: device 0x400 connected as
 * `carplay_source=phone`, a native AudioTrack on the voice call stream with the direct output flag,
 * and a native AudioRecord on the plain microphone with the vendor input flag 0x200.
 * See docs/upstream-review-2026-11-14.md, issue 1.
 */
object StockCallAudio {
    /** Set by the host from settings before CarPlay starts. MG4 only. */
    @Volatile var enabled: Boolean = false

    /** Test switch: Siri records through the car's Siri microphone path (Nuance processing). MG4 only. */
    @Volatile var siriEnabled: Boolean = false

    /** android.media.MediaRecorder.AudioSource and the car's vendor input flags. */
    const val SOURCE_MIC = 1
    const val SOURCE_VOICE_RECOGNITION = 6
    const val FLAG_CARPLAY_SIRI = 0x100
    const val FLAG_CARPLAY_PHONE = 0x200

    /** Package name for the microphone's app-ops check. Set by the host. */
    @Volatile var packageName: String = ""

    /** The direct output takes 8, 16 or 24 kHz mono; 24 kHz is the stock library's best rate. */
    const val OUTPUT_RATE = 24_000

    /** The "carplay_phone Mic" takes 8, 16 or 24 kHz; at 48 kHz the HAL never starts its capture. */
    const val INPUT_RATE = 24_000

    private const val TAG = "DiPlay-StockCall"

    private val loaded: Boolean by lazy {
        Build.VERSION.SDK_INT == Build.VERSION_CODES.P && runCatching {
            System.loadLibrary("mg4_call_audio")
        }.onFailure { Log.w(TAG, "native library not loaded", it) }.isSuccess
    }

    /** "ok", or why the native path cannot be used. */
    fun status(): String = when {
        Build.VERSION.SDK_INT != Build.VERSION_CODES.P -> "not Android 9"
        !loaded -> "native library not loaded"
        else -> runCatching { nativeStatus() }.getOrElse { "failed: ${it.javaClass.simpleName}" }
    }

    /** Whether calls should try this path now. */
    val active: Boolean get() = enabled && status() == "ok"

    /** The call downlink, played on the head unit's direct phone output. */
    class Output private constructor(private var handle: Long, val ioHandle: Int) {
        /**
         * Write of 16-bit mono PCM at [OUTPUT_RATE]. Returns bytes written (non-blocking: what fitted, 0 when full)
         * or a negative status.
         */
        fun write(data: ByteArray, offset: Int, length: Int, blocking: Boolean = true): Int {
            val h = handle
            return if (h > 0) nativeWrite(h, data, offset, length, blocking) else -1
        }

        @Synchronized
        fun close() {
            val h = handle
            handle = 0
            if (h > 0) runCatching { nativeCloseOutput(h) }
            runCatching { nativeSetPhoneDevice(false) }
        }

        companion object {
            /** Opens the output, or returns the failure for the log. */
            fun open(): Result<Output> {
                if (!active) return Result.failure(IllegalStateException(status()))
                return openForTest()
            }

            /** Opens whether or not the switch is on (call echo self-test). */
            fun openForTest(): Result<Output> {
                if (status() != "ok") return Result.failure(IllegalStateException(status()))
                val device = nativeSetPhoneDevice(true)
                val handle = nativeOpenOutput(OUTPUT_RATE)
                if (handle <= 0) {
                    runCatching { nativeSetPhoneDevice(false) }
                    return Result.failure(IllegalStateException("AudioTrack status=$handle device=$device"))
                }
                return Result.success(Output(handle, nativeOutputHandle(handle)))
            }
        }
    }

    /** The call microphone, opened with the vendor flag that selects the "Carplay Phone input". */
    class Input private constructor(private var handle: Long) {
        @Volatile private var gotData = false

        /** True when the HAL delivered nothing within [FIRST_DATA_TIMEOUT_MS] and capture was stopped. */
        @Volatile var starved = false
            private set

        init {
            // A blocking read never returns if the HAL does not start this capture; don't hang a call on it.
            Thread({
                try {
                    Thread.sleep(FIRST_DATA_TIMEOUT_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (!gotData && handle > 0) {
                    starved = true
                    Log.w(TAG, "car phone microphone gave no audio in ${FIRST_DATA_TIMEOUT_MS} ms; stopping it")
                    stop()
                }
            }, "car-mic-watchdog").apply { isDaemon = true }.start()
        }

        /** Blocking read of 16-bit mono PCM. Returns bytes read or a negative status. */
        fun read(data: ByteArray, offset: Int, length: Int): Int {
            val h = handle
            val n = if (h > 0 && !starved) nativeRead(h, data, offset, length) else -1
            if (n > 0) gotData = true
            return if (starved) -1 else n
        }

        /** Stops capture so a blocked [read] returns; call [close] after the reading thread ends. */
        fun stop() {
            val h = handle
            if (h > 0) runCatching { nativeStopInput(h) }
        }

        @Synchronized
        fun close() {
            val h = handle
            handle = 0
            if (h > 0) runCatching { nativeCloseInput(h) }
        }

        companion object {
            const val FIRST_DATA_TIMEOUT_MS = 1_500L

            fun open(rate: Int): Result<Input> {
                if (!active) return Result.failure(IllegalStateException(status()))
                return openForTest(rate)
            }

            /** The Siri microphone: voice recognition with the car's Siri flag. */
            fun openSiri(rate: Int): Result<Input> {
                if (!siriEnabled || status() != "ok") return Result.failure(IllegalStateException(if (siriEnabled) status() else "off"))
                return openSource(rate, SOURCE_VOICE_RECOGNITION, FLAG_CARPLAY_SIRI)
            }

            /** Opens whether or not the switch is on (call echo self-test). */
            fun openForTest(rate: Int): Result<Input> = openSource(rate, SOURCE_MIC, FLAG_CARPLAY_PHONE)

            private fun openSource(rate: Int, source: Int, flags: Int): Result<Input> {
                if (status() != "ok") return Result.failure(IllegalStateException(status()))
                val handle = nativeOpenInput(rate, packageName, source, flags)
                if (handle <= 0) return Result.failure(IllegalStateException("AudioRecord status=$handle"))
                return Result.success(Input(handle))
            }
        }
    }

    @JvmStatic private external fun nativeStatus(): String
    @JvmStatic private external fun nativeSetPhoneDevice(on: Boolean): Int
    @JvmStatic private external fun nativeOpenOutput(rate: Int): Long
    @JvmStatic private external fun nativeOutputHandle(handle: Long): Int
    @JvmStatic private external fun nativeWrite(handle: Long, data: ByteArray, offset: Int, length: Int, blocking: Boolean): Int
    @JvmStatic private external fun nativeCloseOutput(handle: Long)
    @JvmStatic private external fun nativeOpenInput(rate: Int, packageName: String, source: Int, flags: Int): Long
    @JvmStatic private external fun nativeRead(handle: Long, data: ByteArray, offset: Int, length: Int): Int
    @JvmStatic private external fun nativeStopInput(handle: Long)
    @JvmStatic private external fun nativeCloseInput(handle: Long)
}

/** Doubles 16-bit mono PCM from 24 kHz to 48 kHz (zero-stuffing and the same low-pass), keeping state. */
internal class DoubleRateUpsampler {
    private val history = IntArray(TAPS.size - 1)

    /** Writes length*2 bytes into [out] and returns the byte count. */
    fun process(data: ByteArray, offset: Int, length: Int, out: ByteArray): Int {
        val samples = length / 2
        var written = 0
        // Zero-stuffed input: x[2k] = in[k], x[2k+1] = 0. Even taps act on odd outputs and vice versa.
        for (k in 0 until samples) {
            for (phase in 0..1) {
                var acc = 0L
                var t = phase
                while (t < TAPS.size) {
                    val index = k - (t - phase) / 2 // input sample index for this tap
                    val sample = if (index >= 0) sampleAt(data, offset, index) else history[history.size + index]
                    acc += sample.toLong() * TAPS[t] * 2
                    t += 2
                }
                val value = (acc shr SHIFT).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out[written] = value.toByte()
                out[written + 1] = (value shr 8).toByte()
                written += 2
            }
        }
        for (h in history.indices) {
            val index = samples - history.size + h
            history[h] = if (index >= 0) sampleAt(data, offset, index) else history[history.size + index]
        }
        return written
    }

    private fun sampleAt(data: ByteArray, offset: Int, index: Int): Int {
        val p = offset + index * 2
        return (data[p].toInt() and 0xff) or (data[p + 1].toInt() shl 8)
    }

    private companion object {
        val TAPS = intArrayOf(-62, 0, 297, 0, -1012, 0, 4873, 8192, 4873, 0, -1012, 0, 297, 0, -62)
        const val SHIFT = 14
    }
}

/** Halves 16-bit mono PCM from 48 kHz to 24 kHz with a short low-pass, keeping state across calls. */
internal class HalfRateDownsampler {
    private val history = IntArray(TAPS.size - 1)

    /** Writes length/4 samples' bytes into [out] and returns the byte count. */
    fun process(data: ByteArray, offset: Int, length: Int, out: ByteArray): Int {
        val samples = length / 2
        var written = 0
        var i = 0
        while (i + 1 < samples) {
            // Filter at the even input sample, using the last TAPS.size inputs.
            var acc = 0L
            for (t in TAPS.indices) {
                val index = i - t
                val sample = if (index >= 0) sampleAt(data, offset, index) else history[history.size + index]
                acc += sample.toLong() * TAPS[t]
            }
            val value = (acc shr SHIFT).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[written] = value.toByte()
            out[written + 1] = (value shr 8).toByte()
            written += 2
            i += 2
        }
        // Keep the last inputs for the next block. Ascending h only reads history slots not yet overwritten.
        for (h in history.indices) {
            val index = samples - history.size + h
            history[h] = if (index >= 0) sampleAt(data, offset, index) else history[history.size + index]
        }
        return written
    }

    private fun sampleAt(data: ByteArray, offset: Int, index: Int): Int {
        val p = offset + index * 2
        return (data[p].toInt() and 0xff) or (data[p + 1].toInt() shl 8)
    }

    private companion object {
        // 15-tap half-band low-pass (cut-off about 12 kHz at 48 kHz), Q14; centre 8192, other taps also sum to 8192.
        val TAPS = intArrayOf(-62, 0, 297, 0, -1012, 0, 4873, 8192, 4873, 0, -1012, 0, 297, 0, -62)
        const val SHIFT = 14
    }
}
