package mobilityfeeds

import org.slf4j.LoggerFactory
import mobilityfeeds.gtfs.getGtfsStore
import mobilityfeeds.gtfs.writeGtfsWithReplacements
import mobilityfeeds.patch.patchCalendarDates
import mobilityfeeds.patch.patchRoutes
import mobilityfeeds.patch.patchStops
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
private val txtPath = File(outputPath, "txt")
private val transfersFile = File(txtPath, "transfers.txt")
private val calendarDatesFile = File(txtPath, "calendar_dates.txt")
private val routesFile = File(txtPath, "routes.txt")
private val tripsFile = File(txtPath, "trips.txt")
private val stopsFile = File(txtPath, "stops.txt")
private val stopGroupElementsFile = File(txtPath, "stop_group_elements.txt")
// ponytail: flat string map read with a regex, a JSON library if the file grows beyond that
private val sources = Regex("\"(\\w+)\": *\"([^\"]+)\"").findAll(File("data/config.json").readText()).associate { it.groupValues[1] to it.groupValues[2] }

private val patchedGtfsFile = File(outputPath, "sncf_patched.zip")
private val patchedGtfsWithoutTransfersFile = File(outputPath, "sncf_patched_without_transfers.zip")

fun main(args: Array<String>) {
    val gtfsZip = download(name = "sncf", url = sources.getValue("gtfs_url"))
    val sncfRulesZip = download(name = "sncf_transfer_rules", url = sources.getValue("transfer_rules_url"))

    val gtfsStore = getGtfsStore(gtfsZip)
    val indexes = buildIndexes(gtfsStore, getUicReferential())

    txtPath.mkdirs()
    val routeTypeByRoute = patchRoutes(
        routes = gtfsStore.allRoutes,
        brandsByRoute = indexes.brandsByRoute,
        ricsCodesByRoute = indexes.ricsCodesByRoute,
        stops = gtfsStore.allStops,
        agencyIds = gtfsStore.allAgencies.map { it.id }.toSet(),
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

    patchStops(
        gtfsZip = gtfsZip,
        stops = gtfsStore.allStops,
        stopsOutput = stopsFile,
        groupsOutput = stopGroupElementsFile,
    )

    val replacements = mapOf(
        "routes.txt" to routesFile,
        "transfers.txt" to transfersFile,
        "calendar_dates.txt" to calendarDatesFile,
        "trips.txt" to tripsFile,
        "stops.txt" to stopsFile,
        "stop_group_elements.txt" to stopGroupElementsFile,
    )
    writeGtfsWithReplacements(sourceGtfs = gtfsZip, replacements = replacements, output = patchedGtfsFile)
    writeGtfsWithReplacements(sourceGtfs = gtfsZip, replacements = replacements - "transfers.txt" - "calendar_dates.txt", output = patchedGtfsWithoutTransfersFile) // the added service ids only serve the transfers
    logger.info("Wrote patched GTFS feeds ${patchedGtfsFile.path} and ${patchedGtfsWithoutTransfersFile.path}")
    if ("--no-txt" in args) txtPath.deleteRecursively() // the zips are the deliverable, the workflow does not want the txt files around
}

fun download(name: String, url: String): File {
    val local = File("download", "$name.zip").apply { parentFile.mkdirs() }
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
