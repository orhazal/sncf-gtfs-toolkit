package mobilityfeeds.patch

import mobilityfeeds.sncf.SncfBrand
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.onebusaway.gtfs.model.Trip
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("trips")

// Rewrites trips.txt with an extra trip_route_type column (MBTA extension), filled only when the trip's brand
// differs from its route's patched route_type (a replacement bus on a train line)
fun patchTrips(
    trips: Collection<Trip>,
    brandByTrip: Map<String, SncfBrand>,
    routeTypeByRoute: Map<String, Int>,
    output: File,
) {
    val format = CSVFormat.DEFAULT.builder()
        .setRecordSeparator("\n") // SNCF GTFS files are LF
        .setHeader(
            "route_id", "service_id", "trip_id", "trip_headsign", "direction_id",
            "block_id", "shape_id", "trip_route_type"
        )
        .get()
    var patched = 0
    output.bufferedWriter().use { writer ->
        CSVPrinter(writer, format).use { printer ->
            trips.forEach { trip ->
                val tripRouteType = brandByTrip.getValue(trip.id.id).extendedRouteType.value
                    .takeIf { it != routeTypeByRoute.getValue(trip.route.id.id) }
                if (tripRouteType != null) patched++
                printer.printRecord(
                    trip.route.id.id, trip.serviceId.id, trip.id.id,
                    trip.tripHeadsign, trip.directionId, trip.blockId, trip.shapeId?.id,
                    tripRouteType
                )
            }
        }
    }
    logger.info("${trips.size} trips written in ${output.path}, $patched with a trip_route_type differing from their route")
}
