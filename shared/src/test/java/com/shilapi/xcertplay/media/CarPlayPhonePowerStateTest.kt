package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlayPhonePowerStateTest {
    private fun update(block: com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder.() -> Unit) =
        Iap2Messages.buildRaw(CarPlayPhonePowerState.POWER_UPDATE, block)

    @Test
    fun readsLevelChargingAndCharger() {
        val state = CarPlayPhonePowerState()
        // As an iPhone sends it: 80 % is 0x0050.
        val power = state.accept(update { bool(4, true); u8(5, 1); u16(6, 80) })
        assertEquals(CarPlayPhonePower(80, CarPlayPhonePower.Charging.CHARGING, true), power)
    }

    @Test
    fun updatesKeepWhatTheyLeaveOutAndRepeatsAreQuiet() {
        val state = CarPlayPhonePowerState()
        state.accept(update { u8(5, 0); u16(6, 100) })
        assertEquals(CarPlayPhonePower(100, CarPlayPhonePower.Charging.NOT_CHARGING, null), state.current())
        assertEquals(CarPlayPhonePower(100, CarPlayPhonePower.Charging.CHARGED, null), state.accept(update { u8(5, 2) }))
        assertNull(state.accept(update { u8(5, 2) }))
    }

    @Test
    fun otherMessagesAndSessionEnd() {
        val state = CarPlayPhonePowerState()
        assertNull(state.accept(Iap2Messages.buildRaw(0x5001) { u16(6, 100) }))
        assertNull(state.current())
        state.accept(update { u16(6, 0) })
        assertEquals(0, state.current()?.batteryPercent)
        state.clear()
        assertNull(state.current())
    }

    @Test
    fun percentRounds() {
        assertEquals(0, CarPlayPhonePowerState.percent(0))
        assertEquals(80, CarPlayPhonePowerState.percent(80))
        assertEquals(100, CarPlayPhonePowerState.percent(100))
        // The wider iAP2 scale, should an iPhone use it.
        assertEquals(50, CarPlayPhonePowerState.percent(32768))
        assertEquals(100, CarPlayPhonePowerState.percent(65535))
    }
}
