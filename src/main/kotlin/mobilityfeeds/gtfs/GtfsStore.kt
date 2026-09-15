package mobilityfeeds.gtfs

import org.onebusaway.gtfs.impl.GtfsDaoImpl
import org.onebusaway.gtfs.serialization.GtfsReader
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

fun getGtfsStore(gtfsZip: File): GtfsDaoImpl {
    val reader = GtfsReader()
    reader.setInputLocation(gtfsZip)
    reader.entityStore = GtfsDaoImpl()
    reader.run()
    return (reader.entityStore as? GtfsDaoImpl) ?: error("GtfsDaoImpl not initialized")
}

// Copies every entry from the source GTFS zip, replacing the ones whose name matches a provided file
fun writeGtfsWithReplacements(
    sourceGtfs: File,
    replacements: Map<String, File>, // entry name -> new content
    output: File,
) {
    ZipOutputStream(output.outputStream().buffered()).use { zos ->
        ZipInputStream(sourceGtfs.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val replacement = replacements[entry.name]
                zos.putNextEntry(ZipEntry(entry.name).apply { time = entry.time }) // source timestamps: same input, same bytes
                if (replacement != null) {
                    replacement.inputStream().use { it.copyTo(zos) }
                } else {
                    zis.copyTo(zos)
                }
                zos.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
