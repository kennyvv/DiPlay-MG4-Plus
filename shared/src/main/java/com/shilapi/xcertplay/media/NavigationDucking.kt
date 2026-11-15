package com.shilapi.xcertplay.media

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Test switch: makes the MG4 treat CarPlay navigation prompts as navigation, as the car's own CarPlay
 * app does (docs/upstream-review-2026-11-14.md, issue 3).
 *
 * Between the iPhone's duckAudio and unduckAudio it holds navigation audio focus (usage 12, car source
 * 52, GAIN_TRANSIENT), so the car opens its navigation channel and the volume knob adjusts navigation,
 * and it calls CarAudioManager.setNaviDucking(true), which is what lowers music on this car: focus with
 * source 52 alone does not duck (CarAudioService). The car's own "lower media for navigation" setting
 * must also be on. Released 1 s after unduckAudio, 30 s after a duck with no unduck, and on session end.
 */
object NavigationDucking {
    @Volatile var enabled: Boolean = false

    private const val CARPLAY_NAVIGATION_SOURCE = 52
    private const val RELEASE_AFTER_UNDUCK_MS = 1_000L
    private const val RELEASE_WITHOUT_UNDUCK_MS = 30_000L

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var appContext: Context? = null
    private var report: (String) -> Unit = {}
    private var car: Any? = null
    private var carAudio: Any? = null
    private var focus: AudioFocusRequest? = null
    private var ducked = false

    private val release = Runnable { releaseNow("released") }

    /** Called by the host when a CarPlay session starts. Connects to the car's audio service once. */
    fun prepare(context: Context, log: (String) -> Unit) {
        appContext = context.applicationContext
        report = log
        if (!enabled) return
        handler.post { connectCar() }
    }

    fun onDuck(duck: Boolean) {
        if (!enabled) return
        handler.post {
            if (duck) {
                handler.removeCallbacks(release)
                if (!ducked) {
                    ducked = true
                    val granted = requestFocus()
                    val result = setNaviDucking(true)
                    report("Nav duck: on focus=$granted setNaviDucking=$result")
                }
                handler.postDelayed(release, RELEASE_WITHOUT_UNDUCK_MS)
            } else if (ducked) {
                handler.removeCallbacks(release)
                handler.postDelayed(release, RELEASE_AFTER_UNDUCK_MS)
            }
        }
    }

    /** Session end: undo everything now so `sys.carplay_ducking` never stays on. */
    fun reset() {
        if (!enabled) return
        handler.post {
            handler.removeCallbacks(release)
            if (ducked) releaseNow("released at session end")
        }
    }

    private fun releaseNow(how: String) {
        if (!ducked) return
        ducked = false
        val result = setNaviDucking(false)
        val manager = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        focus?.let { request -> runCatching { manager?.abandonAudioFocusRequest(request) } }
        focus = null
        report("Nav duck: off ($how) setNaviDucking=$result")
    }

    private fun requestFocus(): String {
        val manager = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return "no audio manager"
        val builder = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        val tagged = Mg4CarAudioSource.tag(builder, CARPLAY_NAVIGATION_SOURCE)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(builder.build())
            .setOnAudioFocusChangeListener({ change -> report("Nav duck: focus change=$change") }, handler)
            .build()
        focus = request
        val result = runCatching { manager.requestAudioFocus(request) }.getOrElse { return "failed ${it.javaClass.simpleName}" }
        return "$result source52=$tagged"
    }

    private fun connectCar() {
        if (car != null) return
        runCatching {
            val carClass = Class.forName("android.car.Car")
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    carAudio = runCatching {
                        carClass.getMethod("getCarManager", String::class.java).invoke(car, "audio")
                    }.getOrNull()
                    report("Nav duck: car audio service ${if (carAudio != null) "connected" else "has no audio manager"}")
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    carAudio = null
                }
            }
            car = carClass.getMethod("createCar", Context::class.java, ServiceConnection::class.java)
                .invoke(null, appContext, connection)
            carClass.getMethod("connect").invoke(car)
        }.onFailure { report("Nav duck: car service unavailable: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun setNaviDucking(on: Boolean): String {
        val manager = carAudio ?: return "no car audio service"
        return runCatching {
            manager.javaClass.getMethod("setNaviDucking", Boolean::class.javaPrimitiveType).invoke(manager, on)
            "ok"
        }.getOrElse { "failed ${(it.cause ?: it).javaClass.simpleName}" }
    }
}
