package com.shilapi.xcertplay

import com.shilapi.xcertplay.CarPlayWheelControl.Command
import com.shilapi.xcertplay.CarPlayWheelControl.Companion.CENTRE
import com.shilapi.xcertplay.CarPlayWheelControl.Companion.DOWN
import com.shilapi.xcertplay.CarPlayWheelControl.Companion.LEFT
import com.shilapi.xcertplay.CarPlayWheelControl.Companion.RIGHT
import com.shilapi.xcertplay.CarPlayWheelControl.Companion.UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayWheelControlTest {
    private fun CarPlayWheelControl.press(code: Int, onScreen: Boolean = true) =
        onKey(code, down = true, repeat = 0, carPlayOnScreen = onScreen) to onKey(code, down = false, repeat = 0, carPlayOnScreen = onScreen)

    private fun CarPlayWheelControl.hold(code: Int = CENTRE): CarPlayWheelControl.Result {
        assertTrue(onKey(code, down = true, repeat = 0, carPlayOnScreen = true).startLongTimer)
        val long = onLongPress()
        assertTrue(onKey(code, down = false, repeat = 0, carPlayOnScreen = true).consume)
        return long
    }

    @Test
    fun outOfCarPlayModeTheDirectionsPassToTheCar() {
        val control = CarPlayWheelControl()
        for (code in listOf(UP, DOWN, LEFT, RIGHT)) {
            val (down, up) = control.press(code)
            assertFalse(down.consume)
            assertFalse(up.consume)
        }
    }

    @Test
    fun aShortCentreOutOfCarPlayModeGoesBackToTheCar() {
        val control = CarPlayWheelControl()
        val (down, up) = control.press(CENTRE)
        assertTrue(down.consume)
        assertTrue(down.startLongTimer)
        assertTrue(up.replayCentre)
        assertFalse(control.active)
    }

    @Test
    fun holdingTheCentreSwitchesCarPlayModeOnAndOff() {
        val control = CarPlayWheelControl()
        assertEquals(true, control.hold().modeChanged)
        assertTrue(control.active)
        assertEquals(false, control.hold().modeChanged)
        assertFalse(control.active)
    }

    @Test
    fun inCarPlayModeTheJoystickDrivesCarPlay() {
        val control = CarPlayWheelControl()
        control.hold()
        assertEquals(Command.UP, control.press(UP).first.command)
        assertEquals(Command.DOWN, control.press(DOWN).first.command)
        assertEquals(Command.LEFT, control.press(LEFT).first.command)
        assertEquals(Command.RIGHT, control.press(RIGHT).first.command)
        val (down, up) = control.press(UP)
        assertTrue(down.consume)
        assertTrue(up.consume)
        assertNull(up.command)
        assertEquals(Command.SELECT, control.press(CENTRE).second.command)
    }

    @Test
    fun aHeldDirectionMovesOnce() {
        val control = CarPlayWheelControl()
        control.hold()
        assertEquals(Command.DOWN, control.onKey(DOWN, down = true, repeat = 0, carPlayOnScreen = true).command)
        val repeat = control.onKey(DOWN, down = true, repeat = 1, carPlayOnScreen = true)
        assertTrue(repeat.consume)
        assertNull(repeat.command)
    }

    @Test
    fun leavingTheCarPlayScreenEndsTheModeAndPassesKeys() {
        val control = CarPlayWheelControl()
        control.hold()
        val away = control.onKey(UP, down = true, repeat = 0, carPlayOnScreen = false)
        assertFalse(away.consume)
        assertEquals(false, away.modeChanged)
        assertFalse(control.active)
        assertFalse(control.press(CENTRE, onScreen = false).first.consume)
    }

    @Test
    fun otherKeysAlwaysPass() {
        val control = CarPlayWheelControl()
        control.hold()
        for (code in listOf(287, 291, 286, 17, 3)) assertFalse(control.press(code).first.consume)
    }

    @Test
    fun resetEndsTheMode() {
        val control = CarPlayWheelControl()
        assertNull(control.reset())
        control.hold()
        assertEquals(false, control.reset())
    }

    @Test
    fun theAccessibilityListKeepsOtherServices() {
        val ours = "com.shihab.diplay.hudtest/com.shilapi.xcertplay.CarPlayWheelService"
        val mg4control = "com.mg4.control/com.mg4.control.accessibility.KeyCaptureService"
        assertEquals("$mg4control:$ours", CarPlayWheel.withService(mg4control, ours, enabled = true))
        assertEquals(ours, CarPlayWheel.withService("", ours, enabled = true))
        assertEquals("$mg4control:$ours", CarPlayWheel.withService("$mg4control:$ours", ours, enabled = true))
        assertEquals(mg4control, CarPlayWheel.withService("$ours:$mg4control", ours, enabled = false))
        assertEquals("", CarPlayWheel.withService(ours, ours, enabled = false))
    }

    @Test
    fun upAndDownTurnTheKnobSidewaysNudgesAndTheCentreSelects() {
        // A y nudge panned Apple Maps; turning steps the highlight instead.
        assertEquals(-1, CarPlayWheel.knob(Command.UP).wheel)
        assertEquals(1, CarPlayWheel.knob(Command.DOWN).wheel)
        assertEquals(0, CarPlayWheel.knob(Command.UP).y)
        assertEquals(-127, CarPlayWheel.knob(Command.LEFT).x)
        assertEquals(127, CarPlayWheel.knob(Command.RIGHT).x)
        assertTrue(CarPlayWheel.knob(Command.SELECT).select)
    }
}
