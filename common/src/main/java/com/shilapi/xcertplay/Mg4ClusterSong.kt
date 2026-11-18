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
 * transfer id it carries. When the iPhone sends the track length and position, a thin bar and
 * "1:23 / 3:45" show how far the song is, moved on once a second while it plays. With a cover, the card's
 * background is a gentle gradient from a dark tone of the cover's colour at the top to the cluster's black.
 */
internal object Mg4ClusterSong {
    private const val TITLE_PX = 26f
    private const val ARTIST_PX = 21f
    private const val ALBUM_PX = 17f
    private const val STATE_PX = 15f
    private const val PADDING_PX = 18
    private const val ART_PX = 104
    private const val BAR_PX = 4
    private const val TICK_MS = 1_000L

    /**
     * Show on the Music page while a song is known. An unreadable page shows the card only when the
     * map is not in the square, so the two never cover each other.
     */
    fun visibleOn(tab: Int?, hasSong: Boolean, mapShown: Boolean): Boolean =
        hasSong && (tab == Mg4ClusterSquare.TAB_MEDIA || (tab == null && !mapShown))

    /** The cover to show for [song], if [coverId] (the decoded cover's transfer id) belongs to it. */
    fun coverMatches(song: CarPlaySong?, coverId: Int?): Boolean =
        song?.artworkId != null && song.artworkId == coverId

    /** "1:23 / 3:45" (hours when needed), or null without a length and position. */
    fun timeText(positionMs: Long?, durationMs: Long?): String? {
        if (positionMs == null || durationMs == null || durationMs <= 0) return null
        return "${clock(positionMs.coerceIn(0, durationMs))} / ${clock(durationMs)}"
    }

    /** How far [positionMs] is through [durationMs], 0 to 1, or null when unknown. */
    fun fraction(positionMs: Long?, durationMs: Long?): Float? {
        if (positionMs == null || durationMs == null || durationMs <= 0) return null
        return (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()
    }

    private fun clock(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) "%d:%02d:%02d".format(java.util.Locale.US, h, m, sec) else "%d:%02d".format(java.util.Locale.US, m, sec)
    }

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
    private var cardView: View? = null
    private var note: TextView? = null
    private var title: TextView? = null
    private var artist: TextView? = null
    private var album: TextView? = null
    private var state: TextView? = null
    private var bar: ProgressLine? = null
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
                content = ::card,
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
        showProgress()
        showCover()
        square?.refresh()
    }

    private val tick = object : Runnable {
        override fun run() {
            showProgress()
        }
    }

    /** The paused mark, the time and the bar; repeats once a second while the song plays with a known position. */
    private fun showProgress() {
        main.removeCallbacks(tick)
        val s = song
        val now = android.os.SystemClock.elapsedRealtime()
        val position = s?.positionMs(now)
        val time = timeText(position, s?.durationMs)
        val paused = if (s != null && !s.playing) "❚❚" else ""
        state?.text = listOf(paused, time.orEmpty()).filter { it.isNotEmpty() }.joinToString("  ")
        val fraction = fraction(position, s?.durationMs)
        bar?.fraction = fraction ?: 0f
        bar?.visibility = if (fraction == null) View.GONE else View.VISIBLE
        if (s != null && s.playing && time != null && square != null) main.postDelayed(tick, TICK_MS)
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
        main.removeCallbacks(tick)
        if (square != null) log("Cluster song: window removed")
        square?.remove()
        square = null
        song = null
        cover = null
        art = null
        cardView = null
        note = null
        title = null
        artist = null
        album = null
        state = null
        bar = null
    }

    private fun showCover() {
        val shown = coverMatches(song, cover?.first)
        art?.setImageBitmap(if (shown) cover?.second else null)
        art?.visibility = if (shown) View.VISIBLE else View.GONE
        cardView?.background = background(if (shown) cover?.second else null)
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
        cardView = this
        setPadding(PADDING_PX, PADDING_PX, PADDING_PX, PADDING_PX)
        art = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }.also { addView(it, LinearLayout.LayoutParams(ART_PX, ART_PX).apply { bottomMargin = PADDING_PX / 2 }) }
        note = text(context, "♪", ARTIST_PX, Color.rgb(0x8a, 0x8a, 0x8a), lines = 1).also(::addView)
        title = text(context, "", TITLE_PX, Color.WHITE, lines = 2, bold = true).also(::addView)
        artist = text(context, "", ARTIST_PX, Color.rgb(0xc8, 0xc8, 0xc8), lines = 1).also(::addView)
        album = text(context, "", ALBUM_PX, Color.rgb(0x8a, 0x8a, 0x8a), lines = 1).also(::addView)
        bar = ProgressLine(context).apply { visibility = View.GONE }.also {
            addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, BAR_PX).apply { topMargin = PADDING_PX / 2 })
        }
        state = text(context, "", STATE_PX, Color.rgb(0x8a, 0x8a, 0x8a), lines = 1).also(::addView)
    }

    /** The card's background: plain black, or with a cover a gentle gradient from [tint] of its colour at the top. */
    private fun background(cover: Bitmap?): android.graphics.drawable.Drawable {
        val average = cover?.let { runCatching { Bitmap.createScaledBitmap(it, 1, 1, true).getPixel(0, 0) }.getOrNull() }
            ?: return android.graphics.drawable.ColorDrawable(Mg4ClusterSquare.BACKGROUND)
        return android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(tint(average), Mg4ClusterSquare.BACKGROUND),
        )
    }

    /**
     * A dark tone of [color] (the cover's average) for the top of the gradient: its hue, a little more
     * saturated (at most [TINT_MAX_SATURATION]), at brightness [TINT_VALUE] so white text stays easy to read.
     * A grey cover gives a dark grey.
     */
    fun tint(color: Int): Int {
        val r = (color shr 16 and 0xff) / 255f
        val g = (color shr 8 and 0xff) / 255f
        val b = (color and 0xff) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val hue = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta).mod(6f))
            max == g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        val saturation = if (max == 0f) 0f else (delta / max * TINT_SATURATION_BOOST).coerceAtMost(TINT_MAX_SATURATION)
        return hsv(hue, saturation, TINT_VALUE)
    }

    private fun hsv(hue: Float, saturation: Float, value: Float): Int {
        val c = value * saturation
        val x = c * (1 - kotlin.math.abs((hue / 60f).mod(2f) - 1))
        val m = value - c
        val (r, g, b) = when ((hue / 60f).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun channel(v: Float) = ((v + m) * 255 + 0.5f).toInt().coerceIn(0, 255)
        return (0xff shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    private const val TINT_VALUE = 0.32f
    private const val TINT_SATURATION_BOOST = 1.3f
    private const val TINT_MAX_SATURATION = 0.75f

    /** A thin bar: grey track, white part played. */
    private class ProgressLine(context: Context) : View(context) {
        private val track = android.graphics.Paint().apply { color = Color.rgb(0x44, 0x44, 0x44) }
        private val played = android.graphics.Paint().apply { color = Color.WHITE }
        var fraction = 0f
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = h / 2
            canvas.drawRoundRect(0f, 0f, w, h, r, r, track)
            if (fraction > 0f) canvas.drawRoundRect(0f, 0f, w * fraction, h, r, r, played)
        }
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
