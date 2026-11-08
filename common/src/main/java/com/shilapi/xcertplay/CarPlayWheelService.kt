package com.shilapi.xcertplay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.shilapi.xcertplay.airplay.AirPlayKnobState
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** What the wheel service needs from the CarPlay screen: whether it shows, and where knob moves go. */
internal object CarPlayWheel {
    @Volatile var controller: CarPlayController? = null
    @Volatile var onScreen = false
    @Volatile var log: (String) -> Unit = {}

    val carPlayOnScreen: Boolean get() = onScreen && controller != null

    /**
     * The knob report for each joystick command. Up and down turn the knob, which steps CarPlay's
     * highlight from element to element; a nudge there panned Apple Maps instead (car, 2026-11-08).
     * Left and right stay nudges, for rows and grids. The centre is the knob's button.
     */
    fun knob(command: CarPlayWheelControl.Command): AirPlayKnobState = when (command) {
        CarPlayWheelControl.Command.UP -> AirPlayKnobState(wheel = -1)
        CarPlayWheelControl.Command.DOWN -> AirPlayKnobState(wheel = 1)
        CarPlayWheelControl.Command.LEFT -> AirPlayKnobState(x = -NUDGE)
        CarPlayWheelControl.Command.RIGHT -> AirPlayKnobState(x = NUDGE)
        CarPlayWheelControl.Command.SELECT -> AirPlayKnobState(select = true)
    }

    private const val NUDGE = 127

    /**
     * Adds or removes [CarPlayWheelService] in Android's enabled accessibility services, leaving any
     * other (such as MG4Control's) as it is. MG4CPlay runs as the system user, so it may write them;
     * called only from the Experimental switch. Returns whether the setting was written.
     */
    fun setServiceEnabled(context: Context, enabled: Boolean): Boolean = runCatching {
        val resolver = context.contentResolver
        val ours = ComponentName(context, CarPlayWheelService::class.java).flattenToString()
        val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val next = withService(current, ours, enabled)
        if (next != current) Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
        if (enabled) Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        true
    }.onFailure { Log.w(TAG, "accessibility setting not written", it) }.getOrDefault(false)

    /** The colon-separated service list with [service] added or removed, the others kept in order. */
    fun withService(list: String, service: String, enabled: Boolean): String {
        val others = list.split(':').filter { it.isNotBlank() && !it.equals(service, ignoreCase = true) }
        return (if (enabled) others + service else others).joinToString(":")
    }

    private const val TAG = "DiPlay-Wheel"
}

/**
 * Experimental: takes the steering-wheel joystick before the car while CarPlay is on screen
 * ([CarPlayWheelControl] has the rules). Keys it does not claim pass straight on; a short centre
 * press out of CarPlay mode is held back for [CarPlayWheelControl.LONG_PRESS_MILLIS] and then
 * replayed to the car as the same key, as MG4Control does with its advanced shortcuts.
 */
class CarPlayWheelService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val injector = Executors.newSingleThreadExecutor { Thread(it, "diplay-wheel-replay").apply { isDaemon = true } }
    private val control = CarPlayWheelControl()
    private val replays = ConcurrentHashMap<Long, Int>()
    private var centreModel: KeyEvent? = null
    private val longPress = Runnable { apply(control.onLongPress()) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // The XML declares the capability; the flag has to be asked for here as well.
        serviceInfo = serviceInfo?.apply { flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS }
        CarPlayWheel.log("Wheel: service connected")
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        event ?: return false
        return try {
            decide(event)
        } catch (error: Exception) {
            // Swallowing a key by mistake in a car is worse than missing one: pass on anything unexpected.
            Log.w(TAG, "key not handled", error)
            false
        }
    }

    private fun decide(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (!CarPlayWheelControl.isJoystick(code)) return false
        if (replays[event.downTime] == code) return false
        if (!AirPlayPersistence.loadCarPlayWheel(this)) {
            control.reset()?.let(::announce)
            return false
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        if (code == CarPlayWheelControl.CENTRE && down && event.repeatCount == 0) centreModel = KeyEvent(event)
        val result = control.onKey(code, down, event.repeatCount, CarPlayWheel.carPlayOnScreen)
        apply(result)
        return result.consume
    }

    private fun apply(result: CarPlayWheelControl.Result) {
        if (result.startLongTimer) {
            main.removeCallbacks(longPress)
            main.postDelayed(longPress, CarPlayWheelControl.LONG_PRESS_MILLIS)
        }
        if (result.replayCentre || result.command != null || result.modeChanged != null) main.removeCallbacks(longPress)
        if (result.replayCentre) replayCentre()
        result.command?.let { command ->
            val sent = CarPlayWheel.controller?.sendKnob(CarPlayWheel.knob(command)) ?: false
            if (!sent) CarPlayWheel.log("Wheel: $command not sent, no CarPlay session")
        }
        result.modeChanged?.let(::announce)
    }

    private fun announce(active: Boolean) {
        CarPlayWheel.log("Wheel: CarPlay control ${if (active) "on" else "off"}")
        main.post {
            Toast.makeText(
                this,
                getString(if (active) R.string.carplay_wheel_on else R.string.carplay_wheel_off),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // The same key the car sent, with a new time so the replay is recognised and let through.
    private fun replayCentre() {
        val model = centreModel ?: return
        val time = SystemClock.uptimeMillis()
        replays[time] = model.keyCode
        main.postDelayed({ replays.remove(time) }, REPLAY_MEMORY_MILLIS)
        fun copy(action: Int) = KeyEvent(
            time, SystemClock.uptimeMillis(), action, model.keyCode, 0,
            model.metaState, model.deviceId, model.scanCode, model.flags, model.source,
        )
        injector.execute {
            runCatching {
                val instrumentation = Instrumentation()
                instrumentation.sendKeySync(copy(KeyEvent.ACTION_DOWN))
                instrumentation.sendKeySync(copy(KeyEvent.ACTION_UP))
            }.onFailure { Log.w(TAG, "centre press not replayed", it) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        main.removeCallbacks(longPress)
        injector.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "DiPlay-Wheel"
        const val REPLAY_MEMORY_MILLIS = 2_000L
    }
}
