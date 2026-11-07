package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Mg4ClusterMapTest {
    @Test
    fun squareSitsCentredInWhatTheMarginsLeave() {
        // 800x480 display: 424 + (800 - 424 - 260) / 2, 32 + (480 - 32 - 260) / 2.
        assertEquals(Mg4ClusterMap.Placement(482, 126, 260), Mg4ClusterMap.placement(800, 480))
    }

    @Test
    fun smallDisplayCentresAndShrinksTheSquare() {
        assertEquals(Mg4ClusterMap.Placement(70, 0, 200), Mg4ClusterMap.placement(340, 200))
    }

    @Test
    fun mapShowsOnTheNavigationPageOrWhenThePageIsUnknown() {
        assertTrue(Mg4ClusterMap.visibleOn(1))
        assertTrue(Mg4ClusterMap.visibleOn(null))
        for (tab in listOf(0, 2, 3, 4, 5, 6)) assertFalse(Mg4ClusterMap.visibleOn(tab))
    }
}
