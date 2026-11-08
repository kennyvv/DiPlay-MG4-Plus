package com.shilapi.xcertplay

import android.content.Context
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import com.shilapi.xcertplay.media.AndroidMediaSink

/**
 * Experimental: CarPlay's instrument-cluster stream in the MG4 cluster's centre square
 * ([Mg4ClusterSquare]).
 *
 * The map covers the square only while the cluster shows its Navigation page, so the Music page
 * keeps the car's own song card (or [Mg4ClusterSong]); when the page is unreadable, the map stays.
 * The video buffer is the stream size, scaled into the square, so a larger stream draws Apple Maps
 * smaller. It is a SurfaceView on the hardware overlay layer, like the stock CarPlay app; a
 * TextureView was tried (swi68.8-9) and looked flatter in the car. A SurfaceView's layer ignores
 * window transparency, so the map hides by parking where the cluster never looks
 * ([Mg4ClusterSquare.Hide.PARK]).
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
            square = Mg4ClusterSquare.open(
                context,
                "MG4CPlay cluster map",
                ::visibleOn,
                { this.log("Cluster map: $it") },
                hide = Mg4ClusterSquare.Hide.PARK,
            ) { display ->
                FrameLayout(display).apply {
                    setBackgroundColor(Mg4ClusterSquare.BACKGROUND)
                    addView(surfaceVideo(display, streamSide), FrameLayout.LayoutParams(-1, -1))
                }
            }
            this.log("Cluster map: stream ${streamSide}x$streamSide")
        }
    }

    /** Takes the square off the cluster. Main thread. */
    fun detach() {
        surface?.let { sink?.clearSurface(SCREEN_TYPE_ALT, it) }
        sink = null
        if (square != null) log("Cluster map: window removed")
        square?.remove()
        square = null
        surface = null
    }

    private fun onSurface(next: Surface?, detail: String) {
        val previous = surface
        if (previous != null && previous !== next) sink?.clearSurface(SCREEN_TYPE_ALT, previous)
        surface = next
        if (next != null) {
            sink?.setSurface(SCREEN_TYPE_ALT, next)
            log("Cluster map: surface ready $detail")
        }
    }

    private fun surfaceVideo(context: Context, streamSide: Int): View = SurfaceView(context).apply {
        holder.setFixedSize(streamSide, streamSide)
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = onSurface(holder.surface, "overlay ${streamSide}x$streamSide")

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) = onSurface(null, "")
        })
    }
}
