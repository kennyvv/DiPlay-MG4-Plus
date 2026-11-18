package com.shilapi.xcertplay.settings

import com.shilapi.xcertplay.Mg4FirmwareProfile.Generation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelTest {
    private fun env(
        generation: Generation = Generation.SWI165,
        sdk: Int = 28,
        connection: ConnectionChoice = ConnectionChoice.CAR_HOTSPOT,
    ) = SettingsEnvironment(generation, sdk, connection)

    private fun rows(category: SettingsCategory, env: SettingsEnvironment) =
        SettingsLayout.groups(category, env).flatMap(SettingsGroup::rows)

    @Test fun dashboardDirectionsOnlyOnSwi165() {
        assertTrue(SettingRow.CLUSTER_DIRECTIONS in rows(SettingsCategory.CAR, env(Generation.SWI165)))
        assertFalse(SettingRow.CLUSTER_DIRECTIONS in rows(SettingsCategory.CAR, env(Generation.SWI69)))
    }

    @Test fun provenDashboardFeaturesAndTheJoystickLiveUnderCar() {
        val car = rows(SettingsCategory.CAR, env(Generation.SWI165))
        val moved = listOf(
            SettingRow.CLUSTER_MAP, SettingRow.CLUSTER_MAP_SIZE, SettingRow.CLUSTER_SONG, SettingRow.CLUSTER_ALBUM_ART,
            SettingRow.CARPLAY_WHEEL, SettingRow.WHEEL_UP_DOWN, SettingRow.WHEEL_LEFT_RIGHT,
        )
        for (row in moved) {
            assertTrue(row.name, row in car)
            assertFalse(row.name, row in rows(SettingsCategory.EXPERIMENTAL, env(Generation.SWI165)))
            assertFalse(row.name, row in rows(SettingsCategory.CAR, env(Generation.SWI69)))
        }
        // The wheel settings are read on every key press; the dashboard ones need a reconnect.
        assertFalse(SettingRow.CARPLAY_WHEEL.appliesOnReconnect)
        assertFalse(SettingRow.WHEEL_UP_DOWN.appliesOnReconnect)
        assertTrue(SettingRow.CLUSTER_MAP.appliesOnReconnect)
    }

    @Test fun handOverAndMapPauseLeftExperimental() {
        assertTrue(SettingRow.MODES_SCREEN in rows(SettingsCategory.DISPLAY, env(Generation.SWI165)))
        assertTrue(SettingRow.MODES_AUDIO in rows(SettingsCategory.SOUND, env(Generation.SWI165)))
        assertTrue(SettingRow.CLUSTER_MAP_PAUSE in rows(SettingsCategory.CAR, env(Generation.SWI165)))
    }

    @Test fun experimentalHoldsLanesTheCarMfiChipAndCarPlayModes() {
        assertFalse(SettingRow.MODES_SCREEN.appliesOnReconnect)
        assertFalse(SettingRow.MODES_AUDIO.appliesOnReconnect)
        assertEquals(
            listOf(SettingRow.CLUSTER_LANES, SettingRow.CAR_MFI_CHIP, SettingRow.RECHECK_CAR_SOURCE),
            rows(SettingsCategory.EXPERIMENTAL, env(Generation.SWI165)),
        )
        assertTrue(rows(SettingsCategory.EXPERIMENTAL, env(Generation.SWI69)).isEmpty())
    }

    @Test fun experimentalIsListedOnlyWhereItHasSettings() {
        assertTrue(SettingsCategory.EXPERIMENTAL in SettingsLayout.categories(env(Generation.SWI165)))
        assertFalse(SettingsCategory.EXPERIMENTAL in SettingsLayout.categories(env(Generation.SWI69)))
        assertEquals(SettingsCategory.entries.size - 1, SettingsLayout.categories(env(Generation.SWI69)).size)
    }

    @Test fun sendLocationLivesUnderCar() {
        assertTrue(SettingRow.SEND_LOCATION in rows(SettingsCategory.CAR, env()))
        SettingsCategory.entries.filter { it != SettingsCategory.CAR }.forEach {
            assertFalse(SettingRow.SEND_LOCATION in rows(it, env()))
        }
    }

    @Test fun connectionRowsFollowTheConnectionType() {
        val hotspot = rows(SettingsCategory.CONNECTION, env(connection = ConnectionChoice.CAR_HOTSPOT))
        assertTrue(SettingRow.HOTSPOT_DETAILS in hotspot)
        assertFalse(SettingRow.WIFI_DIRECT_BAND in hotspot)

        val direct = rows(SettingsCategory.CONNECTION, env(connection = ConnectionChoice.WIFI_DIRECT))
        assertFalse(SettingRow.HOTSPOT_DETAILS in direct)
        assertTrue(SettingRow.OPEN_CAR_WIFI in direct)
        assertTrue(SettingRow.IPHONE in direct)
    }

    @Test fun wifiDirectBandOnlyOnAndroid9() {
        assertTrue(SettingRow.WIFI_DIRECT_BAND in rows(SettingsCategory.CONNECTION, env(sdk = 28, connection = ConnectionChoice.WIFI_DIRECT)))
        assertFalse(SettingRow.WIFI_DIRECT_BAND in rows(SettingsCategory.CONNECTION, env(sdk = 29, connection = ConnectionChoice.WIFI_DIRECT)))
    }

    @Test fun specialistSettingsSitOnAdvancedPages() {
        val display = rows(SettingsCategory.DISPLAY, env())
        listOf(SettingRow.OVERLAY_RENDERER, SettingRow.COLOUR_RANGE, SettingRow.SQUARE_CORNERS).forEach {
            assertFalse(it in display)
            assertTrue(it in SettingsLayout.page(SettingRow.DISPLAY_ADVANCED).flatMap(SettingsGroup::rows))
        }
        assertEquals(listOf(SettingRow.NAVIGATION_CHANNEL), SettingsLayout.page(SettingRow.SOUND_ADVANCED).flatMap(SettingsGroup::rows))
    }

    @Test fun everyPageRowHasAPage() {
        SettingRow.entries.filter { it.kind == RowKind.PAGE }.forEach {
            assertTrue("$it has no page", SettingsLayout.page(it).isNotEmpty())
        }
    }

    @Test fun noRowAppearsTwice() {
        for (generation in listOf(Generation.SWI165, Generation.SWI69)) for (connection in ConnectionChoice.entries) {
            val all = SettingsLayout.allRows(env(generation, 28, connection))
            assertEquals("$generation $connection", all.size, all.toSet().size)
        }
    }

    @Test fun everyRowIsReachableOnSwi165() {
        val reachable = ConnectionChoice.entries.flatMap { SettingsLayout.allRows(env(connection = it)) }.toSet()
        assertEquals(SettingRow.entries.toSet(), reachable)
    }

    @Test fun changeLogKeepsTheNewestLines() {
        var log: String? = null
        repeat(SettingsChangeLog.MAX_LINES + 5) { log = SettingsChangeLog.append(log, "line $it") }
        val lines = log!!.lines()
        assertEquals(SettingsChangeLog.MAX_LINES, lines.size)
        assertEquals("line 5", lines.first())
        assertEquals("line ${SettingsChangeLog.MAX_LINES + 4}", lines.last())
    }

    @Test fun testOptionsLiveOnlyUnderDeveloper() {
        val tests = listOf(
            SettingRow.MICROPHONE, SettingRow.SIRI_MIC_PCM, SettingRow.MIC_RECORDING,
            SettingRow.CAR_CALL_PATH, SettingRow.CALL_ECHO_TEST,
            SettingRow.CALL_OWN_FOCUS, SettingRow.RINGTONE_STOCK,
            SettingRow.SIRI_CAR_MIC, SettingRow.SIRI_PREWARM, SettingRow.SIRI_OWN_FOCUS, SettingRow.NAV_DUCKING,
        )
        val developer = rows(SettingsCategory.DEVELOPER, env())
        for (row in tests) {
            assertTrue(row.name, row in developer)
            SettingsCategory.entries.filter { it != SettingsCategory.DEVELOPER }.forEach {
                assertFalse(row.name, row in rows(it, env()))
            }
        }
        assertTrue(SettingRow.CALL_ECHO_SUPPRESSION in rows(SettingsCategory.SOUND, env()))
    }
}
