package com.shilapi.xcertplay

import com.shilapi.xcertplay.Mg4FirmwareProfile.Generation
import com.shilapi.xcertplay.media.AudioMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mg4FirmwareProfileTest {
    @Test
    fun swi165UsesHmiLauncherAndOverlayVideoWithoutGuessingTheCard() {
        val profile = Mg4FirmwareProfile.forFirmware("SWI165-29176-1100R11")
        assertEquals(Generation.SWI165, profile.generation)
        assertEquals("com.saicmotor.hmi.launcher", profile.stockLauncherPackage)
        assertNull(profile.launcherCarPlayCard)
        assertTrue(profile.preferSurfaceVideo)
        assertFalse(Mg4LauncherOverlaySupport.isSupported(profile))
    }

    @Test
    fun swi68SharesTheSwi165Profile() {
        val profile = Mg4FirmwareProfile.forFirmware("SWI68-29958-1300R59")
        assertEquals(Generation.SWI165, profile.generation)
        assertEquals("SWI68-29958-1300R59", profile.firmware)
        assertEquals("com.saicmotor.hmi.launcher", profile.stockLauncherPackage)
        assertTrue(profile.preferSurfaceVideo)
        assertEquals(AudioMode.CARPLAY_WIFI, profile.defaultAudioMode)
        assertTrue(profile.squareCorners)
    }

    @Test
    fun swi69KeepsMeasuredLauncherCardAndTextureVideo() {
        val profile = Mg4FirmwareProfile.forFirmware("SWI69-12345-1000R01")
        assertEquals(Generation.SWI69, profile.generation)
        assertEquals("com.saicmotor.launcher", profile.stockLauncherPackage)
        assertNotNull(profile.launcherCarPlayCard)
        assertFalse(profile.preferSurfaceVideo)
        assertTrue(Mg4LauncherOverlaySupport.isSupported(profile))
    }

    @Test
    fun unknownOrMissingFirmwareKeepsDefaults() {
        for (firmware in listOf(null, "", "  ", "SWI6-1", "OTHER-1.0")) {
            val profile = Mg4FirmwareProfile.forFirmware(firmware)
            assertEquals(Generation.OTHER, profile.generation)
            assertFalse(profile.preferSurfaceVideo)
            assertFalse(Mg4LauncherOverlaySupport.isSupported(profile))
        }
    }

    @Test
    fun swi165DefaultsToWifiAudioAndOthersToHybridBluetooth() {
        assertEquals(AudioMode.CARPLAY_WIFI, Mg4FirmwareProfile.forFirmware("SWI165-29176-1100R11").defaultAudioMode)
        for (firmware in listOf("SWI69-12345-1000R01", null, "OTHER-1.0")) {
            assertEquals(AudioMode.HYBRID_BLUETOOTH, Mg4FirmwareProfile.forFirmware(firmware).defaultAudioMode)
        }
    }

    @Test
    fun onlySwi165DefaultsToSquareCorners() {
        assertEquals(true, Mg4FirmwareProfile.forFirmware("SWI165-29176-1100R11").squareCorners)
        for (firmware in listOf("SWI69-12345-1000R01", null, "OTHER-1.0")) {
            assertEquals(false, Mg4FirmwareProfile.forFirmware(firmware).squareCorners)
        }
    }
}
