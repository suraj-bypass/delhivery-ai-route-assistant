package com.routeassistant.app.data.routing

import com.routeassistant.app.util.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OSRM public demo server - FREE prototype provider.
 * Endpoints: /table/v1/driving/ (matrix) and /route/v1/driving/ (single leg).
 *
 * Compliance / limits:
 * - Demo server is NOT for production / heavy use. Keep requests small,
 *   debounce calls, cache results, and do not hammer it.
 * - A simple in-process throttle spaces calls out. If the server returns
 *   429 / 5xx, we return Err and the caller falls back to haversine.
 * - Cache is keyed by the full coordinate list so repeated Recalculate
 *   calls for the same stops do not re-hit the server.
 *
 * For production: self-host OSRM, or swap to a paid provider by
 * implementing [RoutingProvider].
 */
class OsrmRoutingProvider(
    private val baseUrl: String = "https://router.project-osrm.org",
    private val userAgent: String = "RouteAssistant-Prototype/1.0 (contact: user-provided)",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
) : RoutingProvider {

    private val mutex = Mutex()
    private var lastCallAtMs: Long = 0L
    private val matrixCache = LinkedHashMap<String, MatrixResult>()

    private suspend fun throttle() {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val wait = 300L - (now - lastCallAtMs) // gentle spacing; OSRM has no fixed 1/sec rule, but do not burst
            if (wait > 0) kotlinx.coroutines.delay(wait)
            lastCallAtMs = System.currentTimeMillis()
        }
    }

    override suspend fun distanceMatrix(points: List<RoutePoint>): AppResult<MatrixResult> {
        if (points.size < 2) {
            return AppResult.Ok(MatrixResult(matrix = emptyList(), usedRoadDistances = false))
        }
        // OSRM table has practical limits; cap at 100 points for the demo server.
        // Larger sets are chunked by the caller / optimizer via haversine fallback.
        if (points.size > 100) {
            return AppResult.Err("Too many points for OSRM demo table (max 100). Using straight-line fallback.")
        }
        val key = points.joinToString(";") { "${it.lng},${it.lat}" }
        synchronized(matrixCache) {
            matrixCache[key]?.let { return AppResult.Ok(it) }
        }
        throttle()
        return try {
            withContext(Dispatchers.IO) {
                val coords = points.joinToString(";") { "${it.lng},${it.lat}" }
                val url = "$baseUrl/table/v1/driving/$coords?annotations=distance,duration"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.code !in 200..299) {
                        return@withContext AppResult.Err("OSRM table failed: HTTP ${response.code}. Falling back to straight-line distance.")
                    }
                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)
                    if (json.optString("code") != "Ok") {
                        return@withContext AppResult.Err("OSRM table error: ${json.optString("message")}. Falling back to straight-line distance.")
                    }
                    val durations = json.optJSONArray("durations")
                    val distances = json.optJSONArray("distances")
                    if (durations == null || distances == null) {
                        return@withContext AppResult.Err("OSRM returned an incomplete matrix. Falling back to straight-line distance.")
                    }
                    val n = points.size
                    val matrix = List(n) { i ->
                        List(n) { j ->
                            val durSec = durations.optJSONArray(i)?.optDouble(j, -1.0) ?: -1.0
                            val distM = distances.optJSONArray(i)?.optDouble(j, -1.0) ?: -1.0
                            if (durSec < 0 || distM < 0) {
                                // OSRM has no route for this pair - haversine fallback for this cell.
                                val h = Haversine.distanceKm(points[i].lat, points[i].lng, points[j].lat, points[j].lng)
                                MatrixCell(h, Haversine.estimateDurationMin(h), false)
                            } else {
                                MatrixCell(distM / 1000.0, durSec / 60.0, true)
                            }
                        }
                    }
                    val result = MatrixResult(matrix, usedRoadDistances = true)
                    synchronized(matrixCache) {
                        if (matrixCache.size > 20) matrixCache.clear()
                        matrixCache[key] = result
                    }
                    AppResult.Ok(result)
                }
            }
        } catch (e: Exception) {
            AppResult.Err("OSRM unreachable: ${e.message ?: "network error"}. Falling back to straight-line distance.", e)
        }
    }

    override suspend fun route(origin: RoutePoint, destination: RoutePoint): AppResult<RouteResult> {
        throttle()
        return try {
            withContext(Dispatchers.IO) {
                val coords = "${origin.lng},${origin.lat};${destination.lng},${destination.lat}"
                val url = "$baseUrl/route/v1/driving/$coords?overview=false"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.code !in 200..299) {
                        return@withContext AppResult.Err("OSRM route failed: HTTP ${response.code}")
                    }
                    val json = JSONObject(response.body?.string().orEmpty())
                    val routes = json.optJSONArray("routes")
                    if (routes == null || routes.length() == 0) {
                        return@withContext AppResult.Err("OSRM found no route for this leg.")
                    }
                    val r = routes.getJSONObject(0)
                    AppResult.Ok(
                        RouteResult(
                            distanceKm = r.optDouble("distance", 0.0) / 1000.0,
                            durationMin = r.optDouble("duration", 0.0) / 60.0,
                            isRoadDistance = true
                        )
                    )
                }
            }
        } catch (e: Exception) {
            AppResult.Err("OSRM route failed: ${e.message}", e)
        }
    }
}
