package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.media.CarPlaySong
import com.shilapi.xcertplay.transport.Iap2ArtworkTransfer

/**
 * Experimental: the CarPlay song in the MG4 cluster's centre square ([Mg4ClusterSquare]) on its
 * Music page.
 *
 * The cluster's own song card cannot show CarPlay from DiPlay: read from the SWI68 firmware, it
 * takes CarPlay songs only from the stock AllGo media browser, which gets them only from the stock
 * AllGo CarPlay service, both bound by fixed package names. So DiPlay draws its own card over the
 * square: title, artist, album and a paused mark, from the iPhone's now-playing updates, and the
 * cover when album art is on ([updateArtwork]). A cover shows only for the song whose artwork
 * transfer id it carries.
 */
internal object Mg4ClusterSong {
    private const val TITLE_PX = 26f
    private const val ARTIST_PX = 21f
    private const val ALBUM_PX = 17f
    private const val STATE_PX = 15f
    private const val PADDING_PX = 18
    private const val ART_PX = 104

    /**
     * Show on the Music page while a song is known. An unreadable page shows the card only when the
     * map is not in the square, so the two never cover each other.
     */
    fun visibleOn(tab: Int?, hasSong: Boolean, mapShown: Boolean): Boolean =
        hasSong && (tab == Mg4ClusterSquare.TAB_MEDIA || (tab == null && !mapShown))

    /** The cover to show for [song], if [coverId] (the decoded cover's transfer id) belongs to it. */
    fun coverMatches(song: CarPlaySong?, coverId: Int?): Boolean =
        song?.artworkId != null && song.artworkId == coverId

    /** The power-of-two step that brings a [width] x [height] image down to about [target] px. */
    fun sampleSize(width: Int, height: Int, target: Int): Int {
        var size = 1
        while (width / (size * 2) >= target && height / (size * 2) >= target) size *= 2
        return size
    }

    // Lazy so the pure rules above can be unit tested without a looper.
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val decoder by lazy { Handler(HandlerThread("mg4-cluster-art").apply { start() }.looper) }

    private var square: Mg4ClusterSquare? = null
    private var song: CarPlaySong? = null
    private var cover: Pair<Int, Bitmap>? = null
    private var art: ImageView? = null
    private var note: TextView? = null
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
        showCover()
        square?.refresh()
    }

    /** A finished artwork transfer (empty: no cover); any thread. Decoded off the main thread. */
    fun updateArtwork(transfer: Iap2ArtworkTransfer) {
        decoder.post {
            val bitmap = decode(transfer.bytes)
            main.post {
                cover = bitmap?.let { transfer.id to it }
                log("Cluster song: cover id=${transfer.id} ${if (bitmap == null) "none" else "${bitmap.width}x${bitmap.height}"}")
                showCover()
            }
        }
    }

    /** Takes the card off the cluster. Main thread. */
    fun detach() {
        if (square != null) log("Cluster song: window removed")
        square?.remove()
        square = null
        song = null
        cover = null
        art = null
        note = null
        title = null
        artist = null
        album = null
        state = null
    }

    private fun showCover() {
        val shown = coverMatches(song, cover?.first)
        art?.setImageBitmap(if (shown) cover?.second else null)
        art?.visibility = if (shown) View.VISIBLE else View.GONE
        // With a cover the card keeps title and artist; the note and album make room.
        note?.visibility = if (shown) View.GONE else View.VISIBLE
        album?.visibility = if (shown) View.GONE else View.VISIBLE
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, ART_PX) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }.getOrNull()
    }

    private fun card(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Mg4ClusterSquare.BACKGROUND)
        setPadding(PADDING_PX, PADDING_PX, PADDING_PX, PADDING_PX)
        art = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }.also { addView(it, LinearLayout.LayoutParams(ART_PX, ART_PX).apply { bottomMargin = PADDING_PX / 2 }) }
        note = text(context, "♪", ARTIST_PX, Color.rgb(0x8a, 0x8a, 0x8a), lines = 1).also(::addView)
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
