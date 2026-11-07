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
        // Elapsed time alone changes nothing.
        assertNull(state.accept(update { group(1) { u32(1, 1_000L) } }))
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
}
