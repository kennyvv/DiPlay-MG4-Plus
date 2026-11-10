package com.shilapi.xcertplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

class MicRecordingsTest {
    @Test
    fun zipHoldsTheReportThenEachRecording() {
        val wav = File.createTempFile("mic-test", ".wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val missing = File(wav.parentFile, "mic-missing-${System.nanoTime()}.wav")
        try {
            val zipped = MicRecordings.zip("report.txt", "hello".toByteArray(), listOf(wav, missing))
            val entries = LinkedHashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(zipped)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes()
                }
            }
            assertEquals(listOf("report.txt", wav.name), entries.keys.toList())
            assertArrayEquals("hello".toByteArray(), entries["report.txt"])
            assertArrayEquals(byteArrayOf(1, 2, 3), entries[wav.name])
        } finally {
            wav.delete()
        }
    }
}
