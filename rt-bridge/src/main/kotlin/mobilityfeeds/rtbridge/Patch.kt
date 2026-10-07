// The pure part of the bridge: build the lookup from the SNCF GTFS, rewrite the two feeds.
package mobilityfeeds.rtbridge

import com.google.transit.realtime.GtfsRealtime.Alert
import com.google.transit.realtime.GtfsRealtime.FeedEntity
import com.google.transit.realtime.GtfsRealtime.TripDescriptor
import com.google.transit.realtime.GtfsRealtime.TripDescriptor.ScheduleRelationship.ADDED
import org.apache.commons.csv.CSVFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.ZipInputStream

// RT internal trip id: OCE, two letters for the network (SN, SA, EA, LO), the train number, F (ferré) or R (route).
// It is the prefix of the static ids of that train.
private val SHORT_ID = Regex("""^OCE[A-Z]{2}\d+[FR]""")
private val CSV = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get()

class Trip(val routeId: String, val serviceId: String)

class Lookup(
    val trips: Map<String, Trip>,
    val byShort: Map<String, List<String>>, // internal id -> static trip ids
    private val days: Map<String, IntArray>, // service id -> sorted yyyymmdd days it runs
) {
    // whether the trip runs on at least one day of the range
    fun runs(tripId: String, range: IntRange): Boolean {
        val days = days[trips.getValue(tripId).serviceId] ?: return false
        val i = days.binarySearch(range.first).let { if (it < 0) -it - 1 else it }
        return i < days.size && days[i] <= range.last
    }
}

// file name -> text, for trips.txt, calendar_dates.txt and optionally calendar.txt
fun lookupFrom(files: Map<String, String>): Lookup {
    check(csv(files["calendar.txt"].orEmpty()).none()) { "calendar.txt has rows: the SNCF feed used to date everything through calendar_dates.txt" }
    val trips = HashMap<String, Trip>()
    val byShort = HashMap<String, MutableList<String>>()
    for (r in csv(files.getValue("trips.txt"))) {
        val tripId = r["trip_id"]
        trips[tripId] = Trip(r["route_id"], r["service_id"])
        SHORT_ID.find(tripId)?.let { byShort.getOrPut(it.value) { mutableListOf() } += tripId }
    }
    val days = HashMap<String, MutableList<Int>>()
    for (r in csv(files.getValue("calendar_dates.txt"))) if (r["exception_type"] == "1") days.getOrPut(r["service_id"]) { mutableListOf() } += r["date"].toInt()
    return Lookup(trips, byShort, days.mapValues { it.value.toIntArray().apply { sort() } })
}

fun lookupFromZip(bytes: ByteArray): Lookup {
    val wanted = setOf("trips.txt", "calendar_dates.txt", "calendar.txt")
    val files = HashMap<String, String>()
    ZipInputStream(bytes.inputStream()).use { zip ->
        generateSequence { zip.nextEntry }.filter { it.name in wanted }.forEach { files[it.name] = zip.readBytes().decodeToString() }
    }
    return lookupFrom(files)
}

private fun csv(text: String) = CSV.parse(text.removePrefix("﻿").reader())

// The static trips an RT trip descriptor designates: itself when it carries a static id, otherwise the trips of its
// train number, only those running on start_date when there is one.
private fun Lookup.resolve(trip: TripDescriptor): List<String> {
    if (trip.tripId in trips) return listOf(trip.tripId)
    val siblings = byShort[trip.tripId.trim()].orEmpty()
    if (trip.startDate.isEmpty()) return siblings
    val day = trip.startDate.toIntOrNull() ?: return emptyList()
    return siblings.filter { runs(it, day..day) }
}

fun patchTripUpdates(entities: List<FeedEntity>, lk: Lookup): List<FeedEntity> = entities.flatMap { e ->
    val trip = e.tripUpdate.trip
    when {
        !e.hasTripUpdate() || trip.tripId in lk.trips -> listOf(e)
        trip.scheduleRelationship == ADDED -> {
            // a new run of a known train number: kept, with the route of that train so that consumers can place it
            val siblings = lk.byShort[trip.tripId.trim()].orEmpty()
            when {
                siblings.isEmpty() -> emptyList()
                trip.routeId.isNotEmpty() -> listOf(e)
                else -> {
                    val route = siblings.groupingBy { lk.trips.getValue(it).routeId }.eachCount().maxBy { it.value }.key
                    listOf(e.toBuilder().apply { tripUpdateBuilder.tripBuilder.setRouteId(route) }.build())
                }
            }
        }
        // scheduled or canceled: one update per static trip of that train running that day, none means dropped
        else -> lk.resolve(trip).mapIndexed { i, tripId ->
            e.toBuilder().apply {
                if (i > 0) setId("${e.id}#$i")
                tripUpdateBuilder.tripBuilder.setTripId(tripId)
            }.build()
        }
    }
}

// The days an alert can affect: its impact periods, or the active ones it falls back to, as yyyymmdd ranges. The
// dates are those of the UTC day the period starts and ends on, close enough to a service day to sort trips by.
private fun periodsOf(a: Alert) = a.impactPeriodList.ifEmpty { a.activePeriodList }
    .map { (if (it.hasStart()) ymd(it.start) else 0)..(if (it.hasEnd()) ymd(it.end) else 99999999) }

private fun ymd(epochSecond: Long) =
    LocalDate.ofInstant(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC).run { year * 10000 + monthValue * 100 + dayOfMonth }

// An informed entity names a train, not a run: an internal id becomes every static trip of that train number, minus
// the trips that never run while the alert is in effect. What bounds the alert in time is its own period.
fun patchAlerts(entities: List<FeedEntity>, lk: Lookup): List<FeedEntity> = entities.mapNotNull { e ->
    if (!e.hasAlert()) return@mapNotNull e
    val periods = periodsOf(e.alert)
    val informed = e.alert.informedEntityList.flatMap { ie ->
        if (ie.trip.tripId.isEmpty()) listOf(ie)
        else lk.resolve(ie.trip).filter { id -> periods.isEmpty() || periods.any { lk.runs(id, it) } }
            .map { ie.toBuilder().apply { tripBuilder.setTripId(it) }.build() }
    }
    if (informed.isEmpty()) null
    else e.toBuilder().apply { alertBuilder.clearInformedEntity().addAllInformedEntity(informed) }.build()
}
