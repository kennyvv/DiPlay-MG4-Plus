package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lane guidance is asked for only when the dashboard lanes switch is on; off is the old handshake. */
class Iap2LaneGuidanceIdentificationTest {
    @Test
    fun offLeavesTheRouteGuidanceComponentAndMessagesAsBefore() {
        val parameters = parameters(laneGuidance = false)
        val component = Iap2ParameterList.parse(parameters.first(30)!!.payload)
        assertNull(component.first(7))
        assertNull(component.first(8))
        assertFalse(LANE_GUIDANCE_INFO_UPDATE in u16Values(parameters.first(7)!!.payload))
    }

    @Test
    fun onDeclaresLaneStorageAndAcceptsLaneUpdates() {
        val parameters = parameters(laneGuidance = true)
        val component = Iap2ParameterList.parse(parameters.first(30)!!.payload)
        assertEquals(listOf(64), u16Values(component.first(7)!!.payload))
        assertEquals(listOf(8), u16Values(component.first(8)!!.payload))
        assertTrue(LANE_GUIDANCE_INFO_UPDATE in u16Values(parameters.first(7)!!.payload))
    }

    private fun parameters(laneGuidance: Boolean) = Iap2ParameterList.parse(
        Iap2IdentificationClient.identificationInformation(
            Iap2IdentificationConfig(
                name = "xcertplay",
                modelIdentifier = "xcertplay",
                manufacturer = "xcertplay",
                serialNumber = "xcertplay",
                firmwareVersion = "1.0.0",
                hardwareVersion = "1.0",
                carPlayUsbInterfaceNumber = 3,
                laneGuidanceEnabled = laneGuidance,
            ),
        ).payload,
    )

    private fun u16Values(bytes: ByteArray): List<Int> =
        List(bytes.size / 2) { index ->
            ((bytes[index * 2].toInt() and 0xff) shl 8) or (bytes[index * 2 + 1].toInt() and 0xff)
        }

    private companion object {
        const val LANE_GUIDANCE_INFO_UPDATE = 0x5204
    }
}
