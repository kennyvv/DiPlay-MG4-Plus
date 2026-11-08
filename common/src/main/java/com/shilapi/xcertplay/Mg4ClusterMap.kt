package com.shilapi.xcertplay

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import com.shilapi.xcertplay.media.AndroidMediaSink

/**
 * Experimental: CarPlay's instrument-cluster stream in the MG4 cluster's centre square
 * ([Mg4ClusterSquare]).
 *
 * The map covers the square only while the cluster shows its Navigation page, so the Music page
 * keeps the car's own song card (or [Mg4ClusterSong]); when the page is unreadable, the map stays.
 * The video is a TextureView, not a SurfaceView: the square is hidden by window transparency, which
 * a SurfaceView's layer ignores (both windows showed at once in the car, 2026-11-08). Its buffer
 * is the stream size, scaled into the square, so a larger stream draws Apple Maps smaller.
 */
internal object Mg4ClusterMap {
    private const val SCREEN_TYPE_ALT = 111

    /** Show the map on the Navigation page, or whenever the page cannot be read. */
    fun visibleOn(tab: Int?): Boolean = tab == null || tab == Mg4ClusterSquare.TAB_NAVIGATION

    private var square: Mg4ClusterSquare? = null
    private var surface: Surface? = null
    private var sink: AndroidMediaSink? = null
    private var log: (String) -> Unit = {}

    val isShown: Boolean get() = square != null

    /** Puts the square on the cluster and feeds it [sink]'s cluster stream of [streamSide] px. Main thread. */
    fun attach(context: Context, sink: AndroidMediaSink, streamSide: Int, log: (String) -> Unit) {
        this.log = log
        if (this.sink !== sink) {
            this.sink = sink
            surface?.let { sink.setSurface(SCREEN_TYPE_ALT, it) }
        }
        if (square == null) {
            square = Mg4ClusterSquare.open(context, "MG4CPlay cluster map", ::visibleOn, { this.log("Cluster map: $it") }) { display ->
                FrameLayout(display).apply {
                    setBackgroundColor(Mg4ClusterSquare.BACKGROUND)
                    addView(video(display, streamSide), FrameLayout.LayoutParams(-1, -1))
                }
            }
        }
    }

    /** Takes the square off the cluster. Main thread. */
    fun detach() {
        surface?.let { sink?.clearSurface(SCREEN_TYPE_ALT, it) }
        sink = null
        if (square != null) log("Cluster map: window removed")
        square?.remove()
        square = null
        surface?.release()
        surface = null
    }

    private fun video(context: Context, streamSide: Int) = TextureView(context).apply {
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                texture.setDefaultBufferSize(streamSide, streamSide)
                val next = Surface(texture)
                surface = next
                sink?.setSurface(SCREEN_TYPE_ALT, next)
                log("Cluster map: surface ready ${streamSide}x$streamSide in ${width}x$height")
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                surface?.let { sink?.clearSurface(SCREEN_TYPE_ALT, it) }
                surface?.release()
                surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
    }
}
