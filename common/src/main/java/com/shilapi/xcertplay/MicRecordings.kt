package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.media.MicrophoneRecording
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Test aid (mg4.98): where microphone recordings live, and bundling them with the diagnostic report. */
internal object MicRecordings {
    fun directory(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "mic-recordings")

    /** Turns recording on or off from the saved setting. Off also deletes what was recorded. */
    fun sync(context: Context) {
        val directory = directory(context)
        if (AirPlayPersistence.loadMicrophoneRecording(context)) {
            MicrophoneRecording.directory = directory
        } else {
            MicrophoneRecording.directory = null
            runCatching { MicrophoneRecording.files(directory).forEach { it.delete() } }
        }
    }

    fun files(context: Context): List<File> = runCatching { MicrophoneRecording.files(directory(context)) }.getOrDefault(emptyList())

    /** A zip of the report and the recordings. WAV audio barely compresses, so it is stored quickly. */
    fun zip(reportName: String, report: ByteArray, recordings: List<File>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.setLevel(1)
            zip.putNextEntry(ZipEntry(reportName))
            zip.write(report)
            zip.closeEntry()
            recordings.forEach { file ->
                val audio = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
                zip.putNextEntry(ZipEntry(file.name))
                zip.write(audio)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
