package com.shilapi.xcertplay.vehicle

internal data class RouteManeuver(
    val distanceMeters: Int,
    val type: Int,
    val drivingSide: Int,
    val road: String = "",
    val remainingSeconds: Long? = null,
    val remainingMeters: Long? = null,
    val arrivalEpochSeconds: Long? = null,
)

internal enum class RouteChange {
    NONE,
    GUIDANCE,
    CLEAR,
}

/** Decodes the iAP2 route-guidance subset the MG4 cluster needs. */
internal class RouteGuidanceState(private val nanoTime: () -> Long = System::nanoTime) {
    private data class Maneuver(val type: Int, val drivingSide: Int, val afterRoad: String)

    private val maneuvers = mutableMapOf<Int, Maneuver>()
    private var routeActive = false
    private var activeIndex = -1
    private var distanceMeters = 0
    // What CarPlay shows for the next turn (TLVs 0x0b string, 0x0c units), as the iPhone sent it.
    private var distanceText: String? = null
    private var distanceUnits: Int? = null
    /** For the log: raw distance, the display text and units from the iPhone, and what is shown. */
    var distanceDescription = ""
        private set
    private var currentRoad = ""
    private var arrivalEpochSeconds: Long? = null
    private var remainingSeconds: Long? = null
    private var remainingMeters: Long? = null
    private var emptyListSinceNs: Long? = null
    private var lastRouteUpdateNs: Long? = null

    fun accept(messageId: Int, payload: ByteArray): RouteChange {
        if (!validTlvs(payload)) return RouteChange.NONE
        return when (messageId) {
            ROUTE_GUIDANCE_UPDATE -> parseRouteUpdate(payload)
            ROUTE_GUIDANCE_MANEUVER_UPDATE -> parseManeuverUpdate(payload)
            else -> RouteChange.NONE
        }
    }

    /** The next maneuver as the iPhone sent it, or null when there is no live guidance. */
    fun current(): RouteManeuver? {
        val maneuver = activeManeuver() ?: return null
        // The cluster gets the distance CarPlay shows (600, 500, ...), not the iPhone's raw
        // metre count, which changes every second (537, 512, ...).
        val shown = ManeuverDistance.shown(distanceMeters, distanceText, distanceUnits)
        distanceDescription = "raw=${distanceMeters}m phoneText=${distanceText?.let { "\"$it\"" } ?: "none"} " +
            "units=${distanceUnits ?: "none"} shown=${shown.meters}m via ${shown.source}"
        return RouteManeuver(
            shown.meters, maneuver.type, maneuver.drivingSide,
            roadFor(maneuver), remainingSeconds, remainingMeters, arrivalEpochSeconds,
        )
    }

    fun clear(): Boolean {
        val wasActive = routeActive
        routeActive = false
        activeIndex = -1
        distanceMeters = 0
        distanceText = null
        distanceUnits = null
        currentRoad = ""
        arrivalEpochSeconds = null
        remainingSeconds = null
        remainingMeters = null
        emptyListSinceNs = null
        lastRouteUpdateNs = null
        maneuvers.clear()
        return wasActive
    }

    private fun activeManeuver(): Maneuver? {
        if (!routeActive || activeIndex < 0) return null
        val updated = lastRouteUpdateNs ?: return null
        if (nanoTime() - updated >= STALE_ROUTE_NS) return null
        val emptySince = emptyListSinceNs
        if (emptySince != null && nanoTime() - emptySince >= EMPTY_LIST_HIDE_NS) return null
        return maneuvers[activeIndex]
    }

    // The road the driver turns onto is what the next instruction is about; fall back to the current one.
    private fun roadFor(maneuver: Maneuver): String = maneuver.afterRoad.ifEmpty { currentRoad }

    private fun parseRouteUpdate(data: ByteArray): RouteChange {
        lastRouteUpdateNs = nanoTime()
        var state: Int? = null
        var distance: Int? = null
        var text: String? = null
        var units: Int? = null
        var firstManeuver: Int? = null
        var listPresent = false
        forEachTlv(data) { type, value, valueLength ->
            when {
                type == 0x01 && valueLength >= 1 -> state = data[value].toInt() and 0xff
                type == 0x03 -> currentRoad = utf8(data, value, valueLength)
                type == 0x05 && valueLength >= 8 -> arrivalEpochSeconds = u64(data, value).takeIf { it > 0 }
                type == 0x06 && valueLength >= 8 -> remainingSeconds = u64(data, value).takeIf { it >= 0 }
                type == 0x07 && valueLength >= 4 -> remainingMeters = u32(data, value)
                type == 0x0a && valueLength >= 4 -> {
                    distance = u32(data, value).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }
                type == 0x0b -> text = utf8(data, value, valueLength)
                type == 0x0c && valueLength >= 1 -> units = data[value].toInt() and 0xff
                type == 0x0d -> {
                    listPresent = true
                    if (valueLength >= 2) firstManeuver = u16(data, value)
                }
            }
        }

        // Only NoRouteSet (0) and Arrived (2) end the route.
        if (state == 0 || state == 2) {
            return if (clear()) RouteChange.CLEAR else RouteChange.NONE
        }
        // The iPhone briefly sends an empty current list every few seconds and while rerouting. Keep the last
        // maneuver (and the cached 0x5202 details, which are never resent) and hide it only if the list stays
        // empty; the cluster's 1 s tick clears it once current() turns null.
        if (listPresent && firstManeuver == null) {
            if (emptyListSinceNs == null) emptyListSinceNs = nanoTime()
            return RouteChange.NONE
        }
        if (firstManeuver != null) emptyListSinceNs = null
        if (state != null) routeActive = true
        if (firstManeuver != null) {
            activeIndex = firstManeuver!!
            routeActive = true
        }
        if (distance != null) distanceMeters = distance!!.coerceAtLeast(0)
        // The text and units belong with the distance they came with; keep the last pair otherwise.
        if (text != null) distanceText = text
        if (units != null) distanceUnits = units
        return if (current() != null) RouteChange.GUIDANCE else RouteChange.NONE
    }

    private fun parseManeuverUpdate(data: ByteArray): RouteChange {
        var index: Int? = null
        var type: Int? = null
        var drivingSide = 0
        var afterRoad = ""
        forEachTlv(data) { id, value, valueLength ->
            when {
                id == 0x01 && valueLength >= 2 -> index = u16(data, value)
                id == 0x03 && valueLength >= 1 -> type = data[value].toInt() and 0xff
                id == 0x04 -> afterRoad = utf8(data, value, valueLength)
                id == 0x08 && valueLength >= 1 -> drivingSide = data[value].toInt() and 0xff
            }
        }
        if (index != null && type != null) maneuvers[index!!] = Maneuver(type!!, drivingSide, afterRoad)
        return if (current() != null) RouteChange.GUIDANCE else RouteChange.NONE
    }

    private inline fun forEachTlv(data: ByteArray, block: (Int, Int, Int) -> Unit) {
        var offset = 0
        while (offset < data.size) {
            val length = u16(data, offset)
            block(u16(data, offset + 2), offset + TLV_HEADER_BYTES, length - TLV_HEADER_BYTES)
            offset += length
        }
    }

    private fun validTlvs(data: ByteArray): Boolean {
        var offset = 0
        while (offset < data.size) {
            if (offset + TLV_HEADER_BYTES > data.size) return false
            val length = u16(data, offset)
            if (length < TLV_HEADER_BYTES || length > data.size - offset) return false
            offset += length
        }
        return offset == data.size
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private fun u32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xff) shl 24) or
            ((data[offset + 1].toLong() and 0xff) shl 16) or
            ((data[offset + 2].toLong() and 0xff) shl 8) or
            (data[offset + 3].toLong() and 0xff)

    private fun u64(data: ByteArray, offset: Int): Long = (u32(data, offset) shl 32) or u32(data, offset + 4)

    // iAP2 utf8 parameters are NUL-terminated.
    private fun utf8(data: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && data[end] != 0.toByte()) end++
        return String(data, offset, end - offset, Charsets.UTF_8).trim()
    }

    companion object {
        const val ROUTE_GUIDANCE_UPDATE = 0x5201
        const val ROUTE_GUIDANCE_MANEUVER_UPDATE = 0x5202
        private const val TLV_HEADER_BYTES = 4
        private const val STALE_ROUTE_NS = 30_000_000_000L
        private const val EMPTY_LIST_HIDE_NS = 3_000_000_000L
    }
}

/** The distance to the next turn as CarPlay shows it, which is what the cluster should show too. */
internal object ManeuverDistance {
    internal data class Shown(val meters: Int, val source: String)

    /** iAP2 DistanceToNextManeuverDisplayUnits, as read from the spec: 0 kilometres, 1 metres. Other units are not used in Australia. */
    private const val UNITS_KILOMETRES = 0
    private const val UNITS_METRES = 1

    fun shown(rawMeters: Int, text: String?, units: Int?): Shown {
        fromPhone(rawMeters, text, units)?.let { return Shown(it, "phone") }
        return Shown(rounded(rawMeters), "rounded")
    }

    /**
     * The iPhone's own display value in metres, or null if there is none or it does not sit near the
     * raw distance. The second check protects against a wrong guess about the units code: a value
     * that is far from the raw distance is ignored rather than shown.
     */
    fun fromPhone(rawMeters: Int, text: String?, units: Int?): Int? {
        if (text == null || units == null) return null
        val number = Regex("[0-9]+(?:[.,][0-9]+)?").find(text)?.value?.replace(',', '.')?.toDoubleOrNull() ?: return null
        val metres = when (units) {
            UNITS_KILOMETRES -> number * 1000.0
            UNITS_METRES -> number
            else -> return null
        }.let { Math.round(it).toInt() }
        val tolerance = maxOf(150, rawMeters * 35 / 100)
        return metres.takeIf { Math.abs(it - rawMeters) <= tolerance }
    }

    /** Fallback when the iPhone sends no display value: coarser steps further out, so the number does not flicker. */
    fun rounded(rawMeters: Int): Int {
        val step = when {
            rawMeters >= 500 -> 100
            rawMeters >= 100 -> 50
            else -> 10
        }
        return ((rawMeters + step / 2) / step) * step
    }
}
