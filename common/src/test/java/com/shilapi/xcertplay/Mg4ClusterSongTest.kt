package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Mg4ClusterSongTest {
    @Test
    fun timeShowsPositionAndLength() {
        assertEquals("1:23 / 3:45", Mg4ClusterSong.timeText(83_400, 225_000))
        assertEquals("0:00 / 1:02:03", Mg4ClusterSong.timeText(-5, 3_723_000))
        // Never past the end.
        assertEquals("3:45 / 3:45", Mg4ClusterSong.timeText(999_999, 225_000))
    }

    @Test
    fun nothingWithoutLengthOrPosition() {
        assertNull(Mg4ClusterSong.timeText(null, 225_000))
        assertNull(Mg4ClusterSong.timeText(1_000, null))
        assertNull(Mg4ClusterSong.timeText(1_000, 0))
        assertNull(Mg4ClusterSong.fraction(1_000, null))
    }

    @Test
    fun fractionIsClamped() {
        assertEquals(0.5f, Mg4ClusterSong.fraction(100_000, 200_000)!!, 0.0001f)
        assertEquals(1f, Mg4ClusterSong.fraction(300_000, 200_000)!!, 0.0001f)
        assertEquals(0f, Mg4ClusterSong.fraction(-1, 200_000)!!, 0.0001f)
    }

    @Test
    fun gradientTintKeepsTheHueDarkAndReadable() {
        // Bright red cover: a dark red (value 0.32, saturation capped at 0.75).
        assertEquals(0xff521414.toInt(), Mg4ClusterSong.tint(0xffff0000.toInt()))
        // Grey cover: dark grey.
        assertEquals(0xff525252.toInt(), Mg4ClusterSong.tint(0xff808080.toInt()))
        // A pale blue gets more saturation, still dark and blue.
        val blue = Mg4ClusterSong.tint(0xff8090c0.toInt())
        assertEquals(0x52, blue and 0xff)
        assertEquals(true, (blue shr 16 and 0xff) < (blue and 0xff))
    }
}
