package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** The song CarPlay plays, as the cluster card shows it. */
data class CarPlaySong(val title: String, val artist: String?, val album: String?, val playing: Boolean)

/**
 * The CarPlay song from iAP2 NowPlayingUpdate (0x5001): title (1), album (6) and artist (12) in
 * MediaItemAttributes, playback status in PlaybackAttributes. Updates carry only what changed, so
 * omitted fields keep their values, while an explicitly empty title forgets the item.
 *
 * Ported from upstream DiPlay's `ClusterSongState` (shihabal3amri/DiPlay), adding the album.
 */
class CarPlaySongState {
    private var title: String? = null
    private var artist: String? = null
    private var album: String? = null
    private var playing = false
    private var last: CarPlaySong? = null

    /** The song after [frame], or null when it did not change; [current] holds the song itself. */
    fun accept(frame: Iap2Frame): CarPlaySong? {
        if (frame.messageId != NOW_PLAYING_UPDATE) return null
        val body = runCatching { Iap2BodyReader.of(frame) }.getOrNull() ?: return null
        runCatching { body.optionalGroup(ITEM) }.getOrNull()?.let { item ->
            val nextTitle = runCatching { item.optionalString(TITLE) }.getOrNull()
            if (nextTitle != null) {
                title = nextTitle
                if (nextTitle.isBlank()) {
                    artist = null
                    album = null
                }
            }
            if (nextTitle?.isBlank() != true) {
                runCatching { item.optionalString(ARTIST) }.getOrNull()?.let { artist = it }
                runCatching { item.optionalString(ALBUM) }.getOrNull()?.let { album = it }
            }
        }
        runCatching { body.optionalGroup(PLAYBACK)?.optionalU8(STATUS) }.getOrNull()?.let { status ->
            playing = status == STATUS_PLAYING || status == STATUS_SEEK_FORWARD || status == STATUS_SEEK_BACKWARD
        }
        val name = title?.trim()?.takeIf { it.isNotEmpty() }
        val next = name?.let {
            CarPlaySong(it, artist?.trim()?.takeIf(String::isNotEmpty), album?.trim()?.takeIf(String::isNotEmpty), playing)
        }
        if (next == last) return null
        last = next
        return next
    }

    /** The song known so far, if any. */
    fun current(): CarPlaySong? = last

    /** The session ended: forget the song. */
    fun clear() {
        title = null
        artist = null
        album = null
        playing = false
        last = null
    }

    companion object {
        const val NOW_PLAYING_UPDATE = 0x5001
        private const val ITEM = 0
        private const val TITLE = 1
        private const val ALBUM = 6
        private const val ARTIST = 12
        private const val PLAYBACK = 1
        private const val STATUS = 0
        private const val STATUS_PLAYING = 1
        private const val STATUS_SEEK_FORWARD = 3
        private const val STATUS_SEEK_BACKWARD = 4
    }
}
