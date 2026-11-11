package com.shilapi.xcertplay.vehicle

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteGuidanceStateTest {
    @Test
    fun `combines maneuver and route updates`() {
        val state = RouteGuidanceState()
        state.accept(
            RouteGuidanceState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlvs(tlv(0x01, 0, 7), tlv(0x03, 1), tlv(0x08, 0)),
        )

        val change = state.accept(
            RouteGuidanceState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x01, 1), tlv(0x0a, 0, 0, 0, 150), tlv(0x0d, 0, 7)),
        )

        assertEquals(RouteChange.GUIDANCE, change)
        assertEquals(RouteManeuver(distanceMeters = 150, type = 1, drivingSide = 0), state.current())
    }

    @Test
    fun `keeps the driving side`() {
        val state = RouteGuidanceState()
        state.accept(
            RouteGuidanceState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlvs(tlv(0x01, 0, 3), tlv(0x03, 4), tlv(0x08, 1)),
        )
        state.accept(
            RouteGuidanceState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x01, 1), tlv(0x0a, 0, 0, 0, 50), tlv(0x0d, 0, 3)),
        )

        assertEquals(RouteManeuver(distanceMeters = 50, type = 4, drivingSide = 1), state.current())
    }

    @Test
    fun `route end clears guidance`() {
        val state = populatedState()

        val change = state.accept(RouteGuidanceState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x01, 2)))

        assertEquals(RouteChange.CLEAR, change)
        assertNull(state.current())
    }

    @Test
    fun `malformed frame does not alter active guidance`() {
        val state = populatedState()
        val before = state.current()

        val change = state.accept(
            RouteGuidanceState.ROUTE_GUIDANCE_UPDATE,
            byteArrayOf(0, 8, 0, 1, 1),
        )

        assertEquals(RouteChange.NONE, change)
        assertEquals(before, state.current())
    }


    @Test
    fun `carries after-maneuver road and arrival time`() {
        val state = RouteGuidanceState()
        state.accept(
            RouteGuidanceState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlvs(tlv(0x01, 0, 2), tlv(0x03, 2), tlv(0x04, *utf8z("Am Wehr")), tlv(0x08, 0)),
        )
        state.accept(
            RouteGuidanceState.ROUTE_GUIDANCE_UPDATE,
            tlvs(
                tlv(0x01, 1), tlv(0x03, *utf8z("Hauptstraße")), tlv(0x05, 0, 0, 0, 0, 0x68, 0xd5, 0x2a, 0x40),
                tlv(0x0a, 0, 0, 1, 44), tlv(0x0d, 0, 2),
            ),
        )

        val guidance = state.current()!!
        assertEquals("Am Wehr", guidance.road)
        assertEquals(0x68d52a40L, guidance.arrivalEpochSeconds)
        assertEquals(300, guidance.distanceMeters)
    }

    @Test
    fun `brief empty maneuver list keeps guidance and a lasting one hides it without losing maneuvers`() {
        var now = 0L
        val state = populatedState { now }

        assertEquals(RouteChange.NONE, state.accept(RouteGuidanceState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x01, 5), tlv(0x0d))))
        now = 2_000_000_000L
        assertEquals(2, state.current()!!.type) // a blip does not blank the outputs
        now = 3_100_000_000L
        assertNull(state.current())

        // The iPhone lists the same maneuver again without resending its 0x5202 details.
        val resumed = state.accept(
            RouteGuidanceState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x01, 1), tlv(0x0a, 0, 0, 0, 20), tlv(0x0d, 0, 1)),
        )
        assertEquals(RouteChange.GUIDANCE, resumed)
        assertEquals(2, state.current()!!.type)
    }

    @Test
    fun `falls back to current road`() {
        val state = RouteGuidanceState()
        state.accept(RouteGuidanceState.ROUTE_GUIDANCE_MANEUVER_UPDATE, tlvs(tlv(0x01, 0, 0), tlv(0x03, 1)))
        state.accept(
            RouteGuidanceState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x01, 1), tlv(0x03, *utf8z("Hauptstraße")), tlv(0x0d, 0, 0)),
        )
        assertEquals("Hauptstraße", state.current()!!.road)
    }

    @Test
    fun `silent route expires and fresh update restores cached maneuver`() {
        var now = 0L
        val state = populatedState { now }
        now = 30_000_000_000L
        assertNull(state.current())
        state.accept(RouteGuidanceState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x0a, 0, 0, 0, 20)))
        assertEquals(20, state.current()!!.distanceMeters)
    }

    @Test
    fun `clear prevents a keepalive from restoring guidance`() {
        val state = populatedState()
        state.clear()
        assertNull(state.current())
    }

    @Test
    fun `shows the distance CarPlay shows, not the raw metres`() {
        val state = RouteGuidanceState()
        state.accept(RouteGuidanceState.ROUTE_GUIDANCE_MANEUVER_UPDATE, tlvs(tlv(0x01, 0, 1), tlv(0x03, 1), tlv(0x08, 1)))
        state.accept(
            RouteGuidanceState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x01, 1), tlv(0x0a, 0, 0, 2, 25), tlv(0x0b, *utf8z("600")), tlv(0x0c, 1), tlv(0x0d, 0, 1)),
        )
        assertEquals(600, state.current()!!.distanceMeters)
    }

    @Test
    fun `reads kilometres from the display text`() {
        val state = RouteGuidanceState()
        state.accept(RouteGuidanceState.ROUTE_GUIDANCE_MANEUVER_UPDATE, tlvs(tlv(0x01, 0, 1), tlv(0x03, 1), tlv(0x08, 1)))
        // 1.2 km shown, 1,230 m raw.
        state.accept(
            RouteGuidanceState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x01, 1), tlv(0x0a, 0, 0, 4, 206), tlv(0x0b, *utf8z("1.2")), tlv(0x0c, 0), tlv(0x0d, 0, 1)),
        )
        assertEquals(1200, state.current()!!.distanceMeters)
    }

    @Test
    fun `without display text the raw distance is rounded so it does not flicker`() {
        val state = RouteGuidanceState()
        state.accept(RouteGuidanceState.ROUTE_GUIDANCE_MANEUVER_UPDATE, tlvs(tlv(0x01, 0, 1), tlv(0x03, 1), tlv(0x08, 1)))
        state.accept(RouteGuidanceState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x01, 1), tlv(0x0a, 0, 0, 2, 25), tlv(0x0d, 0, 1)))
        val first = state.current()
        state.accept(RouteGuidanceState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x01, 1), tlv(0x0a, 0, 0, 2, 0), tlv(0x0d, 0, 1)))
        // 537 m then 512 m: both show 500 m, so the cluster is not sent a new value.
        assertEquals(500, first!!.distanceMeters)
        assertEquals(first, state.current())
    }

    @Test
    fun `a display value far from the raw distance is ignored`() {
        // Protects against a wrong units code: 600 read as kilometres would be 600,000 m.
        assertEquals(null, ManeuverDistance.fromPhone(537, "600", 0))
        assertEquals(null, ManeuverDistance.fromPhone(537, "abc", 1))
        assertEquals(null, ManeuverDistance.fromPhone(537, "600", 2))
        assertEquals(600, ManeuverDistance.fromPhone(537, "600 m", 1))
    }

    @Test
    fun `fallback rounding steps`() {
        assertEquals(500, ManeuverDistance.rounded(537))
        assertEquals(500, ManeuverDistance.rounded(512))
        assertEquals(600, ManeuverDistance.rounded(551))
        assertEquals(350, ManeuverDistance.rounded(337))
        assertEquals(150, ManeuverDistance.rounded(150))
        assertEquals(40, ManeuverDistance.rounded(43))
    }

    private fun utf8z(value: String): IntArray =
        (value.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xff } + 0).toIntArray()

    private fun populatedState(nanoTime: () -> Long = System::nanoTime): RouteGuidanceState =
        RouteGuidanceState(nanoTime).also { state ->
        state.accept(
            RouteGuidanceState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlvs(tlv(0x01, 0, 1), tlv(0x03, 2), tlv(0x08, 0)),
        )
        state.accept(
            RouteGuidanceState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x01, 1), tlv(0x0a, 0, 0, 0, 25), tlv(0x0d, 0, 1)),
        )
    }

    private fun tlv(type: Int, vararg value: Int): ByteArray = ByteArrayOutputStream().apply {
        val length = value.size + 4
        write(length ushr 8)
        write(length)
        write(type ushr 8)
        write(type)
        value.forEach(::write)
    }.toByteArray()

    private fun tlvs(vararg values: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        values.forEach(::write)
    }.toByteArray()
}
