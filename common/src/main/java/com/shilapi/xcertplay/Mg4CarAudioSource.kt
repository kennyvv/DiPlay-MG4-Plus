package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.Proxy

/**
 * The car's own idea of which app is its audio source. The SAIC framework on SWI68/SWI165 adds hidden
 * `AudioManager.registerAudioSourceChange(OnAudioSourceChangeCallBack, Handler)` and `getCurrentSource()`; CarService
 * and the car's media, radio, voice and cluster apps follow it (`MEDIA.SOURCE OnSourceChange` in logcat). DiPlay is a
 * system app, so it can register too, through reflection. Read only; when the hidden API is missing this logs once and
 * does nothing.
 */
internal object Mg4CarAudioSource {
    private const val TAG = "DiPlay-CarSource"

    private var manager: AudioManager? = null
    private var callback: Any? = null

    /** The package the car last named as its source, null before the first change. */
    @Volatile var current: String? = null
        private set

    /** Starts reporting source changes to [onChange] (package, usage) on the main thread. True when registered. */
    fun start(context: Context, onChange: (String?, Int?) -> Unit, log: (String) -> Unit): Boolean {
        stop()
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        return try {
            val type = Class.forName("android.media.AudioManager\$OnAudioSourceChangeCallBack")
            val id = "diplay-car-source@${System.identityHashCode(this)}"
            val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                when (method.name) {
                    "onSourceChanged" -> {
                        val attributes = args?.getOrNull(0) as? AudioAttributes
                        val pkg = args?.getOrNull(1) as? String
                        current = pkg
                        onChange(pkg, attributes?.usage)
                        null
                    }
                    // AudioManager keys its callback map by toString().
                    "toString" -> id
                    "hashCode" -> id.hashCode()
                    "equals" -> args?.getOrNull(0) === callback
                    else -> null
                }
            }
            AudioManager::class.java.getMethod("registerAudioSourceChange", type, Handler::class.java)
                .invoke(audio, proxy, Handler(Looper.getMainLooper()))
            manager = audio
            callback = proxy
            log("Car source: watching the car's audio source")
            true
        } catch (error: Throwable) {
            Log.i(TAG, "car audio source API not available: $error")
            log("Car source: not available on this head unit (${error.javaClass.simpleName})")
            false
        }
    }

    fun stop() {
        val audio = manager ?: return
        val cb = callback
        manager = null
        callback = null
        current = null
        runCatching {
            val type = Class.forName("android.media.AudioManager\$OnAudioSourceChangeCallBack")
            AudioManager::class.java.getMethod("unregisterAudioSourceChange", type).invoke(audio, cb)
        }
    }
}
