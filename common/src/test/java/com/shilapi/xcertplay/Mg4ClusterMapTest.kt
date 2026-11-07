package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Mg4ClusterMapTest {
    @Test
    fun squareSitsCentredInWhatTheMarginsLeave() {
        // 800x480 display: 424 + (800 - 424 - 260) / 2, 32 + (480 - 32 - 260) / 2.
        assertEquals(Mg4ClusterSquare.Placement(482, 126, 260), Mg4ClusterSquare.placement(800, 480))
    }

    @Test
    fun smallDisplayCentresAndShrinksTheSquare() {
        assertEquals(Mg4ClusterSquare.Placement(70, 0, 200), Mg4ClusterSquare.placement(340, 200))
    }

    @Test
    fun mapShowsOnTheNavigationPageOrWhenThePageIsUnknown() {
        assertTrue(Mg4ClusterMap.visibleOn(1))
        assertTrue(Mg4ClusterMap.visibleOn(null))
        for (tab in listOf(0, 2, 3, 4, 5, 6)) assertFalse(Mg4ClusterMap.visibleOn(tab))
    }

    @Test
    fun songShowsOnTheMusicPageWhileASongIsKnown() {
        assertTrue(Mg4ClusterSong.visibleOn(2, hasSong = true, mapShown = true))
        assertFalse(Mg4ClusterSong.visibleOn(2, hasSong = false, mapShown = false))
        for (tab in listOf(0, 1, 3, 4, 5, 6)) assertFalse(Mg4ClusterSong.visibleOn(tab, hasSong = true, mapShown = false))
    }

    @Test
    fun anUnknownPageGivesTheSquareToTheMapWhenItIsOn() {
        assertTrue(Mg4ClusterSong.visibleOn(null, hasSong = true, mapShown = false))
        assertFalse(Mg4ClusterSong.visibleOn(null, hasSong = true, mapShown = true))
    }
}
