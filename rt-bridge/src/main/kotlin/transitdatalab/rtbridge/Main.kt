// GTFS-RT bridge. Every 30 s: fetch the SNCF trip updates and service alerts from the PAN, rewrite their RT trip ids
// into the ids of the SNCF GTFS, keep the result in memory and serve it over HTTP. Every 5 min: revalidate the SNCF
// GTFS and rebuild the lookup when it changed.
package transitdatalab.rtbridge

import com.google.transit.realtime.GtfsRealtime.FeedEntity
import com.google.transit.realtime.GtfsRealtime.FeedMessage
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val GTFS_URL = "https://eu.ftp.opendatasoft.com/sncf/plandata/Export_OpenData_SNCF_GTFS_NewTripId.zip"
private val STALE_AFTER = Duration.ofMinutes(5)
private val HTTP_DATE = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH).withZone(ZoneOffset.UTC)

private class Gtfs(val lookup: Lookup, val modified: String?, val loadedAt: Instant)
private class Served(val body: ByteArray, val at: Instant, val feedTimestamp: Instant, val entities: String)

private class Feed(val url: String, val patch: (List<FeedEntity>, Lookup) -> List<FeedEntity>) {
    @Volatile var served: Served? = null
    @Volatile var refreshes = 0
    @Volatile var errors = 0
    @Volatile var lastError: Map<String, Any>? = null
}

private val feeds = mapOf(
    "trip-updates" to Feed("https://proxy.transport.data.gouv.fr/resource/sncf-gtfs-rt-trip-updates", ::patchTripUpdates),
    "service-alerts" to Feed("https://proxy.transport.data.gouv.fr/resource/sncf-gtfs-rt-service-alerts", ::patchAlerts),
)
private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build()
private val startedAt = Instant.now()
@Volatile private var gtfs: Gtfs? = null

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    HttpServer.create(InetSocketAddress(port), 0).apply {
        createContext("/", ::handle)
        executor = Executors.newVirtualThreadPerTaskExecutor()
        start()
    }
    println("listening on $port")
    // one thread: a lookup swap never overlaps a feed refresh, and at startup the feeds run right after the first lookup
    Executors.newSingleThreadScheduledExecutor().apply {
        scheduleWithFixedDelay(logged(::refreshLookup), 0, 5, TimeUnit.MINUTES)
        scheduleWithFixedDelay(logged(::refreshFeeds), 0, 30, TimeUnit.SECONDS)
    }
}

private fun logged(task: () -> Unit) = Runnable { try { task() } catch (e: Exception) { System.err.println(e) } } // an escaping exception would cancel the schedule

private fun fetch(url: String, ifModifiedSince: String? = null): HttpResponse<ByteArray> {
    val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
        .apply { ifModifiedSince?.let { header("If-Modified-Since", it) } }.build()
    return http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        .also { check(it.statusCode() == 200 || it.statusCode() == 304) { "GET $url: ${it.statusCode()}" } }
}

private fun refreshLookup() {
    val current = gtfs
    val response = fetch(GTFS_URL, current?.modified)
    if (response.statusCode() == 304) return
    val modified = response.headers().firstValue("last-modified").orElse(null) // none: downloaded again next time
    val lookup = lookupFromZip(response.body())
    gtfs = Gtfs(lookup, modified, Instant.now())
    println("GTFS of $modified: ${lookup.trips.size} trips, ${lookup.byShort.size} train numbers")
}

private fun refreshFeeds() {
    val lookup = gtfs?.lookup ?: return
    for ((name, feed) in feeds) try {
        val message = FeedMessage.parseFrom(fetch(feed.url).body())
        val entities = feed.patch(message.entityList, lookup)
        feed.served = Served(
            body = message.toBuilder().clearEntity().addAllEntity(entities).build().toByteArray(),
            at = Instant.now(),
            feedTimestamp = Instant.ofEpochSecond(message.header.timestamp),
            entities = "${message.entityCount} -> ${entities.size}",
        )
        feed.refreshes++
    } catch (e: Exception) {
        feed.errors++
        feed.lastError = mapOf("at" to Instant.now(), "message" to e.toString())
        System.err.println("$name: $e") // the previous feed stays served
    }
}

private fun handle(ex: HttpExchange) {
    val name = ex.requestURI.path.removePrefix("/")
    val feed = feeds[name]
    when {
        name == "" -> status().let { ex.reply(if (it["fresh"] == true) 200 else 503, "application/json", json(it)) }
        name == "stats" -> ex.reply(200, "application/json", json(stats()))
        feed == null -> ex.reply(404)
        else -> feed.served?.let {
            ex.responseHeaders.add("last-modified", HTTP_DATE.format(it.at))
            ex.responseHeaders.add("cache-control", "no-cache")
            ex.reply(200, "application/x-protobuf", it.body)
        } ?: ex.reply(503)
    }
}

private fun HttpExchange.reply(code: Int, contentType: String? = null, body: Any? = null) {
    val bytes = if (body is ByteArray) body else body?.toString()?.toByteArray()
    contentType?.let { responseHeaders.add("content-type", it) }
    val head = requestMethod == "HEAD"
    sendResponseHeaders(code, if (head || bytes == null) -1 else bytes.size.toLong())
    if (!head && bytes != null) responseBody.write(bytes)
    close()
}

private fun feedInfo(s: Served) = mapOf("fetched" to s.at, "feedTimestamp" to s.feedTimestamp, "entities" to s.entities, "bytes" to s.body.size)

private fun status() = mapOf(
    "fresh" to feeds.values.all { f -> f.served.let { it != null && Duration.between(it.at, Instant.now()) < STALE_AFTER } },
    "gtfsModified" to gtfs?.modified,
    "feeds" to feeds.mapNotNull { (name, f) -> f.served?.let { name to feedInfo(it) } }.toMap(),
)

private fun stats() = mapOf(
    "fresh" to status()["fresh"],
    "bridge" to mapOf(
        "startedAt" to startedAt,
        "uptimeSeconds" to Duration.between(startedAt, Instant.now()).seconds,
        "heapMB" to Runtime.getRuntime().run { (totalMemory() - freeMemory()) / 1_000_000 },
        "java" to Runtime.version(),
    ),
    "gtfs" to gtfs?.let { mapOf("modified" to it.modified, "loadedAt" to it.loadedAt, "trips" to it.lookup.trips.size, "trainNumbers" to it.lookup.byShort.size) },
    "feeds" to feeds.mapValues { (_, f) -> f.served?.let(::feedInfo).orEmpty() + mapOf("refreshes" to f.refreshes, "errors" to f.errors, "lastError" to f.lastError) },
)

// Maps, numbers, booleans, null; anything else as its toString (Instant: ISO-8601)
private fun json(value: Any?): String = when (value) {
    null, is Number, is Boolean -> value.toString()
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> json(k.toString()) + ":" + json(v) }
    else -> buildString {
        append('"')
        for (c in value.toString()) when {
            c == '"' || c == '\\' -> append('\\').append(c)
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
