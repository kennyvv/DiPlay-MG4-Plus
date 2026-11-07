package com.shilapi.xcertplay.vehicle

import android.content.Context
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import com.shilapi.xcertplay.transport.VehicleStatusProvider

/** The MG4 read path, kept apart from the CarPlay host. On other firmware there is no car data. */
object VehicleIntegration {
    fun isMg4(context: Context): Boolean = Mg4Vehicle.available(context)

    /** MG4 has a native read-only telemetry source, so EV routing is always on there. */
    fun vehicleStatusEnabled(context: Context): Boolean {
        if (!isMg4(context)) return false
        Mg4Vehicle.start(context)
        return true
    }

    fun onAppOpened(context: Context) {
        runCatching {
            if (isMg4(context)) {
                // Advertise the MG4 as an EV by default so Apple Maps can ask for live vehicle status.
                VehicleSettings.enableBatteryToIphoneByDefault(context)
                Mg4Vehicle.start(context)
            }
        }
    }

    fun batteryStatus(context: Context): VehicleStatusProvider = Mg4Vehicle.also { it.start(context) }

    /** MG4 reads speed directly, so it always goes with location. */
    fun wheelSpeedEnabled(context: Context): Boolean = isMg4(context)

    fun wheelSpeed(context: Context): VehicleSpeedSource = Mg4WheelSpeedSource

    /** Sends MG4 wheel-speed, gear and charging lines to [log] (the session log), or stops with null. */
    fun setReportLog(log: ((String) -> Unit)?) {
        Mg4WheelSpeedSource.reportLog = log ?: {}
        Mg4Vehicle.reportLog = log ?: {}
    }

    fun parked(context: Context): Boolean? = if (isMg4(context)) Mg4Vehicle.parked(context) else null

    /** The page the instrument cluster shows (1 navigation, 2 media), or null when unreadable. */
    fun clusterTab(): Int? = runCatching { com.evsuite.hardware.EVHardware.clusterTab() }.getOrNull()
}
