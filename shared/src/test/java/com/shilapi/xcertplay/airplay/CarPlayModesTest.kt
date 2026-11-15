package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayModesTest {
    @Test
    fun takeCarriesPriorityAndConstraintsAsTheStockAppSendsThem() {
        val command = CarPlayModes.command(listOf(CarPlayModes.CAR_TAKES_SCREEN), "CarPlay hidden")
        assertEquals("changeModes", command["type"])
        val params = command["params"] as Map<*, *>
        assertEquals("CarPlay hidden", params["reasonStr"])
        val resource = (params["resources"] as List<*>).single() as Map<*, *>
        assertEquals(
            mapOf(
                "resourceID" to 1L, "transferType" to 1L, "transferPriority" to 500L,
                "takeConstraint" to 500L, "borrowConstraint" to 100L,
            ),
            resource,
        )
    }

    @Test
    fun borrowUsesUnborrowConstraintAndGivingBackCarriesOnlyTheType() {
        val borrow = CarPlayModes.CAR_BORROWS_AUDIO.toMap()
        assertEquals(3L, borrow["transferType"])
        assertEquals(100L, borrow["unborrowConstraint"])
        assertFalse(borrow.containsKey("borrowConstraint"))
        assertEquals(
            mapOf("resourceID" to 2L, "transferType" to 2L),
            CarPlayModes.Change(CarPlayModes.RESOURCE_MAIN_AUDIO, CarPlayModes.TRANSFER_UNTAKE).toMap(),
        )
    }

    @Test
    fun parsesModesChanged() {
        val state = CarPlayModes.parse(
            mapOf(
                "resources" to listOf(
                    mapOf("resourceID" to 1L, "entity" to 1L),
                    mapOf("resourceID" to 2L, "entity" to 2L),
                ),
                "appStates" to listOf(
                    mapOf("appStateID" to 1L, "entity" to 1L, "speechMode" to 1L),
                    mapOf("appStateID" to 2L, "entity" to 0L),
                    mapOf("appStateID" to 3L, "entity" to 1L),
                ),
            ),
        )
        assertEquals(CarPlayModes.State(1, 2, 1, 1, 0, 1), state)
        assertEquals(false, state.carPlayHasAudio)
        assertTrue(state.navigating)
        assertTrue(state.siriActive)
        assertFalse(state.onCall)
        assertEquals(CarPlayModes.State(), CarPlayModes.parse(emptyMap()))
    }

    @Test
    fun handoverGivesBackWhatTheCarHolds() {
        val handover = CarPlayModeHandover()
        assertNull(handover.release(CarPlayModes.RESOURCE_MAIN_AUDIO))
        assertEquals(CarPlayModes.CAR_BORROWS_AUDIO, handover.hold(CarPlayModes.CAR_BORROWS_AUDIO))
        assertNull(handover.hold(CarPlayModes.CAR_BORROWS_AUDIO))
        // A lasting loss after a short one upgrades to a take; a later borrow does not weaken it.
        assertEquals(CarPlayModes.CAR_TAKES_AUDIO, handover.hold(CarPlayModes.CAR_TAKES_AUDIO))
        assertNull(handover.hold(CarPlayModes.CAR_BORROWS_AUDIO))
        assertEquals(CarPlayModes.TRANSFER_UNTAKE, handover.release(CarPlayModes.RESOURCE_MAIN_AUDIO)?.transferType)
        assertNull(handover.release(CarPlayModes.RESOURCE_MAIN_AUDIO))

        handover.hold(CarPlayModes.CAR_BORROWS_AUDIO)
        assertEquals(CarPlayModes.TRANSFER_UNBORROW, handover.release(CarPlayModes.RESOURCE_MAIN_AUDIO)?.transferType)
    }

    @Test
    fun theIphoneTakingBackClearsTheHold() {
        val handover = CarPlayModeHandover()
        handover.hold(CarPlayModes.CAR_TAKES_AUDIO)
        handover.hold(CarPlayModes.CAR_TAKES_SCREEN)
        handover.onState(CarPlayModes.State(screen = CarPlayModes.ENTITY_ACCESSORY, mainAudio = CarPlayModes.ENTITY_CONTROLLER))
        assertFalse(handover.holds(CarPlayModes.RESOURCE_MAIN_AUDIO))
        assertTrue(handover.holds(CarPlayModes.RESOURCE_SCREEN))
    }
}
