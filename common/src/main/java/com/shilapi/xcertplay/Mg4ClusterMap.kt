package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.vehicle.VehicleIntegration

/**
 * Experimental: CarPlay's instrument-cluster stream in the MG4 cluster's centre square.
 *
 * Read from the SWI68-29958-1300R59 firmware: SystemUI launches the cluster app
 * (`com.saicmotor.hmi.clusterprojection`) on Android display 1, and its layout
 * (`activity_projection`) is a 260 px square with margins of 424 px left and 32 px top on a
 * #0c0c0c background. The cluster is a separate computer that shows only that square, and only on
 * its Navigation and Music pages. MG4Control (SliDeeN/MG4Control) found that a window over the
 * square shows on the cluster, and that the square sits centred in what the margins leave on the
 * 800x480 display, not at the margins themselves.
 *
 * The window covers the square only while the cluster shows its Navigation page
 * ([VehicleIntegration.clusterTab]), so the Music page keeps the car's own song card; when the page is
 * unreadable, the map stays. Hidden, the window moves off the display rather than going away, so
 * the decoder keeps its surface.
 */
internal object Mg4ClusterMap {
    private const val CLUSTER_DISPLAY_ID = 1
    private const val SQUARE_PX = 260
    private const val MARGIN_LEFT_PX = 424
    private const val MARGIN_TOP_PX = 32
    private const val BACKGROUND = 0xff0c0c0c.toInt()
    private const val SCREEN_TYPE_ALT = 111
    private const val TAB_NAVIGATION = 1
    private const val TAB_POLL_MILLIS = 1_000L

    data class Placement(val x: Int, val y: Int, val side: Int)

    /** Where the cluster app's square lands on a [width] x [height] display. */
    fun placement(width: Int, height: Int): Placement {
        val side = minOf(SQUARE_PX, width, height).coerceAtLeast(0)
        val x = if (width >= MARGIN_LEFT_PX + side) MARGIN_LEFT_PX + (width - MARGIN_LEFT_PX - side) / 2 else (width - side) / 2
        val y = if (height >= MARGIN_TOP_PX + side) MARGIN_TOP_PX + (height - MARGIN_TOP_PX - side) / 2 else (height - side) / 2
        return Placement(x, y, side)
    }

    /** Show the map on the Navigation page, or whenever the page cannot be read. */
    fun visibleOn(tab: Int?): Boolean = tab == null || tab == TAB_NAVIGATION

    // Lazy so the pure [placement] and [visibleOn] can be unit tested without a looper.
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var windows: WindowManager? = null
    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var placement: Placement? = null
    private var displayWidth = 0
    private var surface: Surface? = null
    private var sink: AndroidMediaSink? = null
    private var poller: Handler? = null
    private var shownTab: Int? = null
    private var tabLogged = false
    private var log: (String) -> Unit = {}

    val isShown: Boolean get() = root != null

    /** Puts the square on the cluster and feeds it [sink]'s cluster stream. Main thread. */
    fun attach(context: Context, sink: AndroidMediaSink, log: (String) -> Unit) {
        this.log = log
        if (this.sink !== sink) {
            this.sink = sink
            surface?.let { sink.setSurface(SCREEN_TYPE_ALT, it) }
        }
        if (root == null) show(context.applicationContext)
    }

    /** Takes the square off the cluster. Main thread. */
    fun detach() {
        poller?.looper?.quitSafely()
        poller = null
        surface?.let { sink?.clearSurface(SCREEN_TYPE_ALT, it) }
        sink = null
        root?.let { view -> runCatching { windows?.removeViewImmediate(view) } }
        if (root != null) log("Cluster map: window removed")
        root = null
        windows = null
        params = null
        surface = null
    }

    private fun show(context: Context) {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(CLUSTER_DISPLAY_ID)
        if (display == null) {
            log("Cluster map: no display $CLUSTER_DISPLAY_ID; the cluster square is not available")
            return
        }
        val size = Point().also {
            @Suppress("DEPRECATION")
            display.getRealSize(it)
        }
        val place = placement(size.x, size.y)
        val displayContext = context.createDisplayContext(display)
        val manager = displayContext.getSystemService(WindowManager::class.java) ?: return
        val video = SurfaceView(displayContext).apply {
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
        val frame = FrameLayout(displayContext).apply {
            setBackgroundColor(BACKGROUND)
            addView(video, FrameLayout.LayoutParams(-1, -1))
        }
        @Suppress("DEPRECATION")
        val layout = WindowManager.LayoutParams(
            place.side,
            place.side,
            WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = place.x
            y = place.y
            title = "MG4CPlay cluster map"
        }
        try {
            manager.addView(frame, layout)
        } catch (error: Exception) {
            log("Cluster map: window refused on display ${display.displayId}: ${error.javaClass.simpleName}")
            return
        }
        windows = manager
        root = frame
        params = layout
        placement = place
        displayWidth = size.x
        shownTab = null
        tabLogged = false
        log("Cluster map: window on display ${display.displayId} ${size.x}x${size.y}, ${place.side} px square at ${place.x},${place.y}")
        startPolling()
    }

    private fun startPolling() {
        val handler = Handler(HandlerThread("mg4-cluster-map").apply { start() }.looper)
        poller = handler
        handler.post(object : Runnable {
            override fun run() {
                if (poller !== handler) return
                val tab = VehicleIntegration.clusterTab()
                main.post { applyTab(tab) }
                handler.postDelayed(this, TAB_POLL_MILLIS)
            }
        })
    }

    private fun applyTab(tab: Int?) {
        val view = root ?: return
        val layout = params ?: return
        val place = placement ?: return
        if (!tabLogged || tab != shownTab) {
            log("Cluster map: cluster page=${tab ?: "unreadable"} map=${if (visibleOn(tab)) "shown" else "hidden"}")
            tabLogged = true
        }
        shownTab = tab
        val x = if (visibleOn(tab)) place.x else displayWidth + place.side
        if (layout.x == x) return
        layout.x = x
        runCatching { windows?.updateViewLayout(view, layout) }
    }
}
