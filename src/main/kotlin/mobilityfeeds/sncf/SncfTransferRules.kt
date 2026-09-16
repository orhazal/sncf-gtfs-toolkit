package mobilityfeeds.sncf

import org.apache.commons.csv.CSVFormat
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

// Rules from the SNCF Transfer Rules export (INFOTRAINS_Export_IDH.zip)
data class TrainConnection(
    val arrivalTrain: String,
    val departureTrain: String,
    val minDelay: Int,
    val arrivalStationUic: String,
    val departureStationUic: String,
    val startDate: LocalDate,
    val endDate: LocalDate
)

sealed class StationConnection(
    val minDelay: Int,
    val arrivalUic: String,
    val departureUic: String,
) {
    class ByMode(
        val arrivalMode: Mode,
        val departureMode: Mode,
        minDelay: Int,
        arrivalUic: String,
        departureUic: String,
    ) : StationConnection(minDelay, arrivalUic, departureUic)

    class ByRics(
        val arrivalRics: String,
        val departureRics: String,
        minDelay: Int,
        arrivalUic: String,
        departureUic: String,
    ) : StationConnection(minDelay, arrivalUic, departureUic)

    class ByBrand(
        val arrivalConnectionType: String,
        val departureConnectionType: String,
        minDelay: Int,
        arrivalUic: String,
        departureUic: String,
    ) : StationConnection(minDelay, arrivalUic, departureUic)
}

enum class ConnectionType {
    BY_MODE, BY_RICS, BY_BRAND
}

enum class Mode {
    F, R
}

private val logger = LoggerFactory.getLogger("SncfTransferRules")

// Rows outside the GTFS scope are dropped : unknown UIC, or train connection dates not overlapping feedDates (dates are capped to it)
fun loadSncfTransferRules(gtfsStops: Set<String>, feedDates: ClosedRange<LocalDate>, sncfRulesZip: File): Pair<MutableList<TrainConnection>, MutableList<StationConnection>> {
    val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    val trainConnections = mutableListOf<TrainConnection>()
    val stationConnections = mutableListOf<StationConnection>()

    ZipInputStream(sncfRulesZip.inputStream().buffered()).use { stream ->
        var entry = stream.nextEntry
        while (entry != null) {
            when (entry.name) {
                "Export_TRAIN_CONNECTION_TIMES.csv" -> {
                    val parser = CSVFormat.DEFAULT
                        .withDelimiter(';')
                        .withFirstRecordAsHeader()
                        .parse(InputStreamReader(stream, Charsets.UTF_8))

                    var outOfScopeUicCount = 0
                    var outOfScopeDatesCount = 0

                    for (row in parser) {
                        val arrivalUic = row["ARRIVAL_STATION_UIC"].trim()
                        val departureUic = row["DEPARTURE_STATION_UIC"].trim()
                        if (arrivalUic !in gtfsStops || departureUic !in gtfsStops) {
                            outOfScopeUicCount++
                            continue
                        }

                        // Rules' validity periods can extend beyond the GTFS date range, so we cap them to it
                        val startDate = maxOf(LocalDate.parse(row["START_DATE"], dateFormatter), feedDates.start)
                        val endDate = minOf(LocalDate.parse(row["END_DATE"], dateFormatter), feedDates.endInclusive)
                        if (startDate > endDate) {
                            outOfScopeDatesCount++
                            continue
                        }

                        trainConnections.add(
                            TrainConnection(
                                arrivalTrain = row["ARRIVAL_TRAIN"].trim().trimStart('0'),
                                departureTrain = row["DEPARTURE_TRAIN"].trim().trimStart('0'),
                                minDelay = row["MIN_DELAY"].toInt(),
                                arrivalStationUic = arrivalUic,
                                departureStationUic = departureUic,
                                startDate = startDate,
                                endDate = endDate,
                            )
                        )
                    }
                    logger.warn("Filtered $outOfScopeUicCount out of scope UIC rows and $outOfScopeDatesCount out of feed dates rows from Export_TRAIN_CONNECTION_TIMES.csv")
                }

                "Export_CONNECTION_TIMES.csv" -> {
                    val parser = CSVFormat.DEFAULT
                        .withDelimiter(';')
                        .withFirstRecordAsHeader()
                        .parse(InputStreamReader(stream, Charsets.UTF_8))

                    var outOfScopeUicCount = 0

                    for (row in parser) {
                        val departureUic = row["DEPARTURE_STATION_UIC"].trim()
                        val arrivalUic = row["ARRIVAL_STATION_UIC"].trim()
                        if (departureUic !in gtfsStops || arrivalUic !in gtfsStops) {
                            outOfScopeUicCount++
                            continue
                        }

                        val arrivalMode = row["ARRIVAL_MODE"]?.takeIf { it.isNotBlank() }?.let { Mode.valueOf(it) }
                        val departureMode = row["DEPARTURE_MODE"]?.takeIf { it.isNotBlank() }?.let { Mode.valueOf(it) }
                        val arrivalRics = row["ARRIVAL_RICS"]?.takeIf { it.isNotBlank() }?.trim()
                        val departureRics = row["DEPARTURE_RICS"]?.takeIf { it.isNotBlank() }?.trim()
                        val arrivalConnectionType = row["ARRIVAL_CONNECTION_TYPE"]?.takeIf { it.isNotBlank() }?.trim()
                        val departureConnectionType = row["DEPARTURE_CONNECTION_TYPE"]?.takeIf { it.isNotBlank() }?.trim()
                        val minDelay = row["MIN_DELAY"].toInt()

                        val connectionType = getAndValidateStationConnectionType(
                            arrivalMode, departureMode,
                            arrivalRics, departureRics,
                            arrivalConnectionType, departureConnectionType,
                            arrivalUic, departureUic,
                        ) ?: continue

                        val connection = when (connectionType) {
                            ConnectionType.BY_MODE -> StationConnection.ByMode(
                                arrivalMode = arrivalMode!!,
                                departureMode = departureMode!!,
                                minDelay = minDelay,
                                arrivalUic = arrivalUic,
                                departureUic = departureUic,
                            )
                            ConnectionType.BY_RICS -> StationConnection.ByRics(
                                arrivalRics = arrivalRics!!,
                                departureRics = departureRics!!,
                                minDelay = minDelay,
                                arrivalUic = arrivalUic,
                                departureUic = departureUic,
                            )
                            ConnectionType.BY_BRAND -> StationConnection.ByBrand(
                                arrivalConnectionType = arrivalConnectionType!!,
                                departureConnectionType = departureConnectionType!!,
                                minDelay = minDelay,
                                arrivalUic = arrivalUic,
                                departureUic = departureUic,
                            )
                        }

                        stationConnections.add(connection)
                    }

                    logger.warn("Filtered $outOfScopeUicCount out of scope rows from Export_CONNECTION_TIMES.csv")
                }
            }
            stream.closeEntry()
            entry = stream.nextEntry
        }
    }
    return trainConnections to stationConnections
}

// In Export_CONNECTION_TIMES.csv
// Modes, RICS and ConnectionTypes are set exclusively and can't be set together
private fun getAndValidateStationConnectionType(
    arrivalMode: Mode?,
    departureMode: Mode?,
    arrivalRics: String?,
    departureRics: String?,
    arrivalConnectionType: String?,
    departureConnectionType: String?,
    arrivalUic: String,
    departureUic: String,
): ConnectionType? {
    val bothModesSet = arrivalMode != null && departureMode != null
    val bothRicsSet = arrivalRics != null && departureRics != null
    val bothConnectionTypesSet = arrivalConnectionType != null && departureConnectionType != null

    val partialMode = (arrivalMode != null) xor (departureMode != null)
    val partialRics = (arrivalRics != null) xor (departureRics != null)
    val partialConnectionType = (arrivalConnectionType != null) xor (departureConnectionType != null)

    if (partialMode || partialRics || partialConnectionType) {
        logger.warn("Incomplete pair for UIC ($departureUic-$arrivalUic): arrivalMode=$arrivalMode, departureMode=$departureMode, arrivalRics=$arrivalRics, departureRics=$departureRics, arrivalConnectionType=$arrivalConnectionType, departureConnectionType=$departureConnectionType")
        return null
    }

    if (listOf(bothModesSet, bothRicsSet, bothConnectionTypesSet).count { it } != 1) {
        logger.warn("Mutually exclusive violation for UIC ($departureUic-$arrivalUic): hasMode=$bothModesSet, hasRics=$bothRicsSet, hasConnectionType=$bothConnectionTypesSet")
        return null
    }

    return when {
        bothModesSet -> ConnectionType.BY_MODE
        bothRicsSet -> ConnectionType.BY_RICS
        bothConnectionTypesSet -> ConnectionType.BY_BRAND
        else -> error("Unreachable after validation")
    }
}