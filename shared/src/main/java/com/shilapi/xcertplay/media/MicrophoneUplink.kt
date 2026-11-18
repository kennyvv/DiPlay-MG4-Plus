package com.shilapi.xcertplay.media

import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import com.shilapi.xcertplay.airplay.toHexString
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures one PCM microphone stream and sends it back to the phone as sealed CarPlay RTP.
 *
 * The recorder runs only while the matching audio stream is active, so callers start this after
 * the first downlink audio packet and close it on stream teardown.
 */
/**
 * Test override for which car microphone path calls and Siri use (an Android AudioSource), or null
 * for the default per stream (plain microphone for calls, voice recognition for Siri, the same as the
 * car's own CarPlay audio library, which opens AudioSource 1 for telephony and 6 for Siri and never
 * VOICE_COMMUNICATION). Set by the host from settings before CarPlay starts.
 */
object MicrophoneSource {
    @Volatile var override: Int? = null
}

internal class MicrophoneUplink(
    private val config: MicrophoneConfig,
    private val report: (String) -> Unit = {},
) : Closeable {
    // Diagnostics, every 5 s: whether capture and encoding keep up with real time.
    private var statsStartNs = 0L
    private var framesSent = 0
    private var encodeTotalNs = 0L
    private var encodeMaxNs = 0L
    private var readMaxNs = 0L
    private var bytesSent = 0L
    // Input level, to tell a quiet or clipping microphone from a network problem (Siri, 2026-11-03).
    private val level = MicrophoneLevel()
    // Test recording of what is sent (mg4.98); only touched on the capture thread.
    private var recording: WavWriter? = null
    private val running = AtomicBoolean(false)
    private val firstPacketLogged = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    /** Calls on the head unit's own phone microphone path (test switch, MG4 only). */
    @Volatile private var stockInput: StockCallAudio.Input? = null
    @Volatile private var echoCanceler: AcousticEchoCanceler? = null
    // Software echo suppression for calls, used when the head unit has no echo canceller.
    private var echoSuppressor: EchoSuppressor? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var opusEncoder: OpusEncoder? = null
    private var thread: Thread? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true

        val channelMask = if (config.channels >= 2) {
            AndroidAudioFormat.CHANNEL_IN_STEREO
        } else {
            AndroidAudioFormat.CHANNEL_IN_MONO
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            channelMask,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "microphone unavailable rate=${config.sampleRate} channels=${config.channels}")
            running.set(false)
            return false
        }

        val source = MicrophoneSource.override ?: when (config.audioType) {
            "telephony" -> MediaRecorder.AudioSource.MIC // as stock (libCarPlayPlatformAudio.so)
            "speechrecognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            else -> MediaRecorder.AudioSource.MIC
        }
        val nextEncoder = if (config.codec == AudioCodecKind.OPUS) {
            OpusEncoder(config.bitrate ?: 48_000).takeIf { it.available }
        } else {
            null
        }
        if (config.codec == AudioCodecKind.OPUS && nextEncoder == null) {
            Log.w(TAG, "microphone Opus encoder is unavailable")
            running.set(false)
            return false
        }
        val bufferSize = maxOf(minBuffer * 2, config.frameBytes * 4)
        val carRateOk = config.sampleRate == StockCallAudio.INPUT_RATE || config.sampleRate == 2 * StockCallAudio.INPUT_RATE
        val callCarRate = StockCallAudio.carRate(config.sampleRate)
        if (config.audioType == "telephony" && config.channels == 1 && StockCallAudio.enabled && callCarRate != null) {
            val opened = StockCallAudio.Input.open(callCarRate)
            val input = opened.getOrNull()
            if (input != null) {
                carInputRate = callCarRate
                sourceUsed = "car phone input (MIC, flag 0x200, $callCarRate Hz)" +
                    (nextEncoder?.let { " opus=${it.kind}" } ?: "") + " swEcho=off"
                return startCapture(carPhoneReader(input, channelMask, bufferSize, MediaRecorder.AudioSource.MIC), nextEncoder, stockInputHolder = input)
            }
            runCatching { report("Microphone telephony: car phone input failed (${opened.exceptionOrNull()?.message}); using the normal microphone") }
        }
        if (config.audioType == "speechrecognition" && config.channels == 1 && StockCallAudio.siriEnabled && carRateOk &&
            MicrophoneSource.override == null
        ) {
            val opened = StockCallAudio.Input.openSiri(StockCallAudio.INPUT_RATE)
            val input = opened.getOrNull()
            if (input != null) {
                sourceUsed = "car Siri input (VOICE_RECOGNITION, flag 0x100, ${StockCallAudio.INPUT_RATE} Hz)" +
                    (nextEncoder?.let { " opus=${it.kind}" } ?: "")
                return startCapture(
                    carPhoneReader(input, channelMask, bufferSize, MediaRecorder.AudioSource.VOICE_RECOGNITION),
                    nextEncoder,
                    stockInputHolder = input,
                )
            }
            runCatching { report("Microphone speechrecognition: car Siri input failed (${opened.exceptionOrNull()?.message}); using the normal microphone") }
        }
        // Head units can refuse the call-processing source outside their own hands-free call
        // (seen as silent calls with Wi-Fi audio on an MG4); fall back to the plain microphone.
        val nextRecorder = createRecorder(source, channelMask, bufferSize)?.also { sourceUsed = sourceName(source) }
            ?: if (source != MediaRecorder.AudioSource.MIC) {
                createRecorder(MediaRecorder.AudioSource.MIC, channelMask, bufferSize)
                    ?.also { sourceUsed = "${sourceName(source)} refused, MIC" }
            } else null
        if (nextEncoder != null) sourceUsed = "$sourceUsed opus=${nextEncoder.kind}"
        if (nextRecorder != null && config.audioType == "telephony") {
            sourceUsed = "$sourceUsed aec=${attachEchoCanceler(nextRecorder)}"
            sourceUsed = "$sourceUsed swEcho=${if (CallEchoSuppression.enabled && config.channels == 1) "on" else "off"}"
        }
        if (nextRecorder == null) {
            sourceUsed = "none (${sourceName(source)} and MIC refused)"
            nextEncoder?.close()
            running.set(false)
            return false
        }

        return startCapture(
            { data, offset, length -> nextRecorder.read(data, offset, length, AudioRecord.READ_BLOCKING) },
            nextEncoder,
            recorderHolder = nextRecorder,
        )
    }

    private fun startCapture(
        read: (ByteArray, Int, Int) -> Int,
        nextEncoder: OpusEncoder?,
        recorderHolder: AudioRecord? = null,
        stockInputHolder: StockCallAudio.Input? = null,
    ): Boolean {
        val nextSocket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("::"), 0))
            }
        } catch (error: Exception) {
            Log.e(TAG, "microphone socket creation failed", error)
            recorderHolder?.release()
            stockInputHolder?.close()
            nextEncoder?.close()
            running.set(false)
            return false
        }

        recorder = recorderHolder
        stockInput = stockInputHolder
        socket = nextSocket
        opusEncoder = nextEncoder
        return try {
            recorderHolder?.startRecording()
            thread = Thread({ capture(read, nextSocket) }, "carplay-mic").apply {
                isDaemon = true
                start()
            }
            Log.i(
                TAG,
                "microphone uplink started type=${config.audioType} " +
                    "rate=${config.sampleRate} channels=${config.channels} " +
                    "frameMs=${config.frameMillis} port=${config.port} " +
                    "rtpClock=${config.rtpClockRate} tsStep=${config.rtpTimestampStep}",
            )
            true
        } catch (error: Exception) {
            Log.e(TAG, "microphone recording failed", error)
            release()
            false
        }
    }

    /**
     * Calls play through the car speakers, so without echo cancellation the caller hears
     * themselves (car test 2026-11-11: the stock CarPlay had no echo, this app did). The
     * VOICE_COMMUNICATION source does not turn it on for this head unit, so ask for it.
     * Returns a short word for the log: on, off, unavailable or failed.
     */
    private fun attachEchoCanceler(audioRecord: AudioRecord): String {
        if (!AcousticEchoCanceler.isAvailable()) return "unavailable"
        return try {
            val effect = AcousticEchoCanceler.create(audioRecord.audioSessionId) ?: return "failed"
            effect.enabled = true
            echoCanceler = effect
            if (effect.enabled) "on" else "off"
        } catch (error: Exception) {
            Log.w(TAG, "echo canceller not attached", error)
            "failed"
        }
    }

    /**
     * Reads the car's phone microphone (24 kHz) and doubles it to the call's 48 kHz. If the HAL never
     * starts that capture, switches to the plain microphone so the caller still hears the driver.
     */
    /** The rate the car's microphone was opened at for this stream (24 kHz for Siri and 48 kHz calls). */
    private var carInputRate = StockCallAudio.INPUT_RATE

    private fun carPhoneReader(
        input: StockCallAudio.Input,
        channelMask: Int,
        bufferSize: Int,
        fallbackSource: Int,
    ): (ByteArray, Int, Int) -> Int {
        val upsampler = if (config.sampleRate == carInputRate) null else DoubleRateUpsampler()
        var low = ByteArray(0)
        var fallback: AudioRecord? = null
        return read@{ data, offset, length ->
            fallback?.let { return@read it.read(data, offset, length, AudioRecord.READ_BLOCKING) }
            val want = if (upsampler == null) length else (length / 4) * 2
            if (low.size < want) low = ByteArray(want)
            val n = input.read(low, 0, want)
            if (n < 0 && input.starved && running.get()) {
                val next = createRecorder(fallbackSource, channelMask, bufferSize)
                runCatching {
                    report("Microphone ${config.audioType}: car input gave no audio; switched to the normal microphone (${if (next != null) sourceName(fallbackSource) else "none"})")
                }
                if (next == null) return@read -1
                next.startRecording()
                recorder = next
                fallback = next
                sourceUsed = "$sourceUsed, fell back to ${sourceName(fallbackSource)}"
                return@read next.read(data, offset, length, AudioRecord.READ_BLOCKING)
            }
            if (n <= 0) return@read n
            if (upsampler == null) {
                low.copyInto(data, offset, 0, n)
                n
            } else {
                val doubled = ByteArray(n * 2)
                val bytes = upsampler.process(low, 0, n, doubled)
                doubled.copyInto(data, offset, 0, bytes)
                bytes
            }
        }
    }

    /** Which recording source opened, for the diagnostic log. */
    @Volatile var sourceUsed: String = "not started"
        private set

    private fun createRecorder(source: Int, channelMask: Int, bufferSize: Int): AudioRecord? {
        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AndroidAudioFormat.Builder()
                        .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(config.sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (error: Exception) {
            Log.e(TAG, "microphone recorder creation failed source=${sourceName(source)}", error)
            return null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "microphone recorder failed to initialize source=${sourceName(source)}")
            recorder.release()
            return null
        }
        return recorder
    }

    private fun sourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.MIC -> "MIC"
        else -> source.toString()
    }

    private fun capture(read: (ByteArray, Int, Int) -> Int, socket: DatagramSocket) {
        val frame = ByteArray(config.frameBytes)
        val readBuffer = ByteArray(maxOf(frame.size, MIN_READ_BYTES))
        val counters = MicrophoneCounters()
        var filled = 0
        recording = MicrophoneRecording.open(config.audioType, config.sampleRate, config.channels)
        if (config.audioType == "telephony" && config.channels == 1 && CallEchoSuppression.enabled && stockInput == null) {
            echoSuppressor = EchoSuppressor(EchoReference.shared, config.sampleRate)
        }
        try {
            while (running.get()) {
                val readStarted = System.nanoTime()
                val count = read(readBuffer, 0, readBuffer.size)
                readMaxNs = maxOf(readMaxNs, System.nanoTime() - readStarted)
                if (count < 0) {
                    if (running.get()) Log.e(TAG, "microphone read failed code=$count")
                    return
                }
                if (count == 0) {
                    continue
                }
                var offset = 0
                while (offset < count && running.get()) {
                    val copied = minOf(frame.size - filled, count - offset)
                    readBuffer.copyInto(frame, filled, offset, offset + copied)
                    filled += copied
                    offset += copied
                    if (filled == frame.size) {
                        sendFrame(socket, counters, frame)
                        filled = 0
                    }
                }
            }
        } catch (error: Exception) {
            if (running.get()) Log.e(TAG, "microphone capture failed", error)
        } finally {
            reportStats(force = true)
            recording?.let { saved ->
                recording = null
                saved.close()
                runCatching {
                    report("Microphone ${config.audioType} recording saved file=${saved.file.name} bytes=${saved.dataBytes}")
                }
            }
            running.set(false)
            release()
        }
    }

    private fun reportStats(force: Boolean = false) {
        val now = System.nanoTime()
        if (statsStartNs == 0L) statsStartNs = now
        val elapsedNs = now - statsStartNs
        if (!force && elapsedNs < STATS_WINDOW_NS) return
        if (framesSent > 0 || force) {
            val frameMs = config.frameBytes * 1000L / (config.sampleRate * config.channels * 2).coerceAtLeast(1)
            runCatching {
                report(
                    "Microphone ${config.audioType} stats codec=${config.codec} frames=$framesSent " +
                        "audioMs=${framesSent * frameMs} wallMs=${elapsedNs / 1_000_000L} " +
                        "encodeAvgUs=${if (framesSent > 0) encodeTotalNs / framesSent / 1000 else 0} " +
                        "encodeMaxUs=${encodeMaxNs / 1000} readMaxMs=${readMaxNs / 1_000_000L} " +
                        "kbps=${if (elapsedNs > 0) bytesSent * 8_000_000L / elapsedNs else 0} " +
                        level.summary() +
                        (echoSuppressor?.let { " " + it.summary() } ?: "") +
                        if (force) " ended=true" else "",
                )
            }
        }
        statsStartNs = now
        framesSent = 0
        encodeTotalNs = 0L
        encodeMaxNs = 0L
        readMaxNs = 0L
        bytesSent = 0L
        level.reset()
    }

    private fun sendFrame(socket: DatagramSocket, counters: MicrophoneCounters, frame: ByteArray) {
        echoSuppressor?.let { suppressor ->
            suppressor.process(frame)
            suppressor.lockChange()?.let { line -> runCatching { report("Microphone ${config.audioType}: $line") } }
        }
        level.add(frame)
        recording?.write(frame)
        val encodeStarted = System.nanoTime()
        val bodies = if (config.codec == AudioCodecKind.OPUS) {
            opusEncoder?.encode(frame).orEmpty()
        } else {
            listOf(MicrophonePacketizer.toWirePcm(frame))
        }
        val encodeNs = System.nanoTime() - encodeStarted
        encodeTotalNs += encodeNs
        encodeMaxNs = maxOf(encodeMaxNs, encodeNs)
        framesSent++
        bodies.forEach { bytesSent += it.size }
        reportStats()
        bodies.forEach { body ->
            sendPacket(
                socket = socket,
                counters = counters,
                body = body,
                samples = config.rtpTimestampStep,
            )
        }
    }

    private fun sendPacket(
        socket: DatagramSocket,
        counters: MicrophoneCounters,
        body: ByteArray,
        samples: Int,
    ) {
        val packet = MicrophonePacketizer.sealPacket(
            key = config.key,
            payloadType = config.payloadType,
            counters = counters,
            body = body,
            samples = samples,
        )
        try {
            socket.send(DatagramPacket(packet, packet.size, config.host, config.port))
            if (firstPacketLogged.compareAndSet(false, true)) {
                Log.i(
                    TAG,
                    "microphone first packet bytes=${packet.size} body=${body.size} " +
                        "head=${packet.copyOf(minOf(packet.size, 16)).toHexString()} " +
                        "port=${config.port}",
                )
            }
        } catch (error: Exception) {
            if (running.get()) throw error
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            release()
            return
        }
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // Best effort; release below is authoritative.
        }
        stockInput?.stop()
        try {
            socket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        thread?.let { worker ->
            try {
                worker.join(CLOSE_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) worker.interrupt()
        }
        release()
    }

    @Synchronized
    private fun release() {
        running.set(false)
        val currentRecorder = recorder
        recorder = null
        try {
            currentRecorder?.release()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentStockInput = stockInput
        stockInput = null
        currentStockInput?.close()
        val currentEcho = echoCanceler
        echoCanceler = null
        runCatching { currentEcho?.release() }
        val currentSocket = socket
        socket = null
        try {
            currentSocket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentEncoder = opusEncoder
        opusEncoder = null
        currentEncoder?.close()
    }

    private companion object {
        private const val TAG = "xcertplay-usb"
        private const val MIN_READ_BYTES = 2_048
        private const val CLOSE_JOIN_MILLIS = 500L
        private const val STATS_WINDOW_NS = 5_000_000_000L
    }
}

/** Peak and RMS of 16-bit little-endian PCM frames, in dBFS (0 is full scale). */
internal class MicrophoneLevel {
    private var peak = 0
    private var sumSquares = 0.0
    private var samples = 0L
    private var clipped = 0L
    // RMS of the quietest frame: the noise floor Siri has to hear the end of speech against.
    private var floor = Double.MAX_VALUE

    fun add(frame: ByteArray) {
        var index = 0
        var frameSquares = 0.0
        var frameSamples = 0
        while (index + 1 < frame.size) {
            val sample = (frame[index].toInt() and 0xff) or (frame[index + 1].toInt() shl 8)
            val magnitude = kotlin.math.abs(sample)
            if (magnitude > peak) peak = magnitude
            if (magnitude >= 32_767) clipped++
            frameSquares += sample.toDouble() * sample
            frameSamples++
            index += 2
        }
        if (frameSamples == 0) return
        sumSquares += frameSquares
        samples += frameSamples
        floor = minOf(floor, kotlin.math.sqrt(frameSquares / frameSamples))
    }

    fun summary(): String {
        if (samples == 0L) return "level=none"
        val rms = kotlin.math.sqrt(sumSquares / samples)
        return "peakDb=${dbfs(peak.toDouble())} rmsDb=${dbfs(rms)} clipped=$clipped floorDb=${dbfs(floor)}"
    }

    fun reset() {
        peak = 0
        sumSquares = 0.0
        samples = 0
        clipped = 0
        floor = Double.MAX_VALUE
    }

    private fun dbfs(value: Double): String =
        if (value <= 0.0) "-inf" else String.format(java.util.Locale.US, "%.1f", 20 * kotlin.math.log10(value / 32_768.0))
}
