package com.shilapi.xcertplay

import android.content.Context
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.media.AndroidMediaSink

/**
 * Experimental: CarPlay's instrument-cluster stream in the MG4 cluster's centre square
 * ([Mg4ClusterSquare]).
 *
 * The map covers the square only while the cluster shows its Navigation page, so the Music page
 * keeps the car's own song card (or [Mg4ClusterSong]); when the page is unreadable, the map stays.
 * Hidden, the window moves off the display rather than going away, so the decoder keeps its
 * surface.
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

    /** Puts the square on the cluster and feeds it [sink]'s cluster stream. Main thread. */
    fun attach(context: Context, sink: AndroidMediaSink, log: (String) -> Unit) {
        this.log = log
        if (this.sink !== sink) {
            this.sink = sink
            surface?.let { sink.setSurface(SCREEN_TYPE_ALT, it) }
        }
        if (square == null) {
            square = Mg4ClusterSquare.open(context, "MG4CPlay cluster map", ::visibleOn, { this.log("Cluster map: $it") }) { display ->
                FrameLayout(display).apply {
                    setBackgroundColor(Mg4ClusterSquare.BACKGROUND)
                    addView(video(display), FrameLayout.LayoutParams(-1, -1))
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
        surface = null
    }

    private fun video(context: Context) = SurfaceView(context).apply {
        holder.setFixedSize(CarPlayClusterDisplay.SIDE_PIXELS, CarPlayClusterDisplay.SIDE_PIXELS)
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surface = holder.surface
                sink?.setSurface(SCREEN_TYPE_ALT, holder.surface)
                log("Cluster map: surface ready")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                sink?.clearSurface(SCREEN_TYPE_ALT, holder.surface)
                surface = null
            }
        })
    }
}
