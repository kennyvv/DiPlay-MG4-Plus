package com.shilapi.xcertplay

import com.shilapi.xcertplay.Mg4ClusterLaneStrip.Shade
import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Mg4ClusterLaneStripTest {
    private fun lane(status: Int, vararg angles: Int, highlight: Int? = null) =
        LaneGuidanceLane(0, status, angles.toList(), highlight)

    @Test
    fun theWayToGoIsLitTheRestGreyAndUnusableLanesDim() {
        val straightAndRight = lane(2, 0, highlight = 90)
        assertEquals(Shade.LIT, Mg4ClusterLaneStrip.shade(straightAndRight, 90))
        assertEquals(Shade.GREY, Mg4ClusterLaneStrip.shade(straightAndRight, 0))
        assertEquals(Shade.LIT, Mg4ClusterLaneStrip.shade(lane(1, -45), -45))
        assertEquals(Shade.DIM, Mg4ClusterLaneStrip.shade(lane(0, -90), -90))
    }

    @Test
    fun aLaneDrawsItsOwnAnglesAndTheHighlightOnce() {
        assertEquals(listOf(0, 90), Mg4ClusterLaneStrip.arrows(lane(2, 0, highlight = 90)))
        assertEquals(listOf(-30, 0), Mg4ClusterLaneStrip.arrows(lane(2, -30, 0, highlight = 0)))
        assertEquals(listOf(0), Mg4ClusterLaneStrip.arrows(lane(0)))
    }

    @Test
    fun lanesUseTheCarsIconWidthUpToThirtyFourPixels() {
        assertEquals(34, Mg4ClusterLaneStrip.laneWidth(3, 260))
        assertEquals(26, Mg4ClusterLaneStrip.laneWidth(10, 260))
        assertEquals(0, Mg4ClusterLaneStrip.laneWidth(0, 260))
    }

    @Test
    fun theBandShowsOnTheNavigationPageWithLanesAndNeverOverTheMap() {
        assertTrue(Mg4ClusterLaneStrip.visibleOn(1, hasLanes = true, mapShown = false))
        assertTrue(Mg4ClusterLaneStrip.visibleOn(null, hasLanes = true, mapShown = false))
        assertFalse(Mg4ClusterLaneStrip.visibleOn(1, hasLanes = false, mapShown = false))
        assertFalse(Mg4ClusterLaneStrip.visibleOn(1, hasLanes = true, mapShown = true))
        assertFalse(Mg4ClusterLaneStrip.visibleOn(2, hasLanes = true, mapShown = false))
    }
}
