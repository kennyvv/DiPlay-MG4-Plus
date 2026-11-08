package com.shilapi.xcertplay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.shilapi.xcertplay.vehicle.VehicleIntegration

/**
 * A window over the MG4 cluster's centre square, for [Mg4ClusterMap] and [Mg4ClusterSong].
 *
 * Read from the SWI68-29958-1300R59 firmware: SystemUI launches the cluster app
 * (`com.saicmotor.hmi.clusterprojection`) on Android display 1, and its layout
 * (`activity_projection`) is a 260 px square with margins of 424 px left and 32 px top on a
 * #0c0c0c background. The cluster is a separate computer that shows only that square, and only on
 * its Navigation and Music pages. MG4Control (SliDeeN/MG4Control) found that a window over the
 * square shows on the cluster, and that the square sits centred in what the margins leave on the
 * 800x480 display, not at the margins themselves.
 *
 * A window covers the square or a [Band] of it. Each window follows the cluster page
 * ([VehicleIntegration.clusterTab], read once a second) and hides when its page is not showing, in a
 * way that keeps a decoder surface inside it alive ([Hide]). Moving a window off the display does
 * not hide it: Android pulls it back to the edge (x 540 on 800 px, beside the square at 482, so the
 * song card drew over the navigation page in the car, 2026-11-08).
 */
internal class Mg4ClusterSquare private constructor(
    private val windows: WindowManager,
    private val root: View,
    private val params: WindowManager.LayoutParams,
    private val visibleOn: (Int?) -> Boolean,
    private val log: (String) -> Unit,
    private val hide: Hide,
    private val shownX: Int,
    private val shownY: Int,
) {
    data class Placement(val x: Int, val y: Int, val side: Int)

    /** How a window hides when its page is not showing. */
    enum class Hide {
        /** Fully transparent in place, for windows without video. A SurfaceView's layer ignores window alpha. */
        TRANSPARENT,
        /**
         * Moved to the display's top-left corner, which the cluster never shows (it takes only the
         * square). Works for any view, a SurfaceView included.
         */
        PARK,
    }

    /** A band of the square, [top] and [height] in px from the square's top; null height is the whole square. */
    data class Band(val top: Int = 0, val height: Int? = null)

    private val main = Handler(Looper.getMainLooper())
    private var poller: Handler? = null
    private var tab: Int? = null
    private var tabLogged = false

    /** The page last read, null when unreadable. */
    val clusterTab: Int? get() = tab

    /** Applies the visibility rule again, for a rule that depends on more than the page. */
    fun refresh() = apply(tab)

    /** Takes the window off the cluster. Main thread. */
    fun remove() {
        poller?.looper?.quitSafely()
        poller = null
        runCatching { windows.removeViewImmediate(root) }
    }

    private fun startPolling(name: String) {
        val handler = Handler(HandlerThread(name).apply { start() }.looper)
        poller = handler
        handler.post(object : Runnable {
            override fun run() {
                if (poller !== handler) return
                val next = VehicleIntegration.clusterTab()
                main.post { if (poller === handler) apply(next) }
                handler.postDelayed(this, TAB_POLL_MILLIS)
            }
        })
    }

    private fun apply(next: Int?) {
        val visible = visibleOn(next)
        if (!tabLogged || next != tab) {
            log("cluster page=${next ?: "unreadable"} ${if (visible) "shown" else "hidden"}")
            tabLogged = true
        }
        tab = next
        val (x, y) = if (visible) shownX to shownY else hiddenPosition(hide, shownX, shownY)
        val alpha = if (visible || hide == Hide.PARK) 1f else 0f
        if (params.x == x && params.y == y && params.alpha == alpha) return
        params.x = x
        params.y = y
        params.alpha = alpha
        runCatching { windows.updateViewLayout(root, params) }
    }

    companion object {
        private const val CLUSTER_DISPLAY_ID = 1
        private const val SQUARE_PX = 260
        private const val MARGIN_LEFT_PX = 424
        private const val MARGIN_TOP_PX = 32
        const val BACKGROUND = 0xff0c0c0c.toInt()
        const val TAB_NAVIGATION = 1
        const val TAB_MEDIA = 2
        private const val TAB_POLL_MILLIS = 1_000L

        /** Where a hidden window goes: in place (transparent) or the corner the cluster never shows. */
        fun hiddenPosition(hide: Hide, shownX: Int, shownY: Int): Pair<Int, Int> =
            if (hide == Hide.PARK) 0 to 0 else shownX to shownY

        /** Where the cluster app's square lands on a [width] x [height] display. */
        fun placement(width: Int, height: Int): Placement {
            val side = minOf(SQUARE_PX, width, height).coerceAtLeast(0)
            val x = if (width >= MARGIN_LEFT_PX + side) MARGIN_LEFT_PX + (width - MARGIN_LEFT_PX - side) / 2 else (width - side) / 2
            val y = if (height >= MARGIN_TOP_PX + side) MARGIN_TOP_PX + (height - MARGIN_TOP_PX - side) / 2 else (height - side) / 2
            return Placement(x, y, side)
        }

        /**
         * Puts [content] (built for the cluster display's context) over the square, starting hidden until
         * the page is read, or returns null when there is no cluster display or the window is refused.
         * [log] lines need their own prefix. Main thread.
         */
        fun open(
            context: Context,
            title: String,
            visibleOn: (Int?) -> Boolean,
            log: (String) -> Unit,
            band: Band = Band(),
            hide: Hide = Hide.TRANSPARENT,
            content: (Context) -> View,
        ): Mg4ClusterSquare? {
            val app = context.applicationContext
            val display = app.getSystemService(DisplayManager::class.java)?.getDisplay(CLUSTER_DISPLAY_ID)
            if (display == null) {
                log("no display $CLUSTER_DISPLAY_ID; the cluster square is not available")
                return null
            }
            val size = Point().also {
                @Suppress("DEPRECATION")
                display.getRealSize(it)
            }
            val place = placement(size.x, size.y)
            val displayContext = app.createDisplayContext(display)
            val manager = displayContext.getSystemService(WindowManager::class.java) ?: return null
            val root = content(displayContext)
            val shownX = place.x
            val shownY = place.y + band.top.coerceIn(0, place.side - 1)
            @Suppress("DEPRECATION")
            val params = WindowManager.LayoutParams(
                place.side,
                (band.height ?: place.side).coerceIn(1, place.side),
                WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                // Translucent so a transparent hidden window lets the cluster app's page through.
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // Hidden until the first page read decides.
                val (hiddenX, hiddenY) = hiddenPosition(hide, shownX, shownY)
                x = hiddenX
                y = hiddenY
                alpha = if (hide == Hide.PARK) 1f else 0f
                this.title = title
            }
            try {
                manager.addView(root, params)
            } catch (error: Exception) {
                log("window refused on display ${display.displayId}: ${error.javaClass.simpleName}")
                return null
            }
            log("window on display ${display.displayId} ${size.x}x${size.y}, ${place.side} px square at ${place.x},${place.y}")
            return Mg4ClusterSquare(manager, root, params, visibleOn, log, hide, shownX, shownY).also {
                it.startPolling("mg4-cluster-square")
            }
        }
    }
}
