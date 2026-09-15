package mobilityfeeds.patch

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.onebusaway.gtfs.model.Stop
import org.slf4j.LoggerFactory
import java.io.File
import java.util.zip.ZipFile

private val logger = LoggerFactory.getLogger("stops")

private val HEADER = "stop_id,stop_name,stop_desc,stop_lat,stop_lon,zone_id,stop_url,location_type,parent_station\n".toByteArray()
private val csvFormat = CSVFormat.DEFAULT.builder().setRecordSeparator("\n").get() // SNCF GTFS files are LF

// Copies the original stops.txt byte for byte, then appends one CITY_<locality> stop per locality (location_type 0 at 0,0),
// and writes stop_group_elements.txt linking each locality to its stations. MOTIS stop groups, not GTFS.
fun patchStops(gtfsZip: File, stops: Collection<Stop>, stopsOutput: File, groupsOutput: File) {
    val stopIdByUic8 = stops.filter { it.locationType == 1 }.associate { it.id.id.takeLast(8) to it.id.id }

    val localities = File("input/other/stations_to_localities.csv").bufferedReader().use { reader ->
        csvFormat.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader)
            .mapNotNull { row -> stopIdByUic8[row["station_id"]]?.let { Locality(row["locality_id"], row["locality_name"]) to it } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 } // a single-station locality adds nothing
            .toSortedMap(compareBy { it.id })
    }

    val originalStopsTxt = ZipFile(gtfsZip).use { zip ->
        val entry = zip.getEntry("stops.txt") ?: error("stops.txt missing from ${gtfsZip.path}")
        zip.getInputStream(entry).use { it.readBytes() }
    }
    check(originalStopsTxt.copyOf(HEADER.size).contentEquals(HEADER)) { "stops.txt columns changed, review the appended rows" }
    check(originalStopsTxt.last() == '\n'.code.toByte()) { "stops.txt has no trailing newline, rows would be glued" }

    stopsOutput.outputStream().buffered().use { out ->
        out.write(originalStopsTxt)
        CSVPrinter(out.writer(), csvFormat).use { printer ->
            localities.keys.forEach { printer.printRecord(it.stopId, "${it.name} (toutes gares)", null, 0, 0, null, null, 0, null) }
        }
    }
    groupsOutput.bufferedWriter().use { writer ->
        CSVPrinter(writer, csvFormat.builder().setHeader("stop_group_id", "stop_id").get()).use { printer ->
            localities.forEach { (locality, stations) -> stations.sorted().forEach { printer.printRecord(locality.stopId, it) } }
        }
    }
    logger.info("${localities.size} localities appended to ${stops.count { it.locationType == 1 }} stations in ${stopsOutput.path}, ${localities.values.sumOf { it.size }} elements written in ${groupsOutput.path}")
}

private data class Locality(val id: String, val name: String) {
    val stopId get() = "CITY_$id"
}
