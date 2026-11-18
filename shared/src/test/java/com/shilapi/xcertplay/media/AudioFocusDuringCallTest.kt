package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioManager
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A focus loss during a CarPlay call must not be reported as a music loss: the host would then hand the iPhone's
 * main audio, which carries the call, to the car (changeModes) and the call went silent after a burst of static.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AudioFocusDuringCallTest {
    private val music = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
    private val call = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build()

    private fun coordinator(): Pair<AudioFocusCoordinator, MutableList<Pair<Int, Boolean>>> {
        val seen = mutableListOf<Pair<Int, Boolean>>()
        val focus = AudioFocusCoordinator(RuntimeEnvironment.getApplication(), enabled = true, callMode = true)
        focus.focusChangeListener = { change, media -> seen += change to media }
        return focus to seen
    }

    private fun loseFocus(change: Int) {
        val manager = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)!!
        val request = shadowOf(manager).lastAudioFocusRequest
        request.listener.onAudioFocusChange(change)
    }

    @Test
    fun aLossWithOnlyMusicIsAMusicLoss() {
        val (focus, seen) = coordinator()
        focus.acquire(Any(), AudioChannel.MEDIA, music)
        assertFalse(focus.voiceActive())
        loseFocus(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(listOf(AudioManager.AUDIOFOCUS_LOSS to true), seen)
    }

    @Test
    fun aLossDuringACallIsNotAMusicLossAndFocusIsNotAskedAgain() {
        val (focus, seen) = coordinator()
        focus.acquire(Any(), AudioChannel.MEDIA, music)
        focus.acquire(Any(), AudioChannel.PHONE, call)
        assertTrue(focus.voiceActive())
        loseFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(listOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT to false), seen)
        assertFalse(focus.reacquire())
    }

    @Test
    fun afterTheCallMusicLossesCountAgain() {
        val (focus, seen) = coordinator()
        focus.acquire(Any(), AudioChannel.MEDIA, music)
        val callTrack = Any()
        focus.acquire(callTrack, AudioChannel.PHONE, call)
        focus.release(callTrack)
        assertFalse(focus.voiceActive())
        loseFocus(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(listOf(AudioManager.AUDIOFOCUS_LOSS to true), seen)
    }

    @Test
    fun withOwnFocusACallTakesTheRequestFromMusicAndMusicGetsItBack() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)!!
        try {
            AudioFocusOptions.callOwnFocus = true
            val (focus, _) = coordinator()
            focus.acquire(Any(), AudioChannel.MEDIA, music)
            val callTrack = Any()
            focus.acquire(callTrack, AudioChannel.PHONE, call)
            val during = shadowOf(manager).lastAudioFocusRequest
            assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, during.durationHint)
            assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, during.audioFocusRequest.audioAttributes.usage)
            focus.release(callTrack)
            assertEquals(AudioAttributes.USAGE_MEDIA, shadowOf(manager).lastAudioFocusRequest.audioFocusRequest.audioAttributes.usage)
        } finally {
            AudioFocusOptions.callOwnFocus = false
        }
    }

    @Test
    fun withoutOwnFocusMusicKeepsTheRequestDuringACall() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)!!
        val (focus, _) = coordinator()
        focus.acquire(Any(), AudioChannel.MEDIA, music)
        focus.acquire(Any(), AudioChannel.PHONE, call)
        assertEquals(AudioAttributes.USAGE_MEDIA, shadowOf(manager).lastAudioFocusRequest.audioFocusRequest.audioAttributes.usage)
    }
}
