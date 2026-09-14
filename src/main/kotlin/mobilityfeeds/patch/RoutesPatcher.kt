package mobilityfeeds.patch

import mobilityfeeds.sncf.SncfBrand
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.onebusaway.gtfs.model.Route
import org.onebusaway.gtfs.model.Stop
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("routes")

private const val FALLBACK_AGENCY = "OCEdefault"
private val OCEDEFAULT_ROUTE_ID_REGEX = Regex("""^OCESN-(\d{8})-(\d{8})$""") // origin uic8, destination uic8

// Rewrites routes.txt with:
// - route_type refined to the extended type of the brand serving the route. A route mixing brands (a train line and its
//   replacement buses) takes the brand of its original route_type's family: route_type 2 served by "Train TER" and "Car TER"
//   becomes 106 (regional rail), the "Car TER" trips get a trip_route_type in trips.txt. Several brands in that family
//   ("Navette" and "Car à réservation" on a bus route): the route keeps its original route_type and every trip gets a trip_route_type.
// - agency_id replaced when it is SNCF's fallback "OCEdefault" and every trip of the route carries the same RICS in its id.
// - route_long_name of those "OCEdefault" routes generated as "origin - destination" from the two UIC codes in their id.
// Returns the route_type written for every route.
fun patchRoutes(
    routes: Collection<Route>,
    brandsByRoute: Map<String, Set<SncfBrand>>,
    ricsCodesByRoute: Map<String, Set<String>>,
    stops: Collection<Stop>,
    agencyIds: Set<String>,
    output: File,
): Map<String, Int> {
    val routeTypeByRoute = routes.associate { route ->
        val brands = brandsByRoute[route.id.id] ?: error("Route ${route.id.id} has no stop time")
        val candidates = if (brands.size == 1) brands else brands.filter { it.extendedRouteType.basicRouteType == route.type }

        val routeType = when (candidates.size) {
            1 -> candidates.single().extendedRouteType.value
            0 -> error("Route ${route.id.id} (route_type ${route.type}) mixes $brands, none refines its route_type")
            else -> {
                logger.warn("[ROUTES] ${route.id.id} (route_type ${route.type}) mixes $brands in the same family, keeping route_type ${route.type}")
                route.type
            }
        }
        route.id.id to routeType
    }

    val agencyByRoute = routes.associate { route ->
        val rics = ricsCodesByRoute[route.id.id]?.singleOrNull() // Si tous les trips de la route ont le même code RICS, on le récupère, sinon null
        val agency = when {
            route.agency.id != FALLBACK_AGENCY -> route.agency.id // No patch needed, valid RICS
            rics != null && rics in agencyIds -> rics
            else -> {
                logger.warn("[ROUTES] ${route.id.id} keeps agency $FALLBACK_AGENCY, its trips carry multiple RICS codes ${ricsCodesByRoute[route.id.id]}, not a single known agency")
                route.agency.id
            }
        }
        route.id.id to agency
    }

    // OCEdefault routes have "-" as name: "origin - destination" from the two stations whose UIC codes make up their id
    val stationNameByUic8 = stops.filter { it.locationType == 1 }.associate { it.id.id.takeLast(8) to it.name }
    val longNameByRoute = routes.associate { route ->
        val longName = if (route.agency.id != FALLBACK_AGENCY) route.longName // No patch needed
        else OCEDEFAULT_ROUTE_ID_REGEX.find(route.id.id)?.groupValues?.drop(1)?.map { stationNameByUic8[it] }?.takeIf { null !in it }?.joinToString(" - ")
            ?: run {
                logger.warn("[ROUTES] ${route.id.id} keeps route_long_name '${route.longName}', no origin and destination stations found in its id")
                route.longName
            }
        route.id.id to longName
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
                    route.id.id, agencyByRoute.getValue(route.id.id), route.shortName, longNameByRoute.getValue(route.id.id), route.desc,
                    routeTypeByRoute.getValue(route.id.id), route.url, route.color, route.textColor
                )
            }
        }
    }
    val patchedAgencies = routes.count { it.agency.id == FALLBACK_AGENCY && agencyByRoute.getValue(it.id.id) != FALLBACK_AGENCY }
    val patchedNames = routes.count { longNameByRoute.getValue(it.id.id) != it.longName }
    logger.info("${routes.size} routes written in ${output.path}, ${brandsByRoute.count { it.value.size > 1 }} mixing several brands, $patchedAgencies $FALLBACK_AGENCY agencies replaced, $patchedNames names generated")
    return routeTypeByRoute
}
