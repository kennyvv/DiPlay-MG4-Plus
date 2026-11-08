// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.settings

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.AppLocale
import com.shilapi.xcertplay.DiPlayPreferences
import com.shilapi.xcertplay.Mg4FirmwareProfile
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.CarPlaySize
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.localizedLabel
import com.shilapi.xcertplay.media.AudioMode
import com.shilapi.xcertplay.media.MediaAudioBuffer
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.EvChargingConnectors
import com.shilapi.xcertplay.vehicle.AbrpSettings
import com.shilapi.xcertplay.vehicle.Mg4Vehicle
import com.shilapi.xcertplay.vehicle.VehicleSettings
import com.shilapi.xcertplay.settings.SettingsUi.Companion as Colors

/**
 * The MG4 Settings screen: categories in a sidebar, the chosen one's settings in grouped lists
 * beside it, as in iPad Settings. Below 850 dp wide it becomes one list that drills down.
 * [SettingsLayout] decides which rows appear; this class reads and saves their values.
 */
internal class Mg4SettingsPage(private val activity: Activity, private val host: Host) {
    /** What the page needs from [com.shilapi.xcertplay.DiPlayActivity]: dialogs, system screens, reconnecting. */
    interface Host {
        val sessionRunning: Boolean
        /** Wireless connect was refused because the car hotspot's details aren't saved. */
        val hotspotSetupPending: Boolean
        fun render()
        fun goHome()
        fun hotspotOff(): Boolean
        fun choosePhone()
        /** Asks for the car hotspot's name and password; [onSaved] runs after they are saved. */
        fun editHotspotDetails(onSaved: () -> Unit)
        fun openCarHotspotSettings()
        fun openCarWifiSettings()
        fun editNavigationChannel(onSaved: () -> Unit)
        fun setLocationReporting(enabled: Boolean)
        fun editAbrpAccount()
        fun abrpTelemetryChanged(enabled: Boolean)
        fun checkForUpdates()
        fun openAppPermissions()
        fun openBluetoothSettings()
        fun saveReport()
        fun downloadReport()
        fun chooseReportLocation()
        fun bindReportControls(save: TextView, download: TextView, downloadHint: TextView)
        fun wirelessHelp()
        fun reconnect()
        fun version(): String
    }

    private class Picker(val title: String, val options: List<String>, val selected: Int, val save: (Int) -> Unit)

    private val ui = SettingsUi(activity)
    private val profile get() = Mg4FirmwareProfile.detect()

    /** The open category; null shows the category list on a narrow screen. */
    private var category: SettingsCategory? = SettingsCategory.CONNECTION
    /** Pages and pickers opened from the category, innermost last. */
    private val stack = mutableListOf<SettingRow>()
    /** Rows changed during this CarPlay session that need a reconnect. */
    private val changed = linkedSetOf<SettingRow>()
    private val tags = mutableMapOf<SettingRow, View>()
    private var reconnectBar: View? = null
    private var sidebarScroll: ScrollView? = null
    private var detailScroll: ScrollView? = null
    private var shownKey: String? = null
    private val scrollPositions = mutableMapOf<String, Int>()

    /** The status line in the sidebar; the activity keeps its text current. */
    var statusView: TextView? = null
        private set

    private val wide get() = activity.resources.configuration.screenWidthDp >= 850

    fun environment() = SettingsEnvironment(profile.generation, Build.VERSION.SDK_INT, connectionChoice())

    /** Opens [target] the next time the page is built. */
    fun show(target: SettingsCategory) {
        rememberScroll()
        category = target; stack.clear()
    }

    fun open(target: SettingsCategory) { show(target); host.render() }

    /** Handles the car's back button inside Settings; false means leave Settings. */
    fun back(): Boolean {
        rememberScroll()
        when {
            stack.isNotEmpty() -> stack.removeAt(stack.lastIndex)
            !wide && category != null -> category = null
            else -> return false
        }
        host.render()
        return true
    }

    /** Called every second by the activity. Changes no longer need a reconnect once CarPlay has stopped. */
    fun refresh() {
        if (!host.sessionRunning && changed.isNotEmpty()) {
            changed.clear()
            tags.values.forEach { it.visibility = View.GONE }
        }
        reconnectBar?.visibility = if (changed.isNotEmpty()) View.VISIBLE else View.GONE
    }

    fun save(state: Bundle) {
        state.putString(STATE_CATEGORY, category?.name)
        state.putStringArrayList(STATE_STACK, ArrayList(stack.map { it.name }))
    }

    fun restore(state: Bundle?) {
        state ?: return
        category = state.getString(STATE_CATEGORY)?.let { name -> SettingsCategory.entries.firstOrNull { it.name == name } }
            ?: if (state.containsKey(STATE_CATEGORY)) null else SettingsCategory.CONNECTION
        stack.clear()
        state.getStringArrayList(STATE_STACK)?.mapNotNullTo(stack) { name -> SettingRow.entries.firstOrNull { it.name == name } }
    }

    fun build(): View {
        rememberScroll()
        tags.clear(); reconnectBar = null; statusView = null; sidebarScroll = null; detailScroll = null
        saveReportRow = null; downloadReportRow = null
        if (wide && category == null) category = SettingsCategory.CONNECTION
        val root = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Colors.BG) }
        val shown = category
        if (wide) {
            root.addView(sidebar(), LinearLayout.LayoutParams(0, -1, 0.32f))
            root.addView(View(activity).apply { setBackgroundColor(Colors.SEPARATOR) }, LinearLayout.LayoutParams(ui.dp(1), -1))
            root.addView(detail(shown!!), LinearLayout.LayoutParams(0, -1, 0.68f))
        } else if (shown == null) {
            root.addView(sidebar(), LinearLayout.LayoutParams(-1, -1))
        } else {
            root.addView(detail(shown), LinearLayout.LayoutParams(-1, -1))
        }
        shownKey = locationKey()
        refresh()
        return root
    }

    private fun locationKey() = (listOf(category?.name ?: "LIST") + stack.map { it.name }).joinToString("/")

    private fun rememberScroll() {
        sidebarScroll?.let { scrollPositions["sidebar"] = it.scrollY }
        val key = shownKey ?: return
        detailScroll?.let { scrollPositions[key] = it.scrollY }
    }

    private fun scroller(key: String, content: View) = ScrollView(activity).apply {
        isFillViewport = true; clipToPadding = false
        addView(content)
        val y = scrollPositions[key] ?: 0
        if (y > 0) post { scrollTo(0, y) }
    }

    // ---- Sidebar ----

    private fun sidebar(): View {
        val list = ui.column().apply { setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(32)) }
        list.addView(ui.navBar(activity.getString(R.string.settings_home), null) { host.goHome() })
        list.addView(ui.largeTitle(activity.getString(R.string.settings)))
        list.addView(statusCard(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(20) })
        val selected = if (wide) category else null
        SettingsLayout.categories(environment()).forEach { item ->
            list.addView(ui.sidebarItem(icon(item), tile(item), title(item), item == selected) { open(item) },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(4) })
        }
        return scroller("sidebar", list).apply {
            setBackgroundColor(if (wide) Colors.SIDEBAR else Colors.BG)
            sidebarScroll = this
        }
    }

    private fun statusCard(): View {
        val card = ui.row().apply {
            background = ui.rounded(Colors.SURFACE, 14)
            setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
            minimumHeight = ui.dp(76)
            setOnClickListener { open(SettingsCategory.CONNECTION) }
        }
        card.addView(ui.iconTile(R.drawable.ic_carplay, Colors.SURFACE, tint = false), LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        val text = ui.column()
        val phone = if (DiPlayPreferences.phoneAddress(activity) != null) DiPlayPreferences.phoneName(activity)
            else activity.getString(R.string.settings_no_iphone)
        text.addView(ui.text(phone, 19f, Colors.TEXT, true).apply { maxLines = 1 })
        statusView = ui.text("", 15f, Colors.MUTED).apply {
            maxLines = 2
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        text.addView(statusView)
        card.addView(text, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(14) })
        return card
    }

    private fun title(category: SettingsCategory) = activity.getString(when (category) {
        SettingsCategory.CONNECTION -> R.string.settings_category_connection
        SettingsCategory.DISPLAY -> R.string.settings_category_display
        SettingsCategory.SOUND -> R.string.settings_category_sound
        SettingsCategory.CAR -> R.string.settings_category_car
        SettingsCategory.APPS -> R.string.settings_category_apps
        SettingsCategory.EXPERIMENTAL -> R.string.settings_category_experimental
        SettingsCategory.GENERAL -> R.string.settings_category_general
        SettingsCategory.HELP -> R.string.settings_category_help
        SettingsCategory.ABOUT -> R.string.about
    })

    private fun icon(category: SettingsCategory) = when (category) {
        SettingsCategory.CONNECTION -> R.drawable.ic_dp_connection
        SettingsCategory.DISPLAY -> R.drawable.ic_dp_display
        SettingsCategory.SOUND -> R.drawable.ic_dp_sound
        SettingsCategory.CAR -> R.drawable.ic_dp_car
        SettingsCategory.APPS -> R.drawable.ic_dp_apps
        SettingsCategory.EXPERIMENTAL -> R.drawable.ic_dp_diagnostics
        SettingsCategory.GENERAL -> R.drawable.ic_dp_general
        SettingsCategory.HELP -> R.drawable.ic_dp_diagnostics
        SettingsCategory.ABOUT -> R.drawable.ic_dp_about
    }

    // iOS system colours, one per category.
    private fun tile(category: SettingsCategory) = when (category) {
        SettingsCategory.CONNECTION -> 0xFF0A84FF.toInt()
        SettingsCategory.DISPLAY -> 0xFF5E5CE6.toInt()
        SettingsCategory.SOUND -> 0xFFFF375F.toInt()
        SettingsCategory.CAR -> 0xFF30D158.toInt()
        SettingsCategory.APPS -> 0xFFFF9F0A.toInt()
        SettingsCategory.EXPERIMENTAL -> 0xFFBF5AF2.toInt()
        SettingsCategory.GENERAL -> 0xFF8E8E93.toInt()
        SettingsCategory.HELP -> 0xFF40C8E0.toInt()
        SettingsCategory.ABOUT -> 0xFF8E8E93.toInt()
    }

    // ---- Detail pane ----

    private fun detail(category: SettingsCategory): View {
        val env = environment()
        val content = ui.column().apply { setPadding(ui.dp(40), ui.dp(12), ui.dp(40), ui.dp(140)) }
        val top = stack.lastOrNull()
        if (top == null) {
            content.addView(ui.navBar(if (wide) null else activity.getString(R.string.settings), null) { back() })
            content.addView(ui.largeTitle(title(category)))
            groups(content, SettingsLayout.groups(category, env), env)
        } else {
            val parent = stack.getOrNull(stack.lastIndex - 1)?.let(::rowTitle) ?: title(category)
            content.addView(ui.navBar(parent, rowTitle(top)) { back() })
            content.addView(View(activity), LinearLayout.LayoutParams(1, ui.dp(16)))
            if (top.kind == RowKind.PICKER) pickerPage(content, top) else groups(content, SettingsLayout.page(top), env)
        }
        val frame = FrameLayout(activity)
        frame.addView(scroller(locationKey(), content).also { detailScroll = it }, FrameLayout.LayoutParams(-1, -1))
        frame.addView(reconnectBar(), FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(ui.dp(40), 0, ui.dp(40), ui.dp(20))
        })
        return frame
    }

    private fun reconnectBar(): View = ui.row().apply {
        background = ui.rounded(Colors.SURFACE, 14, Colors.WARNING)
        setPadding(ui.dp(20), ui.dp(10), ui.dp(10), ui.dp(10))
        elevation = ui.dp(8).toFloat()
        addView(ui.text(activity.getString(R.string.settings_reconnect_bar), 18f, Colors.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
        addView(ui.text(activity.getString(R.string.settings_reconnect_now), 18f, Colors.BG, true).apply {
            gravity = Gravity.CENTER; minHeight = ui.dp(52); setPadding(ui.dp(22), 0, ui.dp(22), 0)
            background = ui.rounded(Colors.ACCENT, 12)
            setOnClickListener {
                SettingsChangeLog.note(activity, "Reconnect now after ${changed.joinToString { it.name }}")
                changed.clear(); tags.values.forEach { tag -> tag.visibility = View.GONE }; refresh()
                host.reconnect()
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(16) })
        visibility = View.GONE
        reconnectBar = this
    }

    private fun groups(content: LinearLayout, groups: List<SettingsGroup>, env: SettingsEnvironment) {
        groups.forEach { group ->
            header(group.key, env)?.let { content.addView(ui.groupHeader(it)) }
            if (group.rows.isNotEmpty()) {
                val box = ui.group()
                group.rows.forEach { row ->
                    rowView(row, env, box)?.let { view -> ui.addRow(box, view) }
                }
                content.addView(box, LinearLayout.LayoutParams(-1, -2))
            }
            footers(group.key, env, content)
            content.addView(View(activity), LinearLayout.LayoutParams(1, ui.dp(32)))
        }
    }

    private fun header(key: String, env: SettingsEnvironment): String? = when (key) {
        "automatic" -> R.string.settings_header_automatic
        "wifi" -> R.string.settings_header_wifi
        "screen" -> R.string.settings_header_screen
        "picture" -> R.string.settings_header_picture
        "cluster" -> R.string.settings_header_driving
        "location" -> R.string.settings_header_driving.takeIf { env.generation != Mg4FirmwareProfile.Generation.SWI165 }
        "appearance" -> R.string.settings_header_appearance
        "battery" -> R.string.settings_header_battery
        "abrp" -> R.string.settings_header_abrp
        "system" -> R.string.settings_header_permissions
        "diagnostics" -> R.string.diagnostics
        "help" -> R.string.settings_header_help
        "licences" -> R.string.made_possible_by_open_source
        else -> null
    }?.let(activity::getString)

    private fun footers(key: String, env: SettingsEnvironment, content: LinearLayout) {
        fun add(text: String, warning: Boolean = false) = content.addView(ui.groupFooter(text, warning))
        fun add(res: Int) = add(activity.getString(res))
        when (key) {
            "connection" -> when (env.connection) {
                ConnectionChoice.CAR_HOTSPOT -> {
                    add(R.string.hotspot_mode_manual_desc)
                    when {
                        host.hotspotSetupPending || !hotspotSaved() -> add(activity.getString(R.string.finish_setup_save_your_hotspot_details_to_use_this_mode), true)
                        host.hotspotOff() -> add(activity.getString(R.string.hotspot_details_off), true)
                    }
                }
                ConnectionChoice.WIFI_DIRECT -> add(R.string.hotspot_mode_p2p_desc)
            }
            "automatic" -> add(R.string.settings_footer_automatic)
            "wifi" -> {
                add(R.string.wifi_high_performance_description)
                if (env.connection == ConnectionChoice.WIFI_DIRECT && env.sdkInt < 29) add(R.string.wifi_direct_band_desc)
            }
            "screen" -> add(R.string.settings_footer_screen)
            "picture" -> add(R.string.settings_footer_picture)
            "overlay" -> add(R.string.overlay_video_renderer_description)
            "colour" -> add(R.string.settings_footer_colour_range)
            "corners" -> add(R.string.plain_display_area_description)
            "wifi_audio" -> add(R.string.carplay_wifi_audio_description)
            "calls" -> add(R.string.calls_over_carplay_only_description)
            "buffer" -> add(R.string.settings_footer_buffer)
            "navigation" -> add(R.string.contrib_audio_home_nav_channel_note)
            "cluster" -> add(R.string.mg4_cluster_directions_description)
            "cluster_map" -> add(R.string.mg4_cluster_map_description)
            "cluster_lanes" -> add(R.string.mg4_cluster_lanes_description)
            "cluster_song" -> add(R.string.mg4_cluster_song_description)
            "carplay_wheel" -> add(R.string.mg4_carplay_wheel_description)
            "location" -> add(R.string.sends_precise_android_location_as_carplay_gps_data_when_th)
            "parked" -> add(R.string.settings_footer_video_while_parked)
            "appearance" -> add(R.string.mg4_sunset_dark_mode_description)
            "battery" -> add(R.string.settings_footer_battery)
            "abrp" -> add(R.string.abrp_live_telemetry_description)
            "system" -> add(R.string.nearby_devices_connects_your_iphone_microphone_enables_sir)
            "diagnostics" -> {
                val hint = ui.groupFooter(activity.getString(R.string.download_to_iphone_unavailable))
                content.addView(hint)
                val save = saveReportRow; val download = downloadReportRow
                if (save != null && download != null) host.bindReportControls(save, download, hint)
                add(activity.getString(R.string.reports_save_to_downloads_diplay) + activity.getString(R.string.nothing_is_sent_automatically_protocol_payloads_and_creden))
            }
            "about" -> add(R.string.an_independent_carplay_receiver_for_android_head_units_wir)
            "licences" -> add(R.string.receiver_based_on_xcertplay_licensed_under_gpl_3_0_diplay)
        }
    }

    /** The report rows, bound to the activity once the diagnostics footer's hint exists. */
    private var saveReportRow: TextView? = null
    private var downloadReportRow: TextView? = null

    private fun rowTitle(row: SettingRow): String = activity.getString(when (row) {
        SettingRow.CONNECTION_TYPE -> R.string.settings_connection_type
        SettingRow.HOTSPOT_DETAILS -> R.string.settings_hotspot_details
        SettingRow.IPHONE -> R.string.settings_iphone
        SettingRow.AUTO_CONNECT -> R.string.connect_when_diplay_opens
        SettingRow.OPEN_AFTER_START -> R.string.open_after_the_car_starts
        SettingRow.WIFI_HIGH_PERFORMANCE -> R.string.wifi_high_performance
        SettingRow.WIFI_DIRECT_BAND -> R.string.wifi_direct_band
        SettingRow.OPEN_CAR_WIFI ->
            if (connectionChoice() == ConnectionChoice.CAR_HOTSPOT) R.string.open_car_hotspot_settings else R.string.open_car_wi_fi_settings
        SettingRow.CARPLAY_SIZE -> R.string.carplay_size
        SettingRow.RIGHT_HAND_DRIVE -> R.string.right_hand_drive
        SettingRow.FULL_SCREEN -> R.string.full_screen
        SettingRow.RESOLUTION -> R.string.resolution
        SettingRow.FRAME_RATE -> R.string.frame_rate
        SettingRow.EFFICIENT_VIDEO -> R.string.efficient_video
        SettingRow.DISPLAY_ADVANCED, SettingRow.SOUND_ADVANCED -> R.string.settings_advanced
        SettingRow.OVERLAY_RENDERER -> R.string.overlay_video_renderer
        SettingRow.COLOUR_RANGE -> R.string.video_colour_range
        SettingRow.SQUARE_CORNERS -> R.string.plain_display_area
        SettingRow.WIFI_AUDIO -> R.string.carplay_wifi_audio
        SettingRow.CALLS_OVER_CARPLAY -> R.string.calls_over_carplay_only
        SettingRow.MUSIC_BUFFER -> R.string.music_buffer
        SettingRow.MICROPHONE -> R.string.microphone_source
        SettingRow.NAVIGATION_CHANNEL -> R.string.contrib_audio_home_nav_channel_label
        SettingRow.CLUSTER_DIRECTIONS -> R.string.mg4_cluster_directions
        SettingRow.CLUSTER_MAP -> R.string.mg4_cluster_map
        SettingRow.CLUSTER_MAP_SIZE -> R.string.mg4_cluster_map_size
        SettingRow.CLUSTER_LANES -> R.string.mg4_cluster_lanes
        SettingRow.CLUSTER_SONG -> R.string.mg4_cluster_song
        SettingRow.CLUSTER_ALBUM_ART -> R.string.mg4_cluster_album_art
        SettingRow.CARPLAY_WHEEL -> R.string.mg4_carplay_wheel
        SettingRow.SEND_LOCATION -> R.string.settings_send_location
        SettingRow.VIDEO_WHILE_PARKED -> R.string.mg4_video_while_parked
        SettingRow.SUNSET_DARK_MODE -> R.string.mg4_sunset_dark_mode
        SettingRow.CAR_BATTERY -> R.string.settings_car_battery
        SettingRow.CHARGING_CONNECTORS -> R.string.charging_connectors
        SettingRow.LOW_CHARGE_WARNING -> R.string.low_charge_warning
        SettingRow.ABRP_TELEMETRY -> R.string.abrp_live_telemetry
        SettingRow.ABRP_ACCOUNT -> R.string.settings_abrp_account
        SettingRow.LANGUAGE -> R.string.language_section_title
        SettingRow.SOFTWARE_UPDATE -> R.string.settings_software_update
        SettingRow.APP_PERMISSIONS -> R.string.app_permissions
        SettingRow.BLUETOOTH -> R.string.bluetooth_settings
        SettingRow.SAVE_REPORT -> R.string.save_diagnostic_report
        SettingRow.DOWNLOAD_REPORT -> R.string.download_to_iphone
        SettingRow.REPORT_LOCATION -> R.string.choose_save_location
        SettingRow.WIRELESS_HELP -> R.string.wireless_connection_help
        SettingRow.VERSION -> R.string.settings_version
        SettingRow.CREDITS -> R.string.settings_credits
    })

    // ---- Rows ----

    private fun rowView(row: SettingRow, env: SettingsEnvironment, group: LinearLayout): View? {
        val title = rowTitle(row)
        fun switch(value: Boolean, save: (Boolean) -> Unit): View {
            val (built, _) = ui.switchRow(title, value) { on -> save(on); changed(row, if (on) "on" else "off") }
            return keep(row, built)
        }
        fun link(value: String?, warning: Boolean = false, onClick: () -> Unit) =
            keep(row, ui.valueRow(title, value, chevron = true, warning = warning, onClick = onClick))
        fun segmented(options: List<String>, selected: Int, save: (Int) -> Unit) =
            keep(row, ui.segmentedRow(title, options, selected) { index -> save(index); changed(row, options[index]) })
        return when (row.kind) {
            RowKind.PICKER -> picker(row)!!.let { spec ->
                link(spec.options.getOrNull(spec.selected)) { push(row) }
            }
            RowKind.PAGE -> link(null) { push(row) }
            else -> when (row) {
                SettingRow.HOTSPOT_DETAILS -> link(
                    if (hotspotSaved()) AirPlayPersistence.loadManualHotspotSsid(activity) else activity.getString(R.string.settings_not_set),
                    warning = !hotspotSaved() || host.hotspotSetupPending,
                ) { host.editHotspotDetails { changed(row, "saved"); host.render() } }
                SettingRow.IPHONE -> link(
                    if (DiPlayPreferences.phoneAddress(activity) != null) DiPlayPreferences.phoneName(activity) else activity.getString(R.string.settings_none),
                ) { host.choosePhone() }
                SettingRow.AUTO_CONNECT -> switch(DiPlayPreferences.autoConnect(activity)) { DiPlayPreferences.saveAutoConnect(activity, it) }
                SettingRow.OPEN_AFTER_START -> switch(AirPlayPersistence.loadAutoStartOnBoot(activity)) { AirPlayPersistence.saveAutoStartOnBoot(activity, it) }
                SettingRow.WIFI_HIGH_PERFORMANCE -> switch(AirPlayPersistence.loadWifiHighPerformanceEnabled(activity)) {
                    AirPlayPersistence.saveWifiHighPerformanceEnabled(activity, it)
                }
                SettingRow.OPEN_CAR_WIFI -> link(null) {
                    if (env.connection == ConnectionChoice.CAR_HOTSPOT) host.openCarHotspotSettings() else host.openCarWifiSettings()
                }
                SettingRow.RIGHT_HAND_DRIVE -> switch(AirPlayPersistence.loadRightHandDrive(activity)) { AirPlayPersistence.saveRightHandDrive(activity, it) }
                SettingRow.FULL_SCREEN -> switch(AirPlayPersistence.loadHideTopBar(activity) && AirPlayPersistence.loadHideBottomBar(activity)) {
                    AirPlayPersistence.saveHideTopBar(activity, it); AirPlayPersistence.saveHideBottomBar(activity, it)
                }
                SettingRow.RESOLUTION -> {
                    val scales = listOf(10, 8, 6)
                    segmented(listOf(activity.getString(R.string.settings_resolution_native),
                        activity.getString(R.string.percent_value, 80), activity.getString(R.string.percent_value, 60)),
                        scales.indexOf(AirPlayPersistence.loadDisplayScaleTenths(activity)).coerceAtLeast(0)) {
                        AirPlayPersistence.saveDisplayScaleTenths(activity, scales[it])
                    }
                }
                SettingRow.FRAME_RATE -> {
                    val rates = listOf(30, 60)
                    segmented(rates.map { activity.getString(R.string.settings_fps, it) },
                        if (AirPlayPersistence.loadFps(activity) == 60) 1 else 0) { AirPlayPersistence.saveFps(activity, rates[it]) }
                }
                SettingRow.EFFICIENT_VIDEO -> switch(AirPlayPersistence.loadHevcChoice(activity) ?: true) { AirPlayPersistence.saveHevcEnabled(activity, it) }
                SettingRow.OVERLAY_RENDERER -> switch(AirPlayPersistence.loadSurfaceVideoRenderer(activity) ?: profile.preferSurfaceVideo) {
                    AirPlayPersistence.saveSurfaceVideoRenderer(activity, it)
                }
                SettingRow.SQUARE_CORNERS -> switch(AirPlayPersistence.loadPlainDisplayArea(activity) ?: profile.squareCorners) {
                    AirPlayPersistence.savePlainDisplayArea(activity, it)
                }
                SettingRow.WIFI_AUDIO -> switch((AirPlayPersistence.loadAudioMode(activity) ?: profile.defaultAudioMode) == AudioMode.CARPLAY_WIFI) {
                    AirPlayPersistence.saveAudioMode(activity, if (it) AudioMode.CARPLAY_WIFI else AudioMode.HYBRID_BLUETOOTH)
                }
                SettingRow.CALLS_OVER_CARPLAY -> switch(AirPlayPersistence.loadCallsOverCarPlayOnly(activity)) {
                    AirPlayPersistence.saveCallsOverCarPlayOnly(activity, it)
                }
                SettingRow.MUSIC_BUFFER -> {
                    val presets = MediaAudioBuffer.presets
                    segmented(presets.map { activity.getString(R.string.settings_milliseconds, it) },
                        presets.indexOf(AirPlayPersistence.loadMediaBufferMillis(activity)).coerceAtLeast(0)) {
                        AirPlayPersistence.saveMediaBufferMillis(activity, presets[it])
                    }
                }
                SettingRow.NAVIGATION_CHANNEL -> link(AirPlayPersistence.loadNavigationAudioChannel(activity).toString()) {
                    host.editNavigationChannel { changed(row, AirPlayPersistence.loadNavigationAudioChannel(activity).toString()); host.render() }
                }
                SettingRow.CLUSTER_DIRECTIONS -> switch(AirPlayPersistence.loadClusterDirections(activity)) { AirPlayPersistence.saveClusterDirections(activity, it) }
                SettingRow.CLUSTER_MAP_SIZE -> {
                    val sizes = CarPlayClusterDisplay.Size.entries
                    segmented(
                        listOf(R.string.mg4_cluster_map_size_large, R.string.mg4_cluster_map_size_medium, R.string.mg4_cluster_map_size_small).map(activity::getString),
                        sizes.indexOf(AirPlayPersistence.loadClusterMapSize(activity)),
                    ) { AirPlayPersistence.saveClusterMapSize(activity, sizes[it]) }
                }
                SettingRow.CLUSTER_LANES -> {
                    val modes = AirPlayPersistence.LaneGuidance.entries
                    segmented(
                        listOf(R.string.mg4_cluster_map_off, R.string.mg4_cluster_lanes_car, R.string.mg4_cluster_lanes_drawn).map(activity::getString),
                        modes.indexOf(AirPlayPersistence.loadClusterLanes(activity)),
                    ) { AirPlayPersistence.saveClusterLanes(activity, modes[it]) }
                }
                SettingRow.CLUSTER_SONG -> switch(AirPlayPersistence.loadClusterSong(activity)) { AirPlayPersistence.saveClusterSong(activity, it) }
                SettingRow.CLUSTER_ALBUM_ART -> switch(AirPlayPersistence.loadClusterAlbumArt(activity)) { AirPlayPersistence.saveClusterAlbumArt(activity, it) }
                SettingRow.CARPLAY_WHEEL -> switch(AirPlayPersistence.loadCarPlayWheel(activity)) {
                    AirPlayPersistence.saveCarPlayWheel(activity, it)
                    com.shilapi.xcertplay.CarPlayWheel.setServiceEnabled(activity, it)
                }
                SettingRow.CLUSTER_MAP -> {
                    val choices = listOf(null) + CarPlayClusterDisplay.Content.entries
                    segmented(
                        listOf(R.string.mg4_cluster_map_off, R.string.mg4_cluster_map_map, R.string.mg4_cluster_map_turn_card).map(activity::getString),
                        choices.indexOf(AirPlayPersistence.loadClusterMap(activity)).coerceAtLeast(0),
                    ) { AirPlayPersistence.saveClusterMap(activity, choices[it]) }
                }
                SettingRow.SEND_LOCATION -> switch(AirPlayPersistence.loadLocationReportingEnabled(activity)) { host.setLocationReporting(it) }
                SettingRow.VIDEO_WHILE_PARKED -> switch(VehicleSettings.videoWhileParked(activity)) { VehicleSettings.setVideoWhileParked(activity, it) }
                SettingRow.SUNSET_DARK_MODE -> switch(AirPlayPersistence.loadSunsetDarkMode(activity)) { AirPlayPersistence.saveSunsetDarkMode(activity, it) }
                SettingRow.CAR_BATTERY -> switch(VehicleSettings.batteryToIphone(activity)) {
                    VehicleSettings.setBatteryToIphone(activity, it)
                    if (it) Mg4Vehicle.start(activity.applicationContext)
                }
                SettingRow.ABRP_TELEMETRY -> {
                    val (built, toggle) = ui.switchRow(title, AbrpSettings.enabled(activity)) { on ->
                        changed(row, if (on) "on" else "off")
                        host.abrpTelemetryChanged(on)
                    }
                    // Without an account the switch stays off; the account dialog turns it on when saved.
                    if (!AbrpSettings.configured(activity)) toggle.setOnCheckedChangeListener { view, on ->
                        if (on) { view.isChecked = false; host.editAbrpAccount() }
                    }
                    keep(row, built)
                }
                SettingRow.ABRP_ACCOUNT -> {
                    val ready = AbrpSettings.configured(activity)
                    link(activity.getString(if (ready) R.string.settings_set_up else R.string.settings_not_set), warning = !ready) { host.editAbrpAccount() }
                }
                SettingRow.LANGUAGE -> link(AppLocale.displayName(activity, AppLocale.preference(activity))) { AppLocale.showPicker(activity) }
                SettingRow.SOFTWARE_UPDATE -> link(host.version()) { host.checkForUpdates() }
                SettingRow.APP_PERMISSIONS -> link(null) { host.openAppPermissions() }
                SettingRow.BLUETOOTH -> link(null) { host.openBluetoothSettings() }
                SettingRow.SAVE_REPORT -> ui.actionRow(title) { host.saveReport() }.also { saveReportRow = it }
                SettingRow.DOWNLOAD_REPORT -> ui.actionRow(title) { host.downloadReport() }.also { downloadReportRow = it }
                SettingRow.REPORT_LOCATION -> ui.actionRow(title) { host.chooseReportLocation() }
                SettingRow.WIRELESS_HELP -> link(null) { host.wirelessHelp() }
                SettingRow.VERSION -> keep(row, ui.valueRow(title, host.version(), chevron = false, onClick = null))
                SettingRow.CREDITS -> keep(row, ui.valueRow(title, activity.getString(R.string.settings_credits_value), chevron = false, onClick = null))
                else -> null
            }
        }
    }

    private fun keep(row: SettingRow, built: SettingsUi.Row): View {
        built.tag?.let { tag ->
            tags[row] = tag
            if (row in changed) tag.visibility = View.VISIBLE
        }
        return built.view
    }

    private fun changed(row: SettingRow, value: String) {
        SettingsChangeLog.record(activity, row, value)
        if (row.appliesOnReconnect && host.sessionRunning) {
            changed += row
            tags[row]?.visibility = View.VISIBLE
        }
        refresh()
    }

    private fun push(row: SettingRow) {
        rememberScroll()
        stack += row
        host.render()
    }

    // ---- Pickers ----

    private fun pickerPage(content: LinearLayout, row: SettingRow) {
        val spec = picker(row) ?: return
        val box = ui.group()
        spec.options.forEachIndexed { index, option ->
            ui.addRow(box, ui.optionRow(option, index == spec.selected) {
                stack.removeAt(stack.lastIndex)
                if (index != spec.selected) { spec.save(index); changed(row, option) }
                host.render()
            })
        }
        content.addView(box, LinearLayout.LayoutParams(-1, -2))
        when (row) {
            SettingRow.COLOUR_RANGE -> R.string.settings_footer_colour_range
            SettingRow.WIFI_DIRECT_BAND -> R.string.wifi_direct_band_desc
            else -> null
        }?.let { content.addView(ui.groupFooter(activity.getString(it))) }
    }

    private fun picker(row: SettingRow): Picker? = when (row) {
        SettingRow.CONNECTION_TYPE -> Picker(rowTitle(row),
            listOf(activity.getString(R.string.settings_car_hotspot), activity.getString(R.string.wifi_direct)),
            connectionChoice().ordinal) { index ->
            when (ConnectionChoice.entries[index]) {
                ConnectionChoice.CAR_HOTSPOT -> if (hotspotSaved()) useCarHotspot() else host.editHotspotDetails { useCarHotspot(); host.render() }
                ConnectionChoice.WIFI_DIRECT -> {
                    AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.WIFI_P2P)
                    AirPlayPersistence.saveWirelessEnabled(activity, true)
                }
            }
        }
        SettingRow.WIFI_DIRECT_BAND -> {
            val bands = listOf(ManualHotspotBand.AUTO, ManualHotspotBand.GHZ_2_4)
            Picker(rowTitle(row), listOf(activity.getString(R.string.wifi_direct_band_auto), activity.getString(R.string.wifi_direct_band_2_4)),
                bands.indexOf(AirPlayPersistence.loadWifiDirectBand(activity)).coerceAtLeast(0)) {
                AirPlayPersistence.saveWifiDirectBand(activity, bands[it])
            }
        }
        SettingRow.CARPLAY_SIZE -> {
            val sizes = CarPlaySize.entries
            Picker(rowTitle(row), sizes.map { it.localizedLabel(activity) },
                sizes.indexOf(CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(activity)))) {
                AirPlayPersistence.saveWidthPhysicalMm(activity, sizes[it].widthMillimeters)
            }
        }
        SettingRow.COLOUR_RANGE -> Picker(rowTitle(row), listOf(
            activity.getString(R.string.video_colour_range_stream),
            activity.getString(R.string.video_colour_range_limited),
            activity.getString(R.string.video_colour_range_full),
        ), AirPlayPersistence.loadVideoColourRange(activity)) { AirPlayPersistence.saveVideoColourRange(activity, it) }
        SettingRow.MICROPHONE -> Picker(rowTitle(row), listOf(
            activity.getString(R.string.microphone_source_automatic),
            activity.getString(R.string.microphone_source_voice),
            activity.getString(R.string.microphone_source_plain),
        ), AirPlayPersistence.loadMicrophoneSource(activity)) { AirPlayPersistence.saveMicrophoneSource(activity, it) }
        SettingRow.CHARGING_CONNECTORS -> {
            val connectors = EvChargingConnectors.entries
            Picker(rowTitle(row), connectors.map { it.localizedLabel(activity) },
                connectors.indexOf(VehicleSettings.chargingConnectors(activity))) {
                VehicleSettings.setChargingConnectors(activity, connectors[it])
            }
        }
        SettingRow.LOW_CHARGE_WARNING -> {
            val presets = VehicleSettings.lowChargePresets
            Picker(rowTitle(row), presets.map {
                activity.getString(if (it == VehicleSettings.DEFAULT_LOW_CHARGE_PERCENT) R.string.percent_default else R.string.percent_value, it)
            }, presets.indexOf(VehicleSettings.lowChargePercent(activity)).coerceAtLeast(0)) {
                VehicleSettings.setLowChargePercent(activity, presets[it])
            }
        }
        else -> null
    }

    private fun useCarHotspot() {
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveWirelessEnabled(activity, true)
    }

    private fun hotspotSaved() = ManualHotspotValidation.error(
        AirPlayPersistence.loadManualHotspotSsid(activity), AirPlayPersistence.loadManualHotspotPassphrase(activity)) == null

    private fun connectionChoice() = when {
        AirPlayPersistence.loadWirelessHotspotMode(activity) == WirelessHotspotMode.MANUAL -> ConnectionChoice.CAR_HOTSPOT
        else -> ConnectionChoice.WIFI_DIRECT
    }

    private companion object {
        const val STATE_CATEGORY = "settings_category"
        const val STATE_STACK = "settings_stack"
    }
}
