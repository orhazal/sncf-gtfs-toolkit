package transitdatalab.rtbridge

import com.google.transit.realtime.GtfsRealtime.Alert
import com.google.transit.realtime.GtfsRealtime.EntitySelector
import com.google.transit.realtime.GtfsRealtime.FeedEntity
import com.google.transit.realtime.GtfsRealtime.TimeRange
import com.google.transit.realtime.GtfsRealtime.TripDescriptor
import com.google.transit.realtime.GtfsRealtime.TripDescriptor.ScheduleRelationship
import com.google.transit.realtime.GtfsRealtime.TripDescriptor.ScheduleRelationship.ADDED
import com.google.transit.realtime.GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED
import com.google.transit.realtime.GtfsRealtime.TripDescriptor.ScheduleRelationship.SCHEDULED
import com.google.transit.realtime.GtfsRealtime.TripUpdate
import kotlin.test.Test
import kotlin.test.assertEquals

private fun t(train: Int, mode: Char, date: Int, net: String = "SN") = "OCE$net$train${mode}1187_$mode:TER:FR:Line::L$train::87000001:87000002:1:1200:$date"

private val lk = lookupFrom(mapOf(
    "trips.txt" to listOf("route_id,service_id,trip_id,trip_headsign", "RA,S1,${t(100, 'F', 20260101)},100", "RA,S2,${t(100, 'F', 20260301)},100", "RB,S1,${t(200, 'R', 20260101)},200", "RC,S1,${t(300, 'F', 20260101, "SA")},300").joinToString("\n"),
    "calendar_dates.txt" to listOf("service_id,date,exception_type", "S1,20260916,1", "S2,20260917,1").joinToString("\n"),
))

private fun trip(tripId: String, startDate: String = "") = TripDescriptor.newBuilder().setTripId(tripId).setStartDate(startDate)
private fun tu(tripId: String, relationship: ScheduleRelationship, startDate: String = "20260916") =
    FeedEntity.newBuilder().setId(tripId).setTripUpdate(TripUpdate.newBuilder().setTrip(trip(tripId, startDate).setScheduleRelationship(relationship))).build()
private fun alert(vararg informed: EntitySelector.Builder, period: TimeRange.Builder? = null) =
    FeedEntity.newBuilder().setId("a").setAlert(Alert.newBuilder().apply { informed.forEach { addInformedEntity(it) }; period?.let { addActivePeriod(it) } }).build()
private fun onTrip(tripId: String, startDate: String = "") = EntitySelector.newBuilder().setTrip(trip(tripId, startDate))

class PatchTest {
    @Test
    fun tripUpdates() {
        val out = patchTripUpdates(listOf(
            tu(t(100, 'F', 20260101), SCHEDULED), // static id: kept as is
            tu("OCESN100F", CANCELED),             // train 100 canceled on the 16th: the S1 trip
            tu("OCESN100F", CANCELED, "20260918"), // canceled on a day it does not run: dropped
            tu("OCESN200R", ADDED),                // added run of train 200: kept, with the route of train 200
            tu("OCESN999F", ADDED),                // added unknown train: dropped
            tu("OCESN999F", CANCELED),             // canceled unknown train: dropped
        ), lk)
        assertEquals(listOf(t(100, 'F', 20260101) to "", t(100, 'F', 20260101) to "", "OCESN200R" to "RB"),
            out.map { it.tripUpdate.trip.tripId to it.tripUpdate.trip.routeId })
    }

    @Test
    fun serviceAlerts() {
        val out = patchAlerts(listOf(
            alert(onTrip("OCESN100F"), EntitySelector.newBuilder().setStopId("StopArea:OCE87000001")), // every trip of train 100
            alert(onTrip("OCESN100F", "20260917")), // a date, when there is one, still filters
            alert(onTrip("OCESN999F")), // nothing left: dropped
            alert(onTrip("OCESN100F"), period = TimeRange.newBuilder().setStart(1789516800).setEnd(1789603199)), // 20260916 only: the S1 trip
            alert(onTrip("OCESN100F"), period = TimeRange.newBuilder().setStart(1789603200)), // from 20260917, no end: the S2 trip
            alert(onTrip("OCESA300F")), // another network prefix
        ), lk)
        assertEquals(listOf(
            listOf(t(100, 'F', 20260101), t(100, 'F', 20260301), "StopArea:OCE87000001"),
            listOf(t(100, 'F', 20260301)),
            listOf(t(100, 'F', 20260101)),
            listOf(t(100, 'F', 20260301)),
            listOf(t(300, 'F', 20260101, "SA")),
        ), out.map { e -> e.alert.informedEntityList.map { if (it.hasTrip()) it.trip.tripId else it.stopId } })
    }
}
