package com.shilapi.xcertplay.vehicle

import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import com.shilapi.xcertplay.vehicle.Mg4ClusterLanes.Arrow
import org.junit.Assert.assertEquals
import org.junit.Test

class Mg4ClusterLanesTest {
    private fun lane(index: Int, status: Int, vararg angles: Int, highlight: Int? = null) =
        LaneGuidanceLane(index, status, angles.toList(), highlight)

    @Test
    fun anglesBecomeArrows() {
        assertEquals(Arrow.STRAIGHT, Mg4ClusterLanes.arrow(0, 0))
        assertEquals(Arrow.STRAIGHT, Mg4ClusterLanes.arrow(25, 0))
        assertEquals(Arrow.LEFT, Mg4ClusterLanes.arrow(-90, 0))
        assertEquals(Arrow.RIGHT, Mg4ClusterLanes.arrow(45, 0))
        assertEquals(Arrow.LEFT_U_TURN, Mg4ClusterLanes.arrow(-160, 0))
        assertEquals(Arrow.RIGHT_U_TURN, Mg4ClusterLanes.arrow(160, 0))
    }

    @Test
    fun aFullTurnBackFollowsTheDrivingSide() {
        assertEquals(Arrow.LEFT_U_TURN, Mg4ClusterLanes.arrow(180, 0))
        assertEquals(Arrow.RIGHT_U_TURN, Mg4ClusterLanes.arrow(180, 1))
    }

    @Test
    fun theUpstreamCaptureBecomesGreyLeftGreyStraightAndHighlightedRight() {
        // Lane 2 is a straight lane whose right turn is highlighted: straight-and-right, right lit.
        val lanes = listOf(lane(0, 0, -90), lane(1, 0, 0), lane(2, 2, 0, highlight = 90))
        assertEquals("3,1,16", Mg4ClusterLanes.encode(lanes, 0))
    }

    @Test
    fun singleArrowsAreGreyedOrHighlightedByStatus() {
        assertEquals(1, Mg4ClusterLanes.code(lane(0, 0, 0), 0))
        assertEquals(2, Mg4ClusterLanes.code(lane(0, 1, 0), 0))
        assertEquals(2, Mg4ClusterLanes.code(lane(0, 2, 0), 0))
        assertEquals(6, Mg4ClusterLanes.code(lane(0, 2, 90), 0))
    }

    @Test
    fun combinedLanesHighlightTheArrowToTake() {
        assertEquals(27, Mg4ClusterLanes.code(lane(0, 0, -90, 0, 90), 0))
        assertEquals(29, Mg4ClusterLanes.code(lane(0, 2, -90, 0, 90, highlight = -90), 0))
        assertEquals(31, Mg4ClusterLanes.code(lane(0, 0, -90, 90), 0))
        assertEquals(12, Mg4ClusterLanes.code(lane(0, 2, 0, -90, highlight = 0), 0))
    }

    @Test
    fun aLaneTheClusterCannotDrawKeepsItsHighlightedArrow() {
        // Left, straight and a left U-turn: no such icon, so the highlighted left arrow stands in.
        assertEquals(4, Mg4ClusterLanes.code(lane(0, 2, -160, -90, 0, highlight = -90), 0))
    }

    @Test
    fun lanesGoLeftToRightAndAnEmptyStripClears() {
        assertEquals("4,1", Mg4ClusterLanes.encode(listOf(lane(1, 0, 0), lane(0, 2, -90)), 0))
        assertEquals("", Mg4ClusterLanes.encode(emptyList(), 0))
    }
}
