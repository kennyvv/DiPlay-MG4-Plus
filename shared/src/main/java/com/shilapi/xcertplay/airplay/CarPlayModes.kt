package com.shilapi.xcertplay.airplay

/**
 * CarPlay's resource and app-state hand-over between the car and the iPhone (AirPlay `changeModes`
 * from the car, `modesChanged` from the iPhone).
 *
 * The numbers are read from the SWI68 firmware (SWI68-29958-1300R59): `libAirPlay.so`
 * `AirPlayCreateModesDictionary` writes appStateID 1 with `speechMode`, 2 (phone call) and 3
 * (turn-by-turn) with `state`; `libCarPlay.so` maps the stock app's transfer types 1:1 and its
 * priorities to 100/500 and constraints to 100/500/1000. The rules for when to send what follow the
 * stock AllGo app (`CarPlayScreenHandler`, `CarPlayAudioManager`).
 */
object CarPlayModes {
    const val RESOURCE_SCREEN = 1
    const val RESOURCE_MAIN_AUDIO = 2

    const val ENTITY_NONE = 0
    const val ENTITY_CONTROLLER = 1
    const val ENTITY_ACCESSORY = 2

    const val TRANSFER_TAKE = 1
    const val TRANSFER_UNTAKE = 2
    const val TRANSFER_BORROW = 3
    const val TRANSFER_UNBORROW = 4

    const val PRIORITY_NICE_TO_HAVE = 100
    const val PRIORITY_USER_INITIATED = 500

    const val CONSTRAINT_ANYTIME = 100
    const val CONSTRAINT_USER_INITIATED = 500
    const val CONSTRAINT_NEVER = 1000

    const val APP_STATE_SPEECH = 1
    const val APP_STATE_PHONE_CALL = 2
    const val APP_STATE_TURN_BY_TURN = 3

    const val SPEECH_NONE = -1
    const val SPEECH_SPEAKING = 1
    const val SPEECH_RECOGNIZING = 2

    /** One resource change the car asks for. */
    data class Change(
        val resourceId: Int,
        val transferType: Int,
        val priority: Int = PRIORITY_USER_INITIATED,
        val takeConstraint: Int = CONSTRAINT_ANYTIME,
        val borrowConstraint: Int = CONSTRAINT_ANYTIME,
    ) {
        fun toMap(): Map<String, Any?> {
            val entry = linkedMapOf<String, Any?>(
                "resourceID" to resourceId.toLong(),
                "transferType" to transferType.toLong(),
            )
            // Giving a resource back carries no priority or constraints (stock sends only the type).
            if (transferType == TRANSFER_TAKE || transferType == TRANSFER_BORROW) {
                entry["transferPriority"] = priority.toLong()
                entry["takeConstraint"] = takeConstraint.toLong()
                if (transferType == TRANSFER_TAKE) {
                    entry["borrowConstraint"] = borrowConstraint.toLong()
                } else {
                    entry["unborrowConstraint"] = borrowConstraint.toLong()
                }
            }
            return entry
        }

        fun describe(): String =
            "${resourceName(resourceId)} ${transferName(transferType)}" +
                if (transferType == TRANSFER_TAKE || transferType == TRANSFER_BORROW) {
                    " priority=$priority take=$takeConstraint ${if (transferType == TRANSFER_TAKE) "borrow" else "unborrow"}=$borrowConstraint"
                } else {
                    ""
                }
    }

    /** The `changeModes` command for [changes]. */
    fun command(changes: List<Change>, reason: String): Map<String, Any?> = linkedMapOf(
        "type" to "changeModes",
        "params" to linkedMapOf(
            "resources" to changes.map(Change::toMap),
            "reasonStr" to reason,
        ),
    )

    /** Ask the iPhone to show CarPlay ([url] null: its home screen; or "maps:", "mobilephone:", "music:", "nowplaying:"). */
    fun requestUi(url: String? = null): Map<String, Any?> =
        if (url == null) linkedMapOf("type" to "requestUI") else linkedMapOf("type" to "requestUI", "params" to linkedMapOf("url" to url))

    /** You left CarPlay for the car's own screens: the car takes the screen until you come back (stock onActivityStopped). */
    val CAR_TAKES_SCREEN = Change(RESOURCE_SCREEN, TRANSFER_TAKE, PRIORITY_USER_INITIATED, CONSTRAINT_USER_INITIATED, CONSTRAINT_ANYTIME)

    /** Another car source (the radio, Bluetooth music) took the car's audio for good (stock focus loss). */
    val CAR_TAKES_AUDIO = Change(RESOURCE_MAIN_AUDIO, TRANSFER_TAKE, PRIORITY_USER_INITIATED, CONSTRAINT_USER_INITIATED, CONSTRAINT_ANYTIME)

    /** Another car source interrupts CarPlay audio for a moment (stock transient focus loss). */
    val CAR_BORROWS_AUDIO = Change(RESOURCE_MAIN_AUDIO, TRANSFER_BORROW, PRIORITY_USER_INITIATED, CONSTRAINT_ANYTIME, CONSTRAINT_ANYTIME)

    /** Who holds what, from the iPhone's `modesChanged`. Null where the iPhone said nothing. */
    data class State(
        val screen: Int? = null,
        val mainAudio: Int? = null,
        val speech: Int? = null,
        val speechMode: Int? = null,
        val phoneCall: Int? = null,
        val turnByTurn: Int? = null,
    ) {
        val carPlayHasAudio: Boolean? get() = mainAudio?.let { it == ENTITY_CONTROLLER }
        val navigating: Boolean get() = turnByTurn == ENTITY_CONTROLLER
        val siriActive: Boolean get() = speech == ENTITY_CONTROLLER && (speechMode == SPEECH_SPEAKING || speechMode == SPEECH_RECOGNIZING)
        val onCall: Boolean get() = phoneCall == ENTITY_CONTROLLER

        fun describe(): String =
            "screen=${entityName(screen)} mainAudio=${entityName(mainAudio)} " +
                "speech=${entityName(speech)}(${speechName(speechMode)}) phoneCall=${entityName(phoneCall)} " +
                "turnByTurn=${entityName(turnByTurn)}"
    }

    /**
     * Reads `modesChanged`: `resources` (resourceID, entity) and `appStates` (appStateID, entity, speechMode),
     * the keys `AirPlayReceiverSessionMakeModeStateFromDictionary` reads. A speech entry is also recognised by its
     * `speechMode` key, in case its id differs.
     */
    fun parse(params: Map<String, Any?>): State {
        var state = State()
        (params["resources"] as? List<*>)?.forEach { item ->
            val entry = item as? Map<*, *> ?: return@forEach
            val entity = number(entry["entity"]) ?: return@forEach
            state = when (number(entry["resourceID"])) {
                RESOURCE_SCREEN -> state.copy(screen = entity)
                RESOURCE_MAIN_AUDIO -> state.copy(mainAudio = entity)
                else -> state
            }
        }
        (params["appStates"] as? List<*>)?.forEach { item ->
            val entry = item as? Map<*, *> ?: return@forEach
            val entity = number(entry["entity"])
            val mode = number(entry["speechMode"])
            state = when {
                mode != null || number(entry["appStateID"]) == APP_STATE_SPEECH -> state.copy(speech = entity, speechMode = mode)
                number(entry["appStateID"]) == APP_STATE_PHONE_CALL -> state.copy(phoneCall = entity)
                number(entry["appStateID"]) == APP_STATE_TURN_BY_TURN -> state.copy(turnByTurn = entity)
                else -> state
            }
        }
        return state
    }

    /** The raw `modesChanged` params for the log, so a car test shows exactly what the iPhone sent. */
    fun raw(params: Map<String, Any?>): String = params.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" }

    private fun number(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is Boolean -> if (value) 1 else 0
        else -> null
    }

    fun entityName(entity: Int?): String = when (entity) {
        null -> "?"
        ENTITY_NONE -> "none"
        ENTITY_CONTROLLER -> "iPhone"
        ENTITY_ACCESSORY -> "car"
        else -> entity.toString()
    }

    private fun speechName(mode: Int?): String = when (mode) {
        null -> "?"
        SPEECH_NONE, 0 -> "none"
        SPEECH_SPEAKING -> "speaking"
        SPEECH_RECOGNIZING -> "listening"
        else -> mode.toString()
    }

    private fun resourceName(id: Int): String = when (id) {
        RESOURCE_SCREEN -> "screen"
        RESOURCE_MAIN_AUDIO -> "mainAudio"
        else -> "resource$id"
    }

    private fun transferName(type: Int): String = when (type) {
        TRANSFER_TAKE -> "take"
        TRANSFER_UNTAKE -> "untake"
        TRANSFER_BORROW -> "borrow"
        TRANSFER_UNBORROW -> "unborrow"
        else -> "transfer$type"
    }
}

/**
 * What the car holds of one resource, so giving it back sends the matching untake or unborrow, a repeat
 * is not sent twice, and a resource the iPhone took back (`modesChanged`) is not given back again.
 */
class CarPlayModeHandover {
    private val held = HashMap<Int, Int>()

    /** The change to send for [change], or null when the car already holds the resource that way. */
    @Synchronized
    fun hold(change: CarPlayModes.Change): CarPlayModes.Change? {
        val current = held[change.resourceId]
        // A take is stronger than a borrow; a borrow never weakens a take.
        if (current == CarPlayModes.TRANSFER_TAKE) return null
        if (current == change.transferType) return null
        held[change.resourceId] = change.transferType
        return change
    }

    /** The untake or unborrow for [resourceId], or null when the car holds nothing. */
    @Synchronized
    fun release(resourceId: Int): CarPlayModes.Change? {
        val current = held.remove(resourceId) ?: return null
        val type = if (current == CarPlayModes.TRANSFER_TAKE) CarPlayModes.TRANSFER_UNTAKE else CarPlayModes.TRANSFER_UNBORROW
        return CarPlayModes.Change(resourceId, type)
    }

    /** The iPhone reported who holds what: forget what the car no longer holds. */
    @Synchronized
    fun onState(state: CarPlayModes.State) {
        if (state.screen != null && state.screen != CarPlayModes.ENTITY_ACCESSORY) held.remove(CarPlayModes.RESOURCE_SCREEN)
        if (state.mainAudio != null && state.mainAudio != CarPlayModes.ENTITY_ACCESSORY) held.remove(CarPlayModes.RESOURCE_MAIN_AUDIO)
    }

    @Synchronized
    fun holds(resourceId: Int): Boolean = held.containsKey(resourceId)

    @Synchronized
    fun clear() = held.clear()
}
