package com.shilapi.xcertplay.vehicle

import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane

/**
 * CarPlay lane guidance as the MG4 cluster's navigation card draws it.
 *
 * Read from the SWI68 cluster app (`clusterprojection`, `LanePattern` and `NavigationMapper`): the
 * adapter's `IMapService.vehicleLaneInfoChanged(String)` takes one lane code per lane, left to
 * right, separated by commas. Single arrows come greyed or highlighted; lanes with two or three
 * arrows have a plain form and one form per highlighted arrow. A lane the cluster has no icon for
 * keeps its highlighted arrow, or its first.
 */
internal object Mg4ClusterLanes {
    enum class Arrow { STRAIGHT, LEFT, RIGHT, LEFT_U_TURN, RIGHT_U_TURN }

    private val SINGLE = mapOf(
        Arrow.STRAIGHT to (1 to 2),
        Arrow.LEFT to (3 to 4),
        Arrow.RIGHT to (5 to 6),
        Arrow.LEFT_U_TURN to (7 to 8),
        Arrow.RIGHT_U_TURN to (9 to 10),
    )

    /** Plain code, then the code with each arrow highlighted. */
    private class Combined(val plain: Int, val highlighted: Map<Arrow, Int>)

    private val COMBINED = mapOf(
        setOf(Arrow.STRAIGHT, Arrow.LEFT) to Combined(11, mapOf(Arrow.STRAIGHT to 12, Arrow.LEFT to 13)),
        setOf(Arrow.STRAIGHT, Arrow.RIGHT) to Combined(14, mapOf(Arrow.STRAIGHT to 15, Arrow.RIGHT to 16)),
        setOf(Arrow.LEFT, Arrow.LEFT_U_TURN) to Combined(17, mapOf(Arrow.LEFT to 18, Arrow.LEFT_U_TURN to 19)),
        setOf(Arrow.RIGHT, Arrow.RIGHT_U_TURN) to Combined(20, mapOf(Arrow.RIGHT to 21, Arrow.RIGHT_U_TURN to 22)),
        // 25 (`continueAndLeftUTurn_Left`) is left out: the U-turn lane has no plain left arrow.
        setOf(Arrow.STRAIGHT, Arrow.LEFT_U_TURN) to
            Combined(23, mapOf(Arrow.STRAIGHT to 24, Arrow.LEFT_U_TURN to 26)),
        setOf(Arrow.STRAIGHT, Arrow.LEFT, Arrow.RIGHT) to
            Combined(27, mapOf(Arrow.STRAIGHT to 28, Arrow.LEFT to 29, Arrow.RIGHT to 30)),
        setOf(Arrow.LEFT, Arrow.RIGHT) to Combined(31, mapOf(Arrow.LEFT to 32, Arrow.RIGHT to 33)),
    )

    /**
     * Apple's angle as an arrow: 0 straight, positive right, negative left. A full turn back (±180)
     * is a U-turn across the oncoming traffic, so its side follows the [drivingSide] (1 = left-hand
     * traffic, as in Apple's maneuver updates).
     */
    fun arrow(angle: Int, drivingSide: Int): Arrow = when {
        angle >= 180 || angle <= -180 -> if (drivingSide == 1) Arrow.RIGHT_U_TURN else Arrow.LEFT_U_TURN
        angle <= -150 -> Arrow.LEFT_U_TURN
        angle >= 150 -> Arrow.RIGHT_U_TURN
        angle < -30 -> Arrow.LEFT
        angle > 30 -> Arrow.RIGHT
        else -> Arrow.STRAIGHT
    }

    /** The cluster's code for one lane. A lane the iPhone marks good (1) or preferred (2) is highlighted. */
    fun code(lane: LaneGuidanceLane, drivingSide: Int): Int {
        val highlight = lane.highlightAngle?.let { arrow(it, drivingSide) }
        val arrows = (lane.angles.map { arrow(it, drivingSide) } + listOfNotNull(highlight)).toSet()
            .ifEmpty { setOf(Arrow.STRAIGHT) }
        val usable = lane.status == 1 || lane.status == 2
        val chosen = highlight ?: arrows.first()
        COMBINED[arrows]?.let { combined ->
            return if (usable) combined.highlighted[chosen] ?: combined.plain else combined.plain
        }
        val (greyed, highlighted) = SINGLE.getValue(if (arrows.size == 1) arrows.first() else chosen)
        return if (usable) highlighted else greyed
    }

    /** The string for `vehicleLaneInfoChanged`; empty clears the lanes. */
    fun encode(lanes: List<LaneGuidanceLane>, drivingSide: Int): String =
        lanes.sortedBy { it.index }.joinToString(",") { code(it, drivingSide).toString() }
}
