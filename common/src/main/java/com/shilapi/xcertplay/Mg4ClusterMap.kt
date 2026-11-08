package com.shilapi.xcertplay

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
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
 * smaller. Two renderers ([Renderer]): the hardware overlay layer of a SurfaceView, as the map was
 * first seen working in the car, or a GPU-composited TextureView.
 */
internal object Mg4ClusterMap {
    private const val SCREEN_TYPE_ALT = 111

    enum class Renderer(val hide: Mg4ClusterSquare.Hide) {
        /** Overlay layer, the stock look; a SurfaceView ignores window alpha, so it hides by parking. */
        SURFACE(Mg4ClusterSquare.Hide.PARK),
        /** GPU composited (can look flatter, needs hardware acceleration); hides by transparency in place. */
        TEXTURE(Mg4ClusterSquare.Hide.TRANSPARENT),
    }

    /** Show the map on the Navigation page, or whenever the page cannot be read. */
    fun visibleOn(tab: Int?): Boolean = tab == null || tab == Mg4ClusterSquare.TAB_NAVIGATION

    private var square: Mg4ClusterSquare? = null
    private var surface: Surface? = null
    private var sink: AndroidMediaSink? = null
    private var log: (String) -> Unit = {}

    val isShown: Boolean get() = square != null

    /** Puts the square on the cluster and feeds it [sink]'s cluster stream of [streamSide] px. Main thread. */
    fun attach(context: Context, sink: AndroidMediaSink, streamSide: Int, renderer: Renderer, log: (String) -> Unit) {
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
                hide = renderer.hide,
            ) { display ->
                FrameLayout(display).apply {
                    setBackgroundColor(Mg4ClusterSquare.BACKGROUND)
                    val video = when (renderer) {
                        Renderer.SURFACE -> surfaceVideo(display, streamSide)
                        Renderer.TEXTURE -> textureVideo(display, streamSide)
                    }
                    addView(video, FrameLayout.LayoutParams(-1, -1))
                }
            }
            this.log("Cluster map: renderer=$renderer stream=${streamSide}x$streamSide")
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

    private fun textureVideo(context: Context, streamSide: Int): View = TextureView(context).apply {
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                texture.setDefaultBufferSize(streamSide, streamSide)
                onSurface(Surface(texture), "texture ${streamSide}x$streamSide in ${width}x$height")
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                val previous = surface
                onSurface(null, "")
                previous?.release()
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
    }
}
