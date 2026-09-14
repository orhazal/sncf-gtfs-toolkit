package mobilityfeeds

import org.slf4j.LoggerFactory
import mobilityfeeds.gtfs.getGtfsStore
import mobilityfeeds.gtfs.writeGtfsWithReplacements
import mobilityfeeds.patch.patchCalendarDates
import mobilityfeeds.patch.patchRoutes
import mobilityfeeds.patch.patchTransfers
import mobilityfeeds.patch.patchTrips
import mobilityfeeds.sncf.buildIndexes
import mobilityfeeds.sncf.getUicReferential
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private val logger = LoggerFactory.getLogger("main")
private val httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

private val outputPath = File("output")
private val transfersFile = File(outputPath, "transfers.txt")
private val calendarDatesFile = File(outputPath, "calendar_dates.txt")
private val routesFile = File(outputPath, "routes.txt")
private val tripsFile = File(outputPath, "trips.txt")
private val patchedGtfsFile = File(outputPath, "sncf_patched.zip")

fun main() {
    val gtfsZip = download(
        type = "gtfs",
        name = "sncf",
        url = "https://eu.ftp.opendatasoft.com/sncf/plandata/Export_OpenData_SNCF_GTFS_NewTripId.zip"
    )
    val sncfRulesZip = download(
        type = "other",
        name = "sncf_transfer_rules",
        url = "https://eu.ftp.opendatasoft.com/sncf/prr/temps_correspondance/INFOTRAINS_Export_IDH.zip"
    )

    val gtfsStore = getGtfsStore(gtfsZip)
    val indexes = buildIndexes(gtfsStore, getUicReferential())

    outputPath.mkdirs()
    val routeTypeByRoute = patchRoutes(
        routes = gtfsStore.allRoutes,
        brandsByRoute = indexes.brandsByRoute,
        output = routesFile
    )

    val datesByNewServiceId = patchTransfers(
        indexes = indexes,
        sncfRulesZip = sncfRulesZip,
        output = transfersFile
    )

    patchCalendarDates(
        gtfsZip = gtfsZip,
        calendarDatesCount = gtfsStore.allCalendarDates.size,
        datesByNewServiceId = datesByNewServiceId,
        output = calendarDatesFile
    )

    patchTrips(
        trips = gtfsStore.allTrips,
        brandByTrip = indexes.brandByTrip,
        routeTypeByRoute = routeTypeByRoute,
        output = tripsFile
    )

    writeGtfsWithReplacements(
        sourceGtfs = gtfsZip,
        replacements = mapOf(
            "routes.txt" to routesFile,
            "transfers.txt" to transfersFile,
            "calendar_dates.txt" to calendarDatesFile,
            "trips.txt" to tripsFile,
        ),
        output = patchedGtfsFile,
    )
    logger.info("Wrote patched GTFS feed ${patchedGtfsFile.path}")
}

fun download(type: String, name: String, url: String): File {
    val local = File("feeds/$type", "$name.zip").apply { parentFile.mkdirs() }
    logger.info("Downloading $url -> ${local.path}")
    val response = httpClient.send(
        HttpRequest.newBuilder(URI.create(url)).build(),
        HttpResponse.BodyHandlers.ofFile(local.toPath()),
    )
    check(response.statusCode() in 200..299) {
        "Download failed ($url): HTTP ${response.statusCode()}"
    }
    return local
}
