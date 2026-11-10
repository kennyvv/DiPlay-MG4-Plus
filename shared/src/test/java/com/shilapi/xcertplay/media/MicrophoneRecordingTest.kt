package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class MicrophoneRecordingTest {
    @Test
    fun headerDescribesMono16BitPcm() {
        val header = MicrophoneRecording.wavHeader(sampleRate = 48_000, channels = 1, dataBytes = 1_000)
        assertEquals(44, header.size)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
        val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1_036, fields.getInt(4))
        assertEquals(1, fields.getShort(20).toInt())
        assertEquals(1, fields.getShort(22).toInt())
        assertEquals(48_000, fields.getInt(24))
        assertEquals(96_000, fields.getInt(28))
        assertEquals(2, fields.getShort(32).toInt())
        assertEquals(16, fields.getShort(34).toInt())
        assertEquals(1_000, fields.getInt(40))
    }

    @Test
    fun writerStopsAtTheLimitAndPatchesTheHeader() {
        val file = File.createTempFile("mic", ".wav")
        try {
            val writer = WavWriter(file, sampleRate = 16_000, channels = 1, maxDataBytes = 6)
            writer.write(byteArrayOf(1, 2, 3, 4))
            writer.write(byteArrayOf(5, 6, 7, 8))
            writer.write(byteArrayOf(9))
            writer.close()
            writer.close()
            assertEquals(6, writer.dataBytes)
            val bytes = file.readBytes()
            assertEquals(50, bytes.size)
            assertEquals(6, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), bytes.copyOfRange(44, 50))
        } finally {
            file.delete()
        }
    }

    @Test
    fun offWithoutADirectoryAndKeepsOnlyTheNewestFiles() {
        val before = MicrophoneRecording.directory
        val directory = Files.createTempDirectory("mic-recordings").toFile()
        try {
            MicrophoneRecording.directory = null
            assertNull(MicrophoneRecording.open("speechrecognition", 48_000, 1))

            repeat(MicrophoneRecording.MAX_FILES + 2) { File(directory, "mic-2026010$it-000000-old.wav").writeBytes(ByteArray(1)) }
            MicrophoneRecording.directory = directory
            val writer = MicrophoneRecording.open("speechrecognition", 48_000, 1)!!
            writer.close()
            val names = MicrophoneRecording.files(directory).map { it.name }
            assertEquals(MicrophoneRecording.MAX_FILES, names.size)
            assertTrue(names.last().endsWith("-speechrecognition.wav"))
            assertTrue("mic-20260100-000000-old.wav" !in names)
        } finally {
            MicrophoneRecording.directory = before
            directory.deleteRecursively()
        }
    }
}
