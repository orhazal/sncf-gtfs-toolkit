package mobilityfeeds.sncf

import org.onebusaway.gtfs.impl.GtfsDaoImpl
import org.onebusaway.gtfs.model.calendar.ServiceDate
import java.time.LocalDate
import java.util.Properties

private val TRIP_ID_MODE_REGEX = Regex("""\d{4}_([FR]):""")
private val STOP_POINT_REGEX = Regex("""^StopPoint:OCE(.+)-\d{8}$""")

// Built once from the GTFS store; each patcher picks the indexes it needs.
class GtfsIndexes(
    val feedDates: ClosedRange<LocalDate>,
    val nextUsableServiceId: String,
    val stopsByModeByUic7: Map<String, Map<Mode, Set<String>>>,
    val stopAndRouteByRicsByUic7: Map<String, Map<String, Set<Pair<String, String>>>>,
    val stopByTypeByUic7: Map<String, Map<String, String>>, // Train is OUIGO Classique
    val stopAndTripByTrainNumberByUic7: Map<String, Map<String, Set<Pair<String, String>>>>,
    val brandCarrierByTrip: Map<String, SncfRouteType>,
) {
    val uic7Codes: Set<String> get() = stopsByModeByUic7.keys
}

fun buildIndexes(gtfsStore: GtfsDaoImpl, uic7ByUic8: Map<String, String>): GtfsIndexes {
    // feed_info.txt
    val feedInfo = gtfsStore.allFeedInfos.firstOrNull() ?: error("feed_info.txt missing, needed to cap transfer rule dates")
    val feedDates = feedInfo.startDate.toLocalDate()..feedInfo.endDate.toLocalDate()

    // calendar_dates.txt
    val nextUsableServiceId = serviceIdAfter(gtfsStore.allCalendarDates.maxOf { it.serviceId.id })

    // stop_times.txt
    val stopsByModeByUic7 = HashMap<String, HashMap<Mode, MutableSet<String>>>()
    val stopAndRouteByRicsByUic7 = HashMap<String, HashMap<String, MutableSet<Pair<String, String>>>>()
    val stopByTypeByUic7 = HashMap<String, HashMap<String, String>>()
    val stopAndTripByTrainNumberByUic7 = HashMap<String, HashMap<String, MutableSet<Pair<String, String>>>>()
    val brandCarrierByTrip = HashMap<String, SncfRouteType>()

    for (stopTime in gtfsStore.allStopTimes) {
        val tripId = stopTime.trip.id.id
        val routeId = stopTime.trip.route.id.id
        val stopId = stopTime.stop.id.id
        val rics = stopTime.trip.id.agencyId

        val mode = TRIP_ID_MODE_REGEX.find(tripId)?.groupValues?.get(1)
            ?.let { Mode.valueOf(it) }
            ?: error("Mode always exists")

        val stopType = STOP_POINT_REGEX.find(stopId)?.groupValues?.get(1)
            ?: error("Stop id format changed: $stopId, review immediately")

        val typedStopType = SncfRouteType.fromValue(stopType)
        if (typedStopType == SncfRouteType.UNKNOWN) {
            error("Careful, stop type not matched for $stopType, a new one?")
        }

        val uic8FromStopId = stopId.substring(stopId.length - 8)
        // On récupère le code UIC7 du référentiel des gares SNCF, sinon on strip le dernier char
        val uic7 = uic7ByUic8[uic8FromStopId] ?: uic8FromStopId.dropLast(1)
        val trainNumber = stopTime.trip.tripHeadsign.trim().trimStart('0')

        stopsByModeByUic7.computeIfAbsent(uic7) { HashMap() }
            .computeIfAbsent(mode) { HashSet() }
            .add(stopId)

        stopAndRouteByRicsByUic7.computeIfAbsent(uic7) { HashMap() }
            .computeIfAbsent(rics) { HashSet() }
            .add(routeId to stopId)

        stopByTypeByUic7.computeIfAbsent(uic7) { HashMap() }[stopType] = stopId

        stopAndTripByTrainNumberByUic7.computeIfAbsent(uic7) { HashMap() }
            .computeIfAbsent(trainNumber) { HashSet() }
            .add(tripId to stopId)

        brandCarrierByTrip.putIfAbsent(tripId, typedStopType)
    }

    return GtfsIndexes(
        feedDates = feedDates,
        nextUsableServiceId = nextUsableServiceId,
        stopsByModeByUic7 = stopsByModeByUic7,
        stopAndRouteByRicsByUic7 = stopAndRouteByRicsByUic7,
        stopByTypeByUic7 = stopByTypeByUic7,
        stopAndTripByTrainNumberByUic7 = stopAndTripByTrainNumberByUic7,
        brandCarrierByTrip = brandCarrierByTrip,
    )
}

private fun ServiceDate.toLocalDate(): LocalDate = LocalDate.of(year, month, day)

// Service ids are fixed-width zero-padded numbers ("000222"), so string max == numeric max and the width is kept
fun serviceIdAfter(serviceId: String, offset: Int = 1): String =
    (serviceId.toInt() + offset).toString().padStart(serviceId.length, '0')

fun getUicReferential(): Map<String, String> {
    val properties = Properties()
    val resource = GtfsIndexes::class.java.getResourceAsStream("/unusual_uic_referential.properties")
        ?: error("unusual_uic_referential.properties missing from resources")
    resource.reader().use { properties.load(it) }
    return properties.map { (uic8, uic7) -> uic8.toString() to uic7.toString() }.toMap()
}
