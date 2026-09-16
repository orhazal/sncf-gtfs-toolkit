package mobilityfeeds.sncf

import org.onebusaway.gtfs.impl.GtfsDaoImpl
import org.onebusaway.gtfs.model.calendar.ServiceDate
import java.time.LocalDate
import java.util.EnumMap
import org.apache.commons.csv.CSVFormat
import java.io.File

private val TRIP_ID_REGEX = Regex("""(\d{4})_([FR]):""") // RICS, mode
private val STOP_POINT_REGEX = Regex("""^StopPoint:OCE(.+)-\d{8}$""")

// Built once from the GTFS store; each patcher picks the indexes it needs.
class GtfsIndexes(
    val feedDates: ClosedRange<LocalDate>,
    val nextUsableServiceId: String,
    val stopsByModeByUic7: Map<String, Map<Mode, Set<String>>>,
    val stopAndRouteByRicsByUic7: Map<String, Map<String, Set<Pair<String, String>>>>,
    val stopByBrandByUic7: Map<String, Map<SncfBrand, String>>,
    val stopAndTripByTrainNumberByUic7: Map<String, Map<String, Set<Pair<String, String>>>>,
    val brandByTrip: Map<String, SncfBrand>,
    val brandsByRoute: Map<String, Set<SncfBrand>>,
    val ricsCodesByRoute: Map<String, Set<String>>,
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
    val stopByBrandByUic7 = HashMap<String, EnumMap<SncfBrand, String>>() // EnumMap: iterated in the patcher, enum hash order varies per run
    val stopAndTripByTrainNumberByUic7 = HashMap<String, HashMap<String, MutableSet<Pair<String, String>>>>()
    val brandByTrip = HashMap<String, SncfBrand>()
    val brandsByRoute = HashMap<String, MutableSet<SncfBrand>>()
    val ricsCodesByRoute = HashMap<String, MutableSet<String>>()

    for (stopTime in gtfsStore.allStopTimes) {
        val tripId = stopTime.trip.id.id
        val routeId = stopTime.trip.route.id.id
        val stopId = stopTime.stop.id.id
        // The route's agency_id can be "OCEdefault": the trip id carries the RICS actually running the trip
        val (rics, modeLetter) = TRIP_ID_REGEX.find(tripId)?.destructured ?: error("Trip id format changed: $tripId, review immediately")
        val mode = Mode.valueOf(modeLetter)

        val stopType = STOP_POINT_REGEX.find(stopId)?.groupValues?.get(1)
            ?: error("Stop id format changed: $stopId, review immediately")

        val brandFromStop = SncfBrand.fromValue(stopType)

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

        stopByBrandByUic7.computeIfAbsent(uic7) { EnumMap(SncfBrand::class.java) }[brandFromStop] = stopId

        stopAndTripByTrainNumberByUic7.computeIfAbsent(uic7) { HashMap() }
            .computeIfAbsent(trainNumber) { HashSet() }
            .add(tripId to stopId)

        brandByTrip.putIfAbsent(tripId, brandFromStop)
        brandsByRoute.computeIfAbsent(routeId) { LinkedHashSet() }.add(brandFromStop)
        ricsCodesByRoute.computeIfAbsent(routeId) { LinkedHashSet() }.add(rics)
    }

    return GtfsIndexes(
        feedDates = feedDates,
        nextUsableServiceId = nextUsableServiceId,
        stopsByModeByUic7 = stopsByModeByUic7,
        stopAndRouteByRicsByUic7 = stopAndRouteByRicsByUic7,
        stopByBrandByUic7 = stopByBrandByUic7,
        stopAndTripByTrainNumberByUic7 = stopAndTripByTrainNumberByUic7,
        brandByTrip = brandByTrip,
        brandsByRoute = brandsByRoute,
        ricsCodesByRoute = ricsCodesByRoute,
    )
}

private fun ServiceDate.toLocalDate(): LocalDate = LocalDate.of(year, month, day)

// Service ids are fixed-width zero-padded numbers ("000222"), so string max == numeric max and the width is kept
fun serviceIdAfter(serviceId: String, offset: Int = 1): String =
    (serviceId.toInt() + offset).toString().padStart(serviceId.length, '0')

// GTFS stop uic8 -> IDH uic7, for the stations where dropping the last digit gives the wrong code
fun getUicReferential(): Map<String, String> = File("data/unusual_uic.csv").bufferedReader().use { reader ->
    CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader).associate { it["uic8"] to it["uic7"] }
}
