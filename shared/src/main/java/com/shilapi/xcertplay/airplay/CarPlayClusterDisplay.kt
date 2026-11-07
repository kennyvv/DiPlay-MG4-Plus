package com.shilapi.xcertplay.airplay

/**
 * CarPlay's instrument-cluster screen (stream type 111), sized for the MG4 cluster square.
 *
 * On SWI68 and SWI165 the cluster shows a 260 px square from the head unit's second Android
 * display in its Navigation and Music pages; see `Mg4ClusterMap`. The iPhone lists the cluster
 * content it offers in its /info request (`altScreenURLs`); without an initial URL it streams a
 * black frame. The cluster has no input.
 *
 * Restored in MG4 form from upstream DiPlay's BYD cluster map (removed with the BYD code), where
 * the same stream drew Apple Maps on BYD clusters.
 */
object CarPlayClusterDisplay {
    enum class Content(val url: String) {
        /** The map with the next-turn card on it ("instrumentcluster" on BYD). */
        MAP("maps:/car/instrumentcluster"),
        /** Only the next-turn card: a few kbit/s against megabits for the map. */
        TURN_CARD("maps:/car/instrumentcluster/instructioncard"),
    }

    /** A multiple of 8 just over the 260 px square; the cluster window scales it to fit. */
    const val SIDE_PIXELS = 264

    // An estimate (not measured): 260 px of an 800x480 panel about 7 inches across. Apple Maps
    // ignored the physical size on BYD; it is sent to keep the panel's density plausible.
    private const val SIDE_PHYSICAL_MM = 49
    private const val FPS = 30

    fun config(content: Content): AirPlayDisplayConfig = AirPlayDisplayConfig(
        widthPixels = SIDE_PIXELS,
        heightPixels = SIDE_PIXELS,
        widthPhysicalMm = SIDE_PHYSICAL_MM,
        heightPhysicalMm = SIDE_PHYSICAL_MM,
        fps = FPS,
        primaryInputDevice = 0,
        features = 0,
        initialUrl = content.url,
        safeAreaDrawOutside = true,
    )
}
