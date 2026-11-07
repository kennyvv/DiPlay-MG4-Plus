package com.shilapi.xcertplay.iap2.message

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A real 0x5204 payload captured on a car by upstream DiPlay (Apple Maps, three lanes, the third
 * recommended):
 *
 * <pre>
 * id 0 component 42
 * id 1 lane guidance index 0
 * id 2 lane 0: index 0, status 0, angle -90
 * id 2 lane 1: index 1, status 0, angle 0
 * id 2 lane 2: index 2, status 2, angle 0, angle-highlight 90
 * </pre>
 */
class Iap2LaneGuidanceTest {

    private val sample = hex(
        "00 06 00 00 00 2a" +
            " 00 06 00 01 00 00" +
            " 00 15 00 02 00 06 00 00 00 00 00 05 00 01 00 00 06 00 02 ff a6" +
            " 00 15 00 02 00 06 00 00 00 01 00 05 00 01 00 00 06 00 02 00 00" +
            " 00 1b 00 02 00 06 00 00 00 02 00 05 00 01 02 00 06 00 02 00 00" +
            " 00 06 00 03 00 5a",
    )

    @Test
    fun theCapturedPayloadHasThreeLanes() {
        val lanes = Iap2LaneGuidance.parse(sample)
        assertEquals(listOf(0, 1, 2), lanes.map { it.index })
    }

    @Test
    fun anglesAreSignedDegrees() {
        val lanes = Iap2LaneGuidance.parse(sample)
        assertEquals(listOf(listOf(-90), listOf(0), listOf(0)), lanes.map { it.angles })
    }

    @Test
    fun theThirdLaneIsRecommendedAndHighlighted() {
        val lanes = Iap2LaneGuidance.parse(sample)
        assertEquals(listOf(0, 0, 2), lanes.map { it.status })
        assertEquals(true, lanes[2].recommended)
        assertEquals(90, lanes[2].highlightAngle)
        assertEquals(null, lanes[0].highlightAngle)
    }

    @Test
    fun aLaneWithSeveralArrowsKeepsThemAll() {
        // One lane: index 0, status 1, angles 0 and -90.
        val lane = hex("00 1b 00 02 00 06 00 00 00 00 00 05 00 01 01 00 06 00 02 00 00 00 06 00 02 ff a6")
        assertEquals(listOf(0, -90), Iap2LaneGuidance.parse(lane).single().angles)
    }

    @Test
    fun aMalformedPayloadYieldsNoLanes() {
        assertEquals(emptyList<LaneGuidanceLane>(), Iap2LaneGuidance.parse(byteArrayOf(0, 1, 2)))
    }

    private fun hex(text: String): ByteArray =
        text.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
}
