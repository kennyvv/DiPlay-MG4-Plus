package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Experimental: CarPlay's lanes drawn by MG4CPlay on the MG4 cluster's navigation card.
 *
 * The car's own lane icons ([com.shilapi.xcertplay.vehicle.Mg4ClusterLanes]) know five directions and
 * seven lane combinations. This draws every arrow at the angle the iPhone sends, in a transparent
 * band over the card's lane row: read from the SWI68 `fragment_navi` layout, the turn arrow ends
 * 136 px down the 260 px square, the lane row (27 x 28 px icons) starts at 152 and the status text
 * at 187. The band runs from 144 to 184, so it covers neither. Shown on the Navigation page while
 * lanes are known, and never over the dashboard map.
 */
internal object Mg4ClusterLaneStrip {
    private val BAND = Mg4ClusterSquare.Band(top = 144, height = 40)

    fun visibleOn(tab: Int?, hasLanes: Boolean, mapShown: Boolean): Boolean =
        hasLanes && !mapShown && (tab == null || tab == Mg4ClusterSquare.TAB_NAVIGATION)

    private var square: Mg4ClusterSquare? = null
    private var view: LaneView? = null
    private var lanes: List<LaneGuidanceLane> = emptyList()
    private var log: (String) -> Unit = {}

    val isShown: Boolean get() = square != null

    /** Puts the lane band on the cluster for a CarPlay session. Main thread. */
    fun attach(context: Context, log: (String) -> Unit) {
        this.log = log
        if (square != null) return
        square = Mg4ClusterSquare.open(
            context,
            "MG4CPlay cluster lanes",
            { tab -> visibleOn(tab, lanes.isNotEmpty(), Mg4ClusterMap.isShown) },
            { this.log("Cluster lanes: $it") },
            band = BAND,
            translucent = true,
        ) { display -> LaneView(display).also { view = it } }
    }

    /** New lanes from the iPhone (empty: none) and Apple's driving side (1 = left-hand traffic). Main thread. */
    fun update(next: List<LaneGuidanceLane>, drivingSide: Int) {
        if (next == lanes && view?.drivingSide == drivingSide) return
        lanes = next
        view?.let {
            it.lanes = next
            it.drivingSide = drivingSide
            it.invalidate()
        }
        square?.refresh()
    }

    /** Takes the band off the cluster. Main thread. */
    fun detach() {
        if (square != null) log("Cluster lanes: window removed")
        square?.remove()
        square = null
        view = null
        lanes = emptyList()
    }

    /** The colour of one arrow: the way to go white, other usable arrows grey, unusable lanes dim. */
    fun shade(lane: LaneGuidanceLane, angle: Int): Shade {
        val usable = lane.status == 1 || lane.status == 2
        return when {
            !usable -> Shade.DIM
            lane.highlightAngle == null || lane.highlightAngle == angle -> Shade.LIT
            else -> Shade.GREY
        }
    }

    // Plain ARGB values, so the shading rules load without Android's Color.
    enum class Shade(val color: Int) {
        LIT(0xffffffff.toInt()),
        GREY(0xff8a8a8a.toInt()),
        DIM(0xff484848.toInt()),
    }

    /** Every arrow on a lane: its own angles and the highlighted one, each once. */
    fun arrows(lane: LaneGuidanceLane): List<Int> =
        (lane.angles + listOfNotNull(lane.highlightAngle)).distinct().ifEmpty { listOf(0) }

    /** Width of one lane in a band [width] px wide: the car's 27 px icons, up to 34 px. */
    fun laneWidth(count: Int, width: Int): Int = if (count <= 0) 0 else minOf(34, width / count)

    private class LaneView(context: Context) : View(context) {
        var lanes: List<LaneGuidanceLane> = emptyList()
        var drivingSide = 0
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val divider = Paint().apply { color = Color.rgb(0x30, 0x30, 0x30); strokeWidth = 1f }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            if (lanes.isEmpty()) return
            val each = laneWidth(lanes.size, width)
            var left = (width - each * lanes.size) / 2f
            lanes.forEachIndexed { index, lane ->
                if (index > 0) canvas.drawLine(left, height * 0.2f, left, height * 0.8f, divider)
                // Grey arrows first, so the way to go is drawn on top.
                arrows(lane).sortedBy { shade(lane, it) == Shade.LIT }.forEach { angle ->
                    stroke.color = shade(lane, angle).color
                    drawArrow(canvas, left + each / 2f, angle)
                }
                left += each
            }
        }

        // A stem up from the bottom, bent to [angle]; a U-turn hooks back down on its side.
        private fun drawArrow(canvas: Canvas, x: Float, angle: Int) {
            val bottom = height - 4f
            val bend = height * 0.5f
            val reach = height * 0.36f
            path.reset()
            path.moveTo(x, bottom)
            path.lineTo(x, bend)
            val tipX: Float
            val tipY: Float
            val headAngle: Double
            if (abs(angle) >= 150) {
                val side = when {
                    angle >= 180 || angle <= -180 -> if (drivingSide == 1) 1f else -1f
                    angle > 0 -> 1f
                    else -> -1f
                }
                val gap = reach * 0.55f
                path.lineTo(x, 6f)
                path.quadTo(x, 2f, x + side * gap / 2f, 2f)
                path.quadTo(x + side * gap, 2f, x + side * gap, 6f)
                tipX = x + side * gap
                tipY = bend
                path.lineTo(tipX, tipY)
                headAngle = Math.PI
            } else {
                val radians = Math.toRadians(angle.toDouble())
                tipX = x + (sin(radians) * reach).toFloat()
                tipY = bend - (cos(radians) * reach).toFloat()
                path.lineTo(tipX, tipY)
                headAngle = radians
            }
            canvas.drawPath(path, stroke)
            // Arrowhead: two short strokes back from the tip, 35 degrees either side.
            val head = 6.0
            for (spread in listOf(-0.6, 0.6)) {
                val back = headAngle + Math.PI + spread
                canvas.drawLine(tipX, tipY, tipX + (sin(back) * head).toFloat(), tipY - (cos(back) * head).toFloat(), stroke)
            }
        }
    }
}
