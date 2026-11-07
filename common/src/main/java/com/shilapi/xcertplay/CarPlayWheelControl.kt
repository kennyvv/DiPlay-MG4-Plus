package com.shilapi.xcertplay

/**
 * Experimental: the steering-wheel joystick driving CarPlay, the decisions without Android.
 *
 * Read from the SWI68 SystemUI (`PhysicalKeysBroadcastReceiver`): the right-hand joystick reports
 * 297 up, 298 down, 299 left, 300 right and 301 centre. The car turns them into volume, previous,
 * next and mute through broadcasts nobody can stop, so [CarPlayWheelService] takes them first, as an
 * accessibility service filtering keys (the way MG4Control's advanced shortcuts do).
 *
 * Holding the centre while CarPlay is on screen switches CarPlay mode on or off. In CarPlay mode
 * the four directions move CarPlay's highlight and a short centre press selects; out of it every key
 * passes, except that the centre is held back until it is known to be short and then handed back to
 * the car ([Result.replayCentre]). Leaving the CarPlay screen ends the mode.
 */
internal class CarPlayWheelControl {
    enum class Command { UP, DOWN, LEFT, RIGHT, SELECT }

    data class Result(
        val consume: Boolean,
        val command: Command? = null,
        /** Start the long-press timer, which calls [onLongPress] when it runs out. */
        val startLongTimer: Boolean = false,
        /** Hand a short centre press back to the car as it was. */
        val replayCentre: Boolean = false,
        /** The mode after this event, when it changed. */
        val modeChanged: Boolean? = null,
    )

    var active = false
        private set
    private var centreDown = false
    private var longFired = false

    fun onKey(code: Int, down: Boolean, repeat: Int, carPlayOnScreen: Boolean): Result {
        if (!carPlayOnScreen) {
            centreDown = false
            if (!active) return PASS
            active = false
            return PASS.copy(modeChanged = false)
        }
        return when (code) {
            CENTRE -> centre(down, repeat)
            UP, DOWN, LEFT, RIGHT -> if (!active) {
                PASS
            } else {
                Result(consume = true, command = if (down && repeat == 0) direction(code) else null)
            }
            else -> PASS
        }
    }

    /** The centre has been held for the long-press time. */
    fun onLongPress(): Result {
        if (!centreDown || longFired) return Result(consume = false)
        longFired = true
        active = !active
        return Result(consume = true, modeChanged = active)
    }

    /** CarPlay closed: no mode, nothing held. */
    fun reset(): Boolean? {
        centreDown = false
        longFired = false
        if (!active) return null
        active = false
        return false
    }

    private fun centre(down: Boolean, repeat: Int): Result {
        if (down) {
            if (repeat > 0) return Result(consume = true)
            centreDown = true
            longFired = false
            return Result(consume = true, startLongTimer = true)
        }
        if (!centreDown) return PASS
        centreDown = false
        return when {
            longFired -> Result(consume = true)
            active -> Result(consume = true, command = Command.SELECT)
            else -> Result(consume = true, replayCentre = true)
        }
    }

    private fun direction(code: Int) = when (code) {
        UP -> Command.UP
        DOWN -> Command.DOWN
        LEFT -> Command.LEFT
        else -> Command.RIGHT
    }

    companion object {
        const val UP = 297
        const val DOWN = 298
        const val LEFT = 299
        const val RIGHT = 300
        const val CENTRE = 301
        /** MG4Control's long-press time on the same keys. */
        const val LONG_PRESS_MILLIS = 500L
        private val PASS = Result(consume = false)

        fun isJoystick(code: Int) = code in UP..CENTRE
    }
}
