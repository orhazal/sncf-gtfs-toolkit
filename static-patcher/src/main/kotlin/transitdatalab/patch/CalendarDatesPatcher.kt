package transitdatalab.patch

import org.slf4j.LoggerFactory
import transitdatalab.gtfs.appendToGtfsFile
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val logger = LoggerFactory.getLogger("calendar_dates")

// Copies the original calendar_dates.txt byte for byte, then appends one row per date for each new service (exception_type 1 = service added)
fun patchCalendarDates(gtfsZip: File, calendarDatesCount: Int, datesByNewServiceId: Map<String, Set<LocalDate>>, output: File) {
    val newRows: List<String> = datesByNewServiceId.toSortedMap()
        .flatMap { (serviceId, dates) ->
            dates.sorted().map { "$serviceId,${it.format(DateTimeFormatter.BASIC_ISO_DATE)},1" }
    }

    appendToGtfsFile(gtfsZip, "calendar_dates.txt", "service_id,date,exception_type", output) { out ->
        newRows.forEach { out.write("$it\n".toByteArray()) }
    }
    logger.info("${newRows.size} rows for ${datesByNewServiceId.size} new services (from service_id ${datesByNewServiceId.keys.minOrNull()}) appended to $calendarDatesCount calendar dates in ${output.path}")
}
