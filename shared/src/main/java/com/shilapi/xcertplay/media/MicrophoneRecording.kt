package com.shilapi.xcertplay.media

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Test aid (mg4.98): saves the first seconds of each microphone stream as a WAV, taken before
 * encoding, so what Siri and callers are sent can be listened to. Off unless the host sets
 * [directory]. Files stay on the head unit until the user downloads them.
 */
object MicrophoneRecording {
    /** Where recordings go, or null when recording is off. Read each time a microphone stream starts. */
    @Volatile var directory: File? = null

    const val MAX_SECONDS = 10
    const val MAX_FILES = 8
    internal const val HEADER_BYTES = 44

    /** Recordings in [directory], oldest first. */
    fun files(directory: File): List<File> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(".wav") }.orEmpty().sortedBy { it.name }

    /** Opens the next recording, deleting the oldest so at most [MAX_FILES] remain; null when off or on failure. */
    internal fun open(audioType: String, sampleRate: Int, channels: Int): WavWriter? {
        val target = directory ?: return null
        return runCatching {
            target.mkdirs()
            files(target).dropLast(MAX_FILES - 1).forEach { it.delete() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            WavWriter(
                File(target, "mic-$stamp-$audioType.wav"),
                sampleRate,
                channels,
                maxDataBytes = sampleRate * channels * 2 * MAX_SECONDS,
            )
        }.getOrNull()
    }

    /** A 44-byte header for 16-bit little-endian PCM. */
    internal fun wavHeader(sampleRate: Int, channels: Int, dataBytes: Int): ByteArray {
        val header = ByteArray(HEADER_BYTES)
        fun text(offset: Int, value: String) = value.forEachIndexed { i, c -> header[offset + i] = c.code.toByte() }
        fun int32(offset: Int, value: Int) {
            for (i in 0..3) header[offset + i] = (value shr (8 * i)).toByte()
        }
        fun int16(offset: Int, value: Int) {
            for (i in 0..1) header[offset + i] = (value shr (8 * i)).toByte()
        }
        text(0, "RIFF")
        int32(4, 36 + dataBytes)
        text(8, "WAVE")
        text(12, "fmt ")
        int32(16, 16)
        int16(20, 1)
        int16(22, channels)
        int32(24, sampleRate)
        int32(28, sampleRate * channels * 2)
        int16(32, channels * 2)
        int16(34, 16)
        text(36, "data")
        int32(40, dataBytes)
        return header
    }
}

/** Writes PCM frames to a WAV file up to [maxDataBytes], then ignores the rest. Not thread-safe. */
internal class WavWriter(
    val file: File,
    private val sampleRate: Int,
    private val channels: Int,
    private val maxDataBytes: Int,
) : Closeable {
    private var output: RandomAccessFile? = RandomAccessFile(file, "rw").apply {
        setLength(0)
        write(MicrophoneRecording.wavHeader(sampleRate, channels, 0))
    }
    var dataBytes = 0
        private set

    fun write(frame: ByteArray) {
        val out = output ?: return
        val count = minOf(frame.size, maxDataBytes - dataBytes)
        if (count <= 0) return
        try {
            out.write(frame, 0, count)
            dataBytes += count
        } catch (_: Exception) {
            close()
        }
    }

    override fun close() {
        val out = output ?: return
        output = null
        try {
            out.seek(0)
            out.write(MicrophoneRecording.wavHeader(sampleRate, channels, dataBytes))
        } catch (_: Exception) {
            // Best effort: a short header still leaves the samples in the file.
        } finally {
            runCatching { out.close() }
        }
    }
}
