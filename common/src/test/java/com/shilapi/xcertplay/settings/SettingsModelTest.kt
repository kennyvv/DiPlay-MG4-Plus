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

    @Test fun dashboardMapSongAndArtAreExperimentalOnTheEh32Cluster() {
        val experimental = rows(SettingsCategory.EXPERIMENTAL, env(Generation.SWI165))
        val added = listOf(
            SettingRow.CLUSTER_MAP, SettingRow.CLUSTER_MAP_SIZE, SettingRow.CLUSTER_MAP_RENDERER, SettingRow.CLUSTER_LANES, SettingRow.CLUSTER_SONG, SettingRow.CLUSTER_ALBUM_ART,
            SettingRow.CARPLAY_WHEEL,
        )
        // In the order they were added.
        assertEquals(added, experimental)
        // The wheel switch is read on every key press; the others need a reconnect.
        assertFalse(SettingRow.CARPLAY_WHEEL.appliesOnReconnect)
        for (row in added) {
            assertTrue(row.name, row in experimental)
            if (row != SettingRow.CARPLAY_WHEEL) assertTrue(row.name, row.appliesOnReconnect)
            SettingsCategory.entries.filter { it != SettingsCategory.EXPERIMENTAL }.forEach {
                assertFalse(row.name, row in rows(it, env(Generation.SWI165)))
            }
        }
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
}
