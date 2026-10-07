package mobilityfeeds.patch

import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile

private val logger = LoggerFactory.getLogger("calendar_dates")

private val HEADER = "service_id,date,exception_type\n".toByteArray()

// Copies the original calendar_dates.txt byte for byte, then appends one row per date for each new service (exception_type 1 = service added)
fun patchCalendarDates(gtfsZip: File, calendarDatesCount: Int, datesByNewServiceId: Map<String, Set<LocalDate>>, output: File) {
    val newRows: List<String> = datesByNewServiceId.toSortedMap()
        .flatMap { (serviceId, dates) ->
            dates.sorted().map { "$serviceId,${it.format(DateTimeFormatter.BASIC_ISO_DATE)},1" }
    }

    val originalCalendarDatesTxt = ZipFile(gtfsZip).use { zip ->
        val entry = zip.getEntry("calendar_dates.txt") ?: error("calendar_dates.txt missing from ${gtfsZip.path}")
        zip.getInputStream(entry).use { it.readBytes() }
    }

    check(originalCalendarDatesTxt.copyOf(HEADER.size).contentEquals(HEADER)) { "calendar_dates.txt columns changed, review the appended rows" }
    check(originalCalendarDatesTxt.last() == '\n'.code.toByte()) { "calendar_dates.txt has no trailing newline, rows would be glued" }

    output.outputStream().buffered().use { out ->
        out.write(originalCalendarDatesTxt)
        newRows.forEach { out.write("$it\n".toByteArray()) }
    }
    logger.info("${newRows.size} rows for ${datesByNewServiceId.size} new services (from service_id ${datesByNewServiceId.keys.minOrNull()}) appended to $calendarDatesCount calendar dates in ${output.path}")
}
