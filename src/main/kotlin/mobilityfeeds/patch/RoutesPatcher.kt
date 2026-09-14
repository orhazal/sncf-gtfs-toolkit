package mobilityfeeds.patch

import mobilityfeeds.sncf.SncfBrand
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.onebusaway.gtfs.model.Route
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("routes")

// Rewrites routes.txt with route_type refined to the extended type of the brand serving the route.
// A route mixing brands (a train line and its replacement buses) takes the brand of its original route_type's family:
// route_type 2 served by "Train TER" and "Car TER" becomes 106 (regional rail), the "Car TER" trips get a
// trip_route_type in trips.txt. Several brands in that family ("Navette" and "Car à réservation" on a bus route):
// the route keeps its original route_type and every trip gets a trip_route_type.
// Returns the route_type written for every route.
fun patchRoutes(routes: Collection<Route>, brandsByRoute: Map<String, Set<SncfBrand>>, output: File): Map<String, Int> {
    val routeTypeByRoute = routes.associate { route ->
        val brands = brandsByRoute[route.id.id] ?: error("Route ${route.id.id} has no stop time")
        val candidates = if (brands.size == 1) brands else brands.filter { it.gtfsExtended.basicRouteType == route.type }

        val routeType = when (candidates.size) {
            1 -> candidates.single().gtfsExtended.value
            0 -> error("Route ${route.id.id} (route_type ${route.type}) mixes $brands, none refines its route_type")
            else -> {
                logger.warn("[ROUTES] ${route.id.id} (route_type ${route.type}) mixes $brands in the same family, keeping route_type ${route.type}")
                route.type
            }
        }
        route.id.id to routeType
    }

    val format = CSVFormat.DEFAULT.builder()
        .setRecordSeparator("\n") // SNCF GTFS files are LF
        .setHeader(
            "route_id", "agency_id", "route_short_name", "route_long_name", "route_desc",
            "route_type", "route_url", "route_color", "route_text_color"
        )
        .get()
    output.bufferedWriter().use { writer ->
        CSVPrinter(writer, format).use { printer ->
            routes.forEach { route ->
                printer.printRecord(
                    route.id.id, route.agency.id, route.shortName, route.longName, route.desc,
                    routeTypeByRoute.getValue(route.id.id), route.url, route.color, route.textColor
                )
            }
        }
    }
    logger.info("${routes.size} routes written in ${output.path}, ${brandsByRoute.count { it.value.size > 1 }} mixing several brands")
    return routeTypeByRoute
}
