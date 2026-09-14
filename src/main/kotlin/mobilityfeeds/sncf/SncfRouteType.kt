package mobilityfeeds.sncf

import mobilityfeeds.gtfs.GtfsExtendedRouteType

// Brand or carrier as found in SNCF stop point ids (StopPoint:OCE<brand>-<uic8>), mapped to a GTFS extended route type
enum class SncfRouteType(
    val value: String,
    val gtfsExtended: GtfsExtendedRouteType,
) {
    TGV_INOUI("TGV INOUI", GtfsExtendedRouteType.HIGH_SPEED_RAIL_SERVICE),
    OUIGO("OUIGO", GtfsExtendedRouteType.HIGH_SPEED_RAIL_SERVICE),
    LYRIA("Lyria", GtfsExtendedRouteType.HIGH_SPEED_RAIL_SERVICE),
    ICE("ICE", GtfsExtendedRouteType.HIGH_SPEED_RAIL_SERVICE),
    INTERCITES("INTERCITES", GtfsExtendedRouteType.LONG_DISTANCE_TRAINS),
    INTERCITES_DE_NUIT("INTERCITES de nuit", GtfsExtendedRouteType.SLEEPER_RAIL_SERVICE),
    TRAIN_TER("Train TER", GtfsExtendedRouteType.REGIONAL_RAIL_SERVICE),
    OUIGO_TRAIN_CLASSIQUE("Train", GtfsExtendedRouteType.RAILWAY_SERVICE),
    TRAMTRAIN("TramTrain", GtfsExtendedRouteType.TRAM_SERVICE),
    NAVETTE("Navette", GtfsExtendedRouteType.SHUTTLE_BUS),
    CAR_TER("Car TER", GtfsExtendedRouteType.REGIONAL_BUS_SERVICE),
    CAR_A_RESERVATION("Car à réservation", GtfsExtendedRouteType.DEMAND_AND_RESPONSE_BUS_SERVICE),
    UNKNOWN("UNKNOWN", GtfsExtendedRouteType.RAILWAY_SERVICE);

    companion object {
        private val byValue = entries.associateBy { it.value.lowercase() }
        fun fromValue(v: String): SncfRouteType = byValue[v.lowercase()] ?: UNKNOWN
    }
}
