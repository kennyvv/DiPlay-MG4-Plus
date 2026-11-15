package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlaySongStateTest {
    private fun update(block: com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder.() -> Unit) =
        Iap2Messages.buildRaw(CarPlaySongState.NOW_PLAYING_UPDATE, block)

    @Test
    fun readsTitleArtistAlbumAndPlayState() {
        val state = CarPlaySongState()
        val song = state.accept(update {
            group(0) { string(1, "Song"); string(6, "Album"); string(12, "Artist") }
            group(1) { u8(0, 1) }
        })
        assertEquals(CarPlaySong("Song", "Artist", "Album", playing = true), song)
    }

    @Test
    fun updatesKeepWhatTheyLeaveOut() {
        val state = CarPlaySongState()
        state.accept(update { group(0) { string(1, "Song"); string(12, "Artist") } })
        // Pause alone: the song stays.
        assertEquals(CarPlaySong("Song", "Artist", null, playing = false), state.accept(update { group(1) { u8(0, 2) } }) ?: state.current())
    }

    @Test
    fun playbackPositionMovesOnWhilePlaying() {
        var now = 10_000L
        val state = CarPlaySongState { now }
        state.accept(update { group(0) { string(1, "Song"); u32(4, 200_000L) }; group(1) { u8(0, 1); u32(1, 30_000L) } })
        val song = state.current()!!
        assertEquals(200_000L, song.durationMs)
        assertEquals(30_000L, song.positionMs(now))
        now += 5_000L
        assertEquals(35_000L, song.positionMs(now))
        // Never past the end.
        assertEquals(200_000L, song.positionMs(now + 1_000_000L))
        // Paused: the position stays where the iPhone said.
        val paused = state.accept(update { group(1) { u8(0, 2); u32(1, 36_000L) } })!!
        assertEquals(36_000L, paused.positionMs(now + 60_000L))
        // A new song forgets the old length and position.
        state.accept(update { group(0) { string(1, "Next") } })
        assertNull(state.current()?.durationMs)
        assertNull(state.current()?.positionMs(now))
    }

    @Test
    fun anEmptyTitleForgetsTheSong() {
        val state = CarPlaySongState()
        state.accept(update { group(0) { string(1, "Song"); string(12, "Artist") } })
        state.accept(update { group(0) { string(1, "") } })
        assertNull(state.current())
    }

    @Test
    fun otherMessagesAndSessionEnd() {
        val state = CarPlaySongState()
        assertNull(state.accept(Iap2Messages.buildRaw(0x5201) { group(0) { string(1, "Song") } }))
        state.accept(update { group(0) { string(1, "Song") } })
        state.clear()
        assertNull(state.current())
    }

    @Test
    fun theCoverIdBelongsToItsSong() {
        val state = CarPlaySongState()
        state.accept(update { group(0) { string(1, "Song"); u8(26, 3) } })
        assertEquals(3, state.current()?.artworkId)
        // A new song without an id must not keep the old cover.
        state.accept(update { group(0) { string(1, "Next") } })
        assertNull(state.current()?.artworkId)
    }
}
