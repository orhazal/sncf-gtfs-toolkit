package mobilityfeeds.patch

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.slf4j.LoggerFactory
import mobilityfeeds.sncf.GtfsIndexes
import mobilityfeeds.sncf.Mode
import mobilityfeeds.sncf.SncfBrand
import mobilityfeeds.sncf.StationConnection
import mobilityfeeds.sncf.TrainConnection
import mobilityfeeds.sncf.loadSncfTransferRules
import mobilityfeeds.sncf.serviceIdAfter
import java.io.File
import java.time.LocalDate

private data class TrainTransferKey(val fromStopId: String, val toStopId: String, val fromTripId: String, val toTripId: String)

data class Transfer(
    val fromStopId: String,
    val toStopId: String,
    val transferType: Int,
    val minTransferTime: Int? = null,
    val fromRouteId: String? = null,
    val toRouteId: String? = null,
    val fromTripId: String? = null,
    val toTripId: String? = null,
    val serviceId: String? = null,
    val priority: PrioritySource,
)

// Highest priority is zero
enum class PrioritySource(val value: Int) {
    TRAIN_NUMBER(0),
    // RICS or BRAND_CARRIER
    ONE_TO_ONE(10),
    ONE_TO_ALL(20),
    ALL_TO_ALL(30),
    // By mode : (F)erré or (R)outier
    MODE(40),
}

private val logger = LoggerFactory.getLogger("transfers")

// Builds transfers.txt from the SNCF transfer rules applied to the GTFS indexes.
// Returns the services created for train connections (service_id -> validity dates), to append to calendar_dates.txt
fun patchTransfers(indexes: GtfsIndexes, sncfRulesZip: File, output: File): Map<String, Set<LocalDate>> {
    val (trainConnections, stationConnections) = loadSncfTransferRules(indexes.uic7Codes, indexes.feedDates, sncfRulesZip)

    // TODO : useless transfers between the same origin and destination stop point (quay) ?
    val transfers = mutableSetOf<Transfer>()
    // For Export_CONNECTION_TIMES.csv
    for (stationConnection in stationConnections) {
        when (stationConnection) {
            is StationConnection.ByMode -> transfers.addAll(processByModeConnection(stationConnection, indexes.stopsByModeByUic7))
            is StationConnection.ByRics -> transfers.addAll(processByRicsConnection(stationConnection, indexes.stopAndRouteByRicsByUic7))
            is StationConnection.ByBrand -> transfers.addAll(processByBrandConnection(stationConnection, indexes.stopByBrandByUic7))
        }
    }
    // For Export_TRAIN_CONNECTION_TIMES.csv
    val (trainTransfers, datesByNewServiceId) = processTrainConnections(trainConnections, indexes.stopAndTripByTrainNumberByUic7, indexes.nextUsableServiceId)
    transfers.addAll(trainTransfers)

    val deduplicatedTransfers = deduplicateByPriority(transfers)
    writeTransfersFile(deduplicatedTransfers, output)
    logger.info("(${deduplicatedTransfers.size} unique / ${transfers.size} raw) transfers written in ${output.path}")
    return datesByNewServiceId
}

// A train connection only applies on startDate..endDate. Several rules can target the same trip pair with different periods,
// so one transfer is produced per trip pair, on a service_id covering the union of those periods (trip pairs with the same dates
// share a service_id, allocated from nextUsableServiceId upwards). Rules disagreeing on the delay are merged on the most
// restrictive one and logged. Returns transfers + service_id -> dates.
private fun processTrainConnections(
    connections: List<TrainConnection>,
    stopAndTripByTrainNumberByUic7: Map<String, Map<String, Set<Pair<String, String>>>>,
    nextUsableServiceId: String,
): Pair<Set<Transfer>, Map<String, Set<LocalDate>>> {
    // insertion-ordered maps: service ids are allocated in encounter order, so the output is deterministic
    val rulesByTransfer = LinkedHashMap<TrainTransferKey, MutableList<TrainConnection>>()
    for (connection in connections) {
        val fromSet = stopAndTripByTrainNumberByUic7[connection.arrivalStationUic]?.get(connection.arrivalTrain) ?: emptySet()
        val toSet = stopAndTripByTrainNumberByUic7[connection.departureStationUic]?.get(connection.departureTrain) ?: emptySet()
        for ((fromTrip, fromStop) in fromSet) {
            for ((toTrip, toStop) in toSet) {
                rulesByTransfer.getOrPut(TrainTransferKey(fromStop, toStop, fromTrip, toTrip)) { mutableListOf() } += connection
            }
        }
    }

    val serviceIdByDates = LinkedHashMap<Set<LocalDate>, String>() // To have unique service ids for the same set of dates
    val transfers = rulesByTransfer.map { (key, rules) ->
        val delays = rules.map { it.minDelay }.distinct()
        val minDelay = if (-1 in delays) -1 else delays.max() // -1 = forbidden, the most restrictive of all
        if (delays.size > 1) {
            val rule = rules.first()
            logger.warn("[TRAIN] ${rule.arrivalTrain} -> ${rule.departureTrain} at ${rule.arrivalStationUic} -> ${rule.departureStationUic} : ${rules.size} rules with delays $delays, keeping $minDelay")
            // TODO : should flag as error to report to SNCF?
        }

        val dates = rules.flatMapTo(sortedSetOf()) { it.startDate.datesUntil(it.endDate.plusDays(1)).toList() }
        val serviceId = serviceIdByDates.getOrPut(dates) { serviceIdAfter(nextUsableServiceId, offset = serviceIdByDates.size) }
        val (transferType, minTransferTime) = gtfsTransfer(minDelay)
        Transfer(
            fromStopId = key.fromStopId,
            toStopId = key.toStopId,
            fromTripId = key.fromTripId,
            toTripId = key.toTripId,
            transferType = transferType,
            minTransferTime = minTransferTime,
            serviceId = serviceId,
            priority = PrioritySource.TRAIN_NUMBER,
        )
    }.toSet()

    return transfers to serviceIdByDates.entries.associate { (dates, serviceId) -> serviceId to dates } // invert the (range, service_id) to (service_id, range)
}

private fun processByModeConnection(connection: StationConnection.ByMode, stopsByModeByUic7: Map<String, Map<Mode, Set<String>>>): Set<Transfer> {
    val fromStops = stopsByModeByUic7[connection.arrivalUic]?.get(connection.arrivalMode) ?: emptySet()
    val toStops = stopsByModeByUic7[connection.departureUic]?.get(connection.departureMode) ?: emptySet()
    val (transferType, minTransferTime) = gtfsTransfer(connection.minDelay)

    if (fromStops.isEmpty() || toStops.isEmpty()) {
        return emptySet()
    }

    val transfers = LinkedHashSet<Transfer>(fromStops.size * toStops.size)
    for (fromStopId in fromStops) {
        for (toStopId in toStops) {
            transfers.add(
                Transfer(
                    fromStopId = fromStopId,
                    toStopId = toStopId,
                    transferType = transferType,
                    minTransferTime = minTransferTime,
                    priority = PrioritySource.MODE,
                )
            )
        }
    }
    return transfers
}

private fun processByRicsConnection(connection: StationConnection.ByRics, stopAndRouteByRicsByUic7: Map<String, Map<String, Set<Pair<String, String>>>>): Set<Transfer> {
    val isFromAllRics = connection.arrivalRics.equals("ALL", true)
    val isToAllRics = connection.departureRics.equals("ALL", true)

    val fromRoutesAndStops: Set<Pair<String, String>> = if (isFromAllRics) {
        stopAndRouteByRicsByUic7[connection.arrivalUic]?.values?.flatten()?.toSet()
    } else {
        stopAndRouteByRicsByUic7[connection.arrivalUic]?.get(connection.arrivalRics)
    } ?: emptySet()

    val toRoutesAndStops: Set<Pair<String, String>> = if (isToAllRics) {
        stopAndRouteByRicsByUic7[connection.departureUic]?.values?.flatten()?.toSet()
    } else {
        stopAndRouteByRicsByUic7[connection.departureUic]?.get(connection.departureRics)
    } ?: emptySet()

    if (fromRoutesAndStops.isEmpty() || toRoutesAndStops.isEmpty()) {
        return emptySet()
    }

    val priority = priorityOf(isFromAllRics, isToAllRics)
    val (transferType, minTransferTime) = gtfsTransfer(connection.minDelay)

    val transfers = LinkedHashSet<Transfer>(fromRoutesAndStops.size * toRoutesAndStops.size)
    for ((fromRouteId, fromStopId) in fromRoutesAndStops) {
        for ((toRouteId, toStopId) in toRoutesAndStops) {
            transfers.add(
                Transfer(
                    fromStopId = fromStopId,
                    toStopId = toStopId,
                    fromRouteId = fromRouteId,
                    toRouteId = toRouteId,
                    transferType = transferType,
                    minTransferTime = minTransferTime,
                    priority = priority,
                )
            )
        }
    }
    return transfers
}

private fun processByBrandConnection(connection: StationConnection.ByBrand, stopByBrandByUic7: Map<String, Map<SncfBrand, String>>): Set<Transfer> {
    val isFromAllBrands = connection.arrivalConnectionType.equals("ALL", true)
    val isToAllBrands = connection.departureConnectionType.equals("ALL", true)

    val fromStops = if (isFromAllBrands) {
        stopByBrandByUic7[connection.arrivalUic]?.values?.toSet()
    } else {
        stopByBrandByUic7[connection.arrivalUic]?.entries
            ?.filter { areGtfsAndIdhBrandEquivalent(connection.arrivalConnectionType, it.key) }
            ?.map { it.value }
    } ?: emptySet()

    val toStops = if (isToAllBrands) {
        stopByBrandByUic7[connection.departureUic]?.values?.toSet()
    } else {
        stopByBrandByUic7[connection.departureUic]?.entries
            ?.filter { areGtfsAndIdhBrandEquivalent(connection.departureConnectionType, it.key) }
            ?.map { it.value }
    } ?: emptySet()

    if (fromStops.isEmpty() || toStops.isEmpty()) {
        return emptySet()
    }

    val priority = priorityOf(isFromAllBrands, isToAllBrands)
    val (transferType, minTransferTime) = gtfsTransfer(connection.minDelay)

    val transfers = LinkedHashSet<Transfer>(fromStops.size * toStops.size)
    for (fromStopId in fromStops) {
        for (toStopId in toStops) {
            transfers.add(
                Transfer(
                    fromStopId = fromStopId,
                    toStopId = toStopId,
                    transferType = transferType,
                    minTransferTime = minTransferTime,
                    priority = priority,
                )
            )
        }
    }
    return transfers
}

// SNCF transfer rules MIN_DELAY: -1 = forbidden, else minutes. GTFS: transfer_type 3 = not possible, 2 = min_transfer_time in seconds
private fun gtfsTransfer(minDelay: Int): Pair<Int, Int?> = if (minDelay == -1) 3 to null else 2 to minDelay * 60

private fun priorityOf(fromAll: Boolean, toAll: Boolean): PrioritySource = when {
    fromAll && toAll -> PrioritySource.ALL_TO_ALL
    fromAll xor toAll -> PrioritySource.ONE_TO_ALL
    else -> PrioritySource.ONE_TO_ONE
}

private fun areGtfsAndIdhBrandEquivalent(brandFromIdh: String, brandFromGtfs: SncfBrand): Boolean =
    when (brandFromIdh) {
        "TER" -> brandFromGtfs in setOf(SncfBrand.TRAIN_TER, SncfBrand.CAR_TER)
        "OUIGO" -> brandFromGtfs in setOf(SncfBrand.OUIGO, SncfBrand.OUIGO_TRAIN_CLASSIQUE)
        else -> brandFromGtfs.value.contains(brandFromIdh, true) // "TGV" matches "TGV INOUI"
    }

// Regroupe sur le tuple d'identité GTFS
// Pour chaque groupe, on garde le transfert de plus haute priorité (plus petite valeur de PrioritySource.value)
// Les groupes à un seul élément conservent leur unique élément
// Les groupes à multiples éléments (donc priorité égale comme 2 règles en ONE_TO_ALL_RICS) conservent l'élément avec le temps de transfert le plus grand (le plus restrictif)
fun deduplicateByPriority(transfers: Set<Transfer>): Set<Transfer> =
    transfers
        .groupBy {
            listOf(
                it.fromStopId, it.toStopId, it.fromTripId, it.toTripId,
                it.fromRouteId, it.toRouteId, it.serviceId, it.transferType,
            )
        }
        .map { (_, group) ->
            val minValue = group.minOf { it.priority.value }
            group
                .filter { it.priority.value == minValue }
                .maxBy { it.minTransferTime ?: -1 }
        }
        .toSet()

private fun writeTransfersFile(transfers: Set<Transfer>, output: File) {
    val format = CSVFormat.DEFAULT.builder()
        .setRecordSeparator("\n") // SNCF GTFS files are LF
        .setHeader(
            "from_stop_id", "to_stop_id",
            "transfer_type", "min_transfer_time",
            "from_route_id", "to_route_id",
            "from_trip_id", "to_trip_id",
            "service_id"
        )
        .get()
    output.bufferedWriter().use { writer ->
        CSVPrinter(writer, format).use { printer ->
            transfers.forEach {
                printer.printRecord(
                    it.fromStopId, it.toStopId, it.transferType, it.minTransferTime,
                    it.fromRouteId, it.toRouteId, it.fromTripId, it.toTripId, it.serviceId
                )
            }
        }
    }
}
