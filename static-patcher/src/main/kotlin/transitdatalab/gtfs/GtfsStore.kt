package transitdatalab.gtfs

import org.apache.commons.csv.CSVFormat
import org.onebusaway.gtfs.impl.GtfsDaoImpl
import org.onebusaway.gtfs.serialization.GtfsReader
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

fun getGtfsStore(gtfsZip: File): GtfsDaoImpl {
    val store = GtfsDaoImpl()
    GtfsReader().apply { setInputLocation(gtfsZip); entityStore = store }.run()
    return store
}

// Format of the patched files: the given header, LF records like the SNCF GTFS files
fun lfCsv(vararg header: String): CSVFormat = CSVFormat.DEFAULT.builder().setRecordSeparator("\n").setHeader(*header).get()

// Format of the files read: columns named by the first record
val HEADER_CSV: CSVFormat = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get()

// Copies a file of the source GTFS byte for byte, then lets append write its rows after it. The header is checked
// first, since the appended rows follow its columns.
fun appendToGtfsFile(gtfsZip: File, name: String, header: String, output: File, append: (OutputStream) -> Unit) {
    val original = ZipFile(gtfsZip).use { zip ->
        val entry = zip.getEntry(name) ?: error("$name missing from ${gtfsZip.path}")
        zip.getInputStream(entry).use { it.readBytes() }
    }
    val headerBytes = "$header\n".toByteArray()
    check(original.copyOf(headerBytes.size).contentEquals(headerBytes)) { "$name columns changed, review the appended rows" }
    check(original.last() == '\n'.code.toByte()) { "$name has no trailing newline, rows would be glued" }

    output.outputStream().buffered().use { out ->
        out.write(original)
        append(out)
    }
}

// Copies every entry from the source GTFS zip, replacing the ones whose name matches a provided file and adding the others
fun writeGtfsWithReplacements(
    sourceGtfs: File,
    replacements: Map<String, File>, // entry name -> new content
    output: File,
) {
    ZipOutputStream(output.outputStream().buffered()).use { zos ->
        val copied = mutableSetOf<String>()
        var lastTime = 0L
        ZipInputStream(sourceGtfs.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                copied += entry.name
                lastTime = entry.time
                val replacement = replacements[entry.name]
                zos.putNextEntry(ZipEntry(entry.name).apply { time = lastTime }) // source timestamps: same input, same bytes
                if (replacement != null) {
                    replacement.inputStream().use { it.copyTo(zos) }
                } else {
                    zis.copyTo(zos)
                }
                zos.closeEntry()
                entry = zis.nextEntry
            }
        }
        (replacements - copied).forEach { (name, file) -> // files the source does not have
            zos.putNextEntry(ZipEntry(name).apply { time = lastTime })
            file.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }
}
