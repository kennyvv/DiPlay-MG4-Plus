package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlaySong
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.transport.Iap2ArtworkTransfer

/**
 * Sends the CarPlay song (title, artist, album, playing, cover) to the MG4 Launcher home screen
 * (`nl.mg4launcher`) and takes its play, pause, next and previous buttons.
 *
 * On Android 9 MG4CPlay publishes no media session (the SAIC Bluetooth stack would pause the
 * iPhone, see [CarPlayMediaKeys]), so the launcher cannot read the song the usual way. Both
 * apps declare [PERMISSION] with `protectionLevel="signature"`: only apps signed with the same
 * (platform) key can send or receive these broadcasts. Nothing is sent when the launcher is
 * not installed.
 */
internal object LauncherBridge {
    private const val TAG = "DiPlay-Launcher"
    const val PERMISSION = "nl.mg4launcher.permission.CARPLAY_BRIDGE"
    private const val LAUNCHER = "nl.mg4launcher"
    private const val ACTION_STATE = "nl.mg4launcher.action.CARPLAY_STATE"
    private const val ACTION_COMMAND = "nl.mg4launcher.action.CARPLAY_COMMAND"
    private const val COVER_PX = 320

    private val main = Handler(Looper.getMainLooper())
    private val decoder by lazy { Handler(HandlerThread("launcher-cover").apply { start() }.looper) }
    private var context: Context? = null
    @Volatile private var controller: CarPlayController? = null
    private var registered = false
    private var song: CarPlaySong? = null
    private var cover: Pair<Int, Bitmap>? = null
    private var lastSentCover: Int? = null
    private var lastName = ""

    /** "bluetooth" (hybrid: music over the car's Bluetooth Music) or "wifi" (DiPlay plays it). */
    @Volatile var audioMode: String = "bluetooth"
    /** Wi-Fi audio: ask for the car's audio focus again (the launcher's "take_focus"). */
    @Volatile var takeFocus: (() -> Boolean)? = null

    /** Whether the launcher is installed (then album art is worth requesting from the iPhone). */
    fun wanted(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(LAUNCHER, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** A CarPlay session started with [target]. Main thread. */
    fun attach(context: Context, target: CarPlayController) {
        val app = context.applicationContext
        this.context = app
        controller = target
        song = target.currentSong()
        if (!registered && wanted(app)) {
            app.registerReceiver(commands, IntentFilter(ACTION_COMMAND), PERMISSION, main)
            registered = true
            Log.i(TAG, "launcher found: sending the CarPlay song")
        }
        publish()
    }

    /** The session with [expected] ended. Main thread. */
    fun detach(expected: CarPlayController?) {
        if (expected == null || controller !== expected) return
        controller = null
        song = null
        cover = null
        publish()
    }

    /** Send the state again (the phone's name may have changed); any thread. */
    fun refresh() {
        if (controller == null) return
        main.post {
            val name = controller?.phoneName().orEmpty()
            if (name != lastName) publish()
        }
    }

    /** A new song (or none); any thread. */
    fun onSong(next: CarPlaySong?) = main.post {
        if (next?.title != song?.title) cover = cover?.takeIf { Mg4ClusterSong.coverMatches(next, it.first) }
        song = next
        publish()
    }

    /** A finished artwork transfer; any thread. Decoded off the main thread. */
    fun onArtwork(transfer: Iap2ArtworkTransfer) {
        if (context == null) return
        decoder.post {
            val bitmap = decode(transfer.bytes)
            main.post {
                cover = bitmap?.let { transfer.id to it }
                publish()
            }
        }
    }

    private val commands = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val button = when (intent.getStringExtra("command")) {
                "play" -> CarPlayMediaButton.PLAY
                "pause" -> CarPlayMediaButton.PAUSE
                "next" -> CarPlayMediaButton.NEXT
                "previous" -> CarPlayMediaButton.PREVIOUS
                "request_state" -> { lastSentCover = null; publish(); return }
                "take_focus" -> {
                    val asked = takeFocus?.invoke() ?: false
                    Log.i(TAG, "launcher take_focus asked=$asked audio=$audioMode")
                    return
                }
                else -> return
            }
            val sent = controller?.sendMediaButton(button) ?: false
            Log.i(TAG, "launcher button $button sent=$sent")
        }
    }

    private fun publish() {
        val ctx = context ?: return
        if (!registered) return
        val s = song
        val intent = Intent(ACTION_STATE).setPackage(LAUNCHER)
            .putExtra("connected", controller != null)
            .putExtra("title", s?.title ?: "")
            .putExtra("artist", s?.artist ?: "")
            .putExtra("album", s?.album ?: "")
            .putExtra("playing", s?.playing ?: false)
            .putExtra("audio_mode", audioMode)
            .putExtra("device_name", controller?.phoneName().orEmpty().also { lastName = it })
        // Send the cover only when it changes: broadcasts are kept small.
        val c = cover?.takeIf { Mg4ClusterSong.coverMatches(s, it.first) }
        if (c != null && c.first != lastSentCover) {
            intent.putExtra("art", c.second)
            lastSentCover = c.first
        }
        if (c == null) lastSentCover = null
        try {
            ctx.sendBroadcast(intent, PERMISSION)
        } catch (e: RuntimeException) {
            Log.w(TAG, "launcher broadcast failed: $e")
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = Mg4ClusterSong.sampleSize(bounds.outWidth, bounds.outHeight, COVER_PX)
            }
            val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            val scale = COVER_PX.toFloat() / maxOf(b.width, b.height)
            if (scale < 1f) Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true) else b
        } catch (e: RuntimeException) {
            Log.w(TAG, "cover decode failed: $e")
            null
        }
    }
}
