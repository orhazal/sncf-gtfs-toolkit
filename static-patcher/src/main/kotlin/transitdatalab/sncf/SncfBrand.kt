package transitdatalab.sncf

// Brand as found in SNCF stop point ids (StopPoint:OCE<brand>-<uic8>), with the GTFS extended route type (Google / TPEG) it maps to
enum class SncfBrand(val extendedRouteType: Int, val stopIdBrand: String) {
    TGV_INOUI(101, "TGV INOUI"), // high speed rail
    OUIGO(101, "OUIGO"),
    LYRIA(101, "Lyria"),
    ICE(101, "ICE"),
    INTERCITES(102, "INTERCITES"), // long distance rail
    INTERCITES_DE_NUIT(105, "INTERCITES de nuit"), // sleeper rail
    TRAIN_TER(106, "Train TER"), // regional rail
    OUIGO_TRAIN_CLASSIQUE(102, "Train"),
    TRAMTRAIN(900, "TramTrain"), // tram
    NAVETTE(711, "Navette"), // shuttle bus
    CAR_TER(701, "Car TER"), // regional bus
    CAR_A_RESERVATION(715, "Car à réservation"); // demand and response bus

    // Basic GTFS route_type the extended type refines: 2 rail, 3 bus, 0 tram
    val basicRouteType: Int
        get() = when (extendedRouteType / 100) {
            1 -> 2
            7 -> 3
            9 -> 0
            else -> error("No basic route_type for extended type $extendedRouteType, map its family")
        }

    companion object {
        private val byValue = entries.associateBy { it.stopIdBrand.lowercase() }
        fun fromValue(v: String): SncfBrand = byValue[v.lowercase()] ?: error("Careful, stop type not matched for $v, a new one?")
    }
}
