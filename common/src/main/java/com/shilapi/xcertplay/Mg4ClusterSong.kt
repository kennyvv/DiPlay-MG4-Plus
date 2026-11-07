package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.media.CarPlaySong

/**
 * Experimental: the CarPlay song in the MG4 cluster's centre square ([Mg4ClusterSquare]) on its
 * Music page.
 *
 * The cluster's own song card cannot show CarPlay from DiPlay: read from the SWI68 firmware, it
 * takes CarPlay songs only from the stock AllGo media browser, which gets them only from the stock
 * AllGo CarPlay service, both bound by fixed package names. So DiPlay draws its own card over the
 * square: title, artist, album and a paused mark, from the iPhone's now-playing updates.
 */
internal object Mg4ClusterSong {
    private const val TITLE_PX = 26f
    private const val ARTIST_PX = 21f
    private const val ALBUM_PX = 17f
    private const val STATE_PX = 15f
    private const val PADDING_PX = 18

    /**
     * Show on the Music page while a song is known. An unreadable page shows the card only when the
     * map is not in the square, so the two never cover each other.
     */
    fun visibleOn(tab: Int?, hasSong: Boolean, mapShown: Boolean): Boolean =
        hasSong && (tab == Mg4ClusterSquare.TAB_MEDIA || (tab == null && !mapShown))

    private var square: Mg4ClusterSquare? = null
    private var song: CarPlaySong? = null
    private var title: TextView? = null
    private var artist: TextView? = null
    private var album: TextView? = null
    private var state: TextView? = null
    private var log: (String) -> Unit = {}

    val isShown: Boolean get() = square != null

    /** Puts the card on the cluster for a CarPlay session. Main thread. */
    fun attach(context: Context, current: CarPlaySong?, log: (String) -> Unit) {
        this.log = log
        if (square == null) {
            square = Mg4ClusterSquare.open(
                context,
                "MG4CPlay cluster song",
                { tab -> visibleOn(tab, song != null, Mg4ClusterMap.isShown) },
                { this.log("Cluster song: $it") },
                ::card,
            )
        }
        update(current)
    }

    /** A new song or play state from the iPhone; null when none is known. Main thread. */
    fun update(next: CarPlaySong?) {
        if (next == song) return
        if (next?.title != song?.title) log("Cluster song: ${if (next == null) "none" else "new song"}")
        song = next
        title?.text = next?.title.orEmpty()
        artist?.text = next?.artist.orEmpty()
        album?.text = next?.album.orEmpty()
        state?.text = if (next != null && !next.playing) "❚❚" else ""
        square?.refresh()
    }

    /** Takes the card off the cluster. Main thread. */
    fun detach() {
        if (square != null) log("Cluster song: window removed")
        square?.remove()
        square = null
        song = null
        title = null
        artist = null
        album = null
        state = null
    }

    private fun card(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Mg4ClusterSquare.BACKGROUND)
        setPadding(PADDING_PX, PADDING_PX, PADDING_PX, PADDING_PX)
        addView(text(context, "♪", ARTIST_PX, Color.rgb(0x8a, 0x8a, 0x8a), lines = 1))
        title = text(context, "", TITLE_PX, Color.WHITE, lines = 2, bold = true).also(::addView)
        artist = text(context, "", ARTIST_PX, Color.rgb(0xc8, 0xc8, 0xc8), lines = 1).also(::addView)
        album = text(context, "", ALBUM_PX, Color.rgb(0x8a, 0x8a, 0x8a), lines = 1).also(::addView)
        state = text(context, "", STATE_PX, Color.rgb(0x8a, 0x8a, 0x8a), lines = 1).also(::addView)
    }

    private fun text(context: Context, value: String, sizePx: Float, color: Int, lines: Int, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
            setTextColor(color)
            gravity = Gravity.CENTER
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
}
