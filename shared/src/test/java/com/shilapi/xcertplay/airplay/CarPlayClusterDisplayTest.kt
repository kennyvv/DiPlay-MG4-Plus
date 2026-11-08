package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayClusterDisplayTest {
    private val config = AirPlayConfig(
        deviceName = "test",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:02",
        sourceVersion = "366.0",
        main = AirPlayDisplayConfig(widthPixels = 1778, heightPixels = 720),
    )

    @Test
    fun clusterIsASquareWithoutInputAndWithItsContentUrl() {
        val cluster = CarPlayClusterDisplay.config(CarPlayClusterDisplay.Content.TURN_CARD)
        assertEquals(CarPlayClusterDisplay.SIDE_PIXELS, cluster.widthPixels)
        assertEquals(CarPlayClusterDisplay.SIDE_PIXELS, cluster.heightPixels)
        assertEquals(0, cluster.widthPixels % 8)
        assertTrue(cluster.widthPixels >= 260)
        assertEquals(0, cluster.primaryInputDevice)
        assertEquals(0, cluster.features)
        assertEquals("maps:/car/instrumentcluster/instructioncard", cluster.initialUrl)
    }

    @Test
    fun clusterAddsTheAltScreenOnlyWhenRequested() {
        assertEquals(1, (AirPlayInfoPlist.build(config)["displays"] as List<*>).size)
        assertFalse("altScreen" in setupEnabledFeatures(config, null))

        val withCluster = config.copy(cluster = CarPlayClusterDisplay.config(CarPlayClusterDisplay.Content.MAP))
        val displays = AirPlayInfoPlist.build(withCluster)["displays"] as List<*>
        val alt = displays[1] as Map<*, *>
        assertEquals(111, alt["type"])
        assertEquals(AirPlayInfoPlist.ALT_UUID, alt["uuid"])
        assertEquals("maps:/car/instrumentcluster", alt["initialURL"])
        assertEquals(30, alt["maxFPS"])
        assertTrue("altScreen" in setupEnabledFeatures(withCluster, null))
    }

    @Test
    fun aLargerStreamDrawsTheMapSmaller() {
        for (size in CarPlayClusterDisplay.Size.entries) {
            val cluster = CarPlayClusterDisplay.config(CarPlayClusterDisplay.Content.MAP, size)
            assertEquals(size.sidePixels, cluster.widthPixels)
            assertEquals(size.sidePixels, cluster.heightPixels)
            assertEquals(0, cluster.widthPixels % 8)
        }
        assertTrue(CarPlayClusterDisplay.Size.SMALL.sidePixels > CarPlayClusterDisplay.Size.MEDIUM.sidePixels)
        assertTrue(CarPlayClusterDisplay.Size.MEDIUM.sidePixels > CarPlayClusterDisplay.Size.LARGE.sidePixels)
    }
}
