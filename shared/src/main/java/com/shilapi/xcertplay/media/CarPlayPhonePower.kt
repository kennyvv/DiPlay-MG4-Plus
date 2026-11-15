package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** The iPhone's battery, from iAP2 PowerUpdate (0xAE01). Null fields: the iPhone has not said. */
data class CarPlayPhonePower(
    /** 0 to 100. */
    val batteryPercent: Int? = null,
    val charging: Charging? = null,
    /** A charger is connected (a cable, or the car's wireless charging pad). */
    val externalCharger: Boolean? = null,
) {
    enum class Charging { NOT_CHARGING, CHARGING, CHARGED }

    fun describe(): String =
        "battery=${batteryPercent?.let { "$it%" } ?: "?"} charging=${charging?.name?.lowercase() ?: "?"} " +
            "charger=${externalCharger ?: "?"}"
}

/**
 * Keeps the iPhone's battery from PowerUpdate (0xAE01), which DiPlay has asked for since the upstream identification
 * (StartPowerUpdates with IsExternalChargerConnected 4, BatteryChargingState 5 and BatteryChargeLevel 6). Updates carry
 * only what changed, so omitted fields keep their values.
 */
class CarPlayPhonePowerState {
    private var current = CarPlayPhonePower()
    private var known = false

    /** The power after [frame], or null when [frame] is not a PowerUpdate or nothing changed. */
    fun accept(frame: Iap2Frame): CarPlayPhonePower? {
        if (frame.messageId != POWER_UPDATE) return null
        val body = runCatching { Iap2BodyReader.of(frame) }.getOrNull() ?: return null
        var next = current
        runCatching { body.optionalBool(EXTERNAL_CHARGER) }.getOrNull()?.let { next = next.copy(externalCharger = it) }
        runCatching { body.optionalU8(CHARGING_STATE) }.getOrNull()?.let { state ->
            next = next.copy(
                charging = when (state) {
                    0 -> CarPlayPhonePower.Charging.NOT_CHARGING
                    1 -> CarPlayPhonePower.Charging.CHARGING
                    2 -> CarPlayPhonePower.Charging.CHARGED
                    else -> next.charging
                },
            )
        }
        runCatching { body.optionalU16(CHARGE_LEVEL) }.getOrNull()?.let { level ->
            next = next.copy(batteryPercent = percent(level))
        }
        if (known && next == current) return null
        known = true
        current = next
        return next
    }

    fun current(): CarPlayPhonePower? = current.takeIf { known }

    fun clear() {
        current = CarPlayPhonePower()
        known = false
    }

    companion object {
        const val POWER_UPDATE = 0xae01
        private const val EXTERNAL_CHARGER = 4
        private const val CHARGING_STATE = 5
        private const val CHARGE_LEVEL = 6

        /**
         * The iPhone sends BatteryChargeLevel as a plain percentage (0x0050 for 80 %, seen in the emulator with an
         * iPhone, 2026-11-15), although iAP2 allows 0-65535. A value above 100 is read on that wider scale.
         */
        fun percent(level: Int): Int =
            if (level in 0..100) level else ((level.coerceIn(0, 65535) * 100L + 32767) / 65535).toInt()
    }
}
