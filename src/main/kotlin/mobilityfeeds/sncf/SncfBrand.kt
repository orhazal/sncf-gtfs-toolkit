package mobilityfeeds.sncf

import mobilityfeeds.gtfs.GtfsExtendedRouteType

// Brand as found in SNCF stop point ids (StopPoint:OCE<brand>-<uic8>), mapped to a GTFS extended route type
enum class SncfBrand(
    val extendedRouteType: GtfsExtendedRouteType,
    val stopIdBrand: String,
) {
    TGV_INOUI(GtfsExtendedRouteType.HIGH_SPEED_RAIL_SERVICE, "TGV INOUI"),
    OUIGO(GtfsExtendedRouteType.HIGH_SPEED_RAIL_SERVICE, "OUIGO"),
    LYRIA(GtfsExtendedRouteType.HIGH_SPEED_RAIL_SERVICE, "Lyria"),
    ICE(GtfsExtendedRouteType.HIGH_SPEED_RAIL_SERVICE, "ICE"),
    INTERCITES(GtfsExtendedRouteType.LONG_DISTANCE_TRAINS, "INTERCITES"),
    INTERCITES_DE_NUIT(GtfsExtendedRouteType.SLEEPER_RAIL_SERVICE, "INTERCITES de nuit"),
    TRAIN_TER(GtfsExtendedRouteType.REGIONAL_RAIL_SERVICE, "Train TER"),
    OUIGO_TRAIN_CLASSIQUE(GtfsExtendedRouteType.LONG_DISTANCE_TRAINS, "Train"),
    TRAMTRAIN(GtfsExtendedRouteType.TRAM_SERVICE, "TramTrain"),
    NAVETTE(GtfsExtendedRouteType.SHUTTLE_BUS, "Navette"),
    CAR_TER(GtfsExtendedRouteType.REGIONAL_BUS_SERVICE, "Car TER"),
    CAR_A_RESERVATION(GtfsExtendedRouteType.DEMAND_AND_RESPONSE_BUS_SERVICE, "Car à réservation"),
    UNKNOWN(GtfsExtendedRouteType.RAILWAY_SERVICE, "UNKNOWN");

    companion object {
        private val byValue = entries.associateBy { it.stopIdBrand.lowercase() }
        fun fromValue(v: String): SncfBrand = byValue[v.lowercase()] ?: UNKNOWN
    }
}
