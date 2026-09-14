package mobilityfeeds.patch

import mobilityfeeds.sncf.SncfRouteType
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.onebusaway.gtfs.model.Trip
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("trips")

// Rewrites trips.txt with an extra trip_route_type column (GTFS extended route type per trip)
fun patchTrips(trips: Collection<Trip>, brandCarrierByTrip: Map<String, SncfRouteType>, output: File) {
    val format = CSVFormat.DEFAULT.builder()
        .setRecordSeparator("\n") // SNCF GTFS files are LF
        .setHeader(
            "route_id", "service_id", "trip_id", "trip_headsign", "direction_id",
            "block_id", "shape_id", "trip_route_type"
        )
        .get()
    output.bufferedWriter().use { writer ->
        CSVPrinter(writer, format).use { printer ->
            trips.forEach { trip ->
                printer.printRecord(
                    trip.route.id.id, trip.serviceId.id, trip.id.id,
                    trip.tripHeadsign, trip.directionId, trip.blockId, trip.shapeId?.id,
                    brandCarrierByTrip[trip.id.id]!!.gtfsExtended.value
                )
            }
        }
    }
    logger.info("${trips.size} trips written in ${output.path}")
}
