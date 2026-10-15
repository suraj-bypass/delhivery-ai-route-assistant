package com.routeassistant.app.data.geocoding

import com.routeassistant.app.util.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Nominatim (OpenStreetMap) geocoder - FREE prototype provider.
 *
 * OSM Usage Policy compliance (https://operations.osmfoundation.org/policies/nominatim/):
 * - Max 1 request/second. Enforced here with a Mutex + last-call timestamp.
 * - Must send a valid, identifying User-Agent. The default below MUST be
 *   replaced with a real contact (see [userAgent]). If the caller does not
 *   supply one, we still send a descriptive UA but log that it is a placeholder.
 * - Results are cached in-memory (per-process) to avoid repeat queries.
 * - HTTP 429 / 403 are treated as rate-limit / blocked, NOT retried in a loop.
 *   The caller surfaces the error and offers a manual Retry button.
 *
 * DO NOT use this for bulk / production traffic. For production, use a
 * paid geocoder or your own Nominatim instance.
 */
class NominatimGeocoder(
    private val userAgent: String = "RouteAssistant-Prototype/1.0 (contact: user-provided - set in ParcelViewModel factory)",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
) : GeocoderProvider {

    companion object {
        private const val BASE_URL = "https://nominatim.openstreetmap.org/search"
        private const val MIN_INTERVAL_MS = 1100L // >1s: 1 req/sec policy + margin
    }

    private val mutex = Mutex()
    private var lastCallAtMs: Long = 0L
    private val cache = LinkedHashMap<String, GeocodeResult>()

    override suspend fun geocode(address: String): AppResult<GeocodeResult> {
        val key = address.trim().lowercase()
        if (key.isBlank()) return AppResult.Err("Empty address")
        synchronized(cache) { cache[key]?.let { return AppResult.Ok(it) } }

        // Rate-limit: serialise + space out calls to max 1/sec.
        mutex.withLock {
            val now = System.currentTimeMillis()
            val wait = MIN_INTERVAL_MS - (now - lastCallAtMs)
            if (wait > 0) delay(wait)
            lastCallAtMs = System.currentTimeMillis()
        }

        return try {
            withContext(Dispatchers.IO) {
                val url = "$BASE_URL?format=json&limit=1&q=${encode(address)}"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .build()
                client.newCall(request).execute().use { response ->
                    when (response.code) {
                        429 -> return@withContext AppResult.Err(
                            "Nominatim rate limit (429). Wait a minute and tap Retry. " +
                                "Do not spam retries - OSM policy is 1 req/sec."
                        )
                        403 -> return@withContext AppResult.Err(
                            "Nominatim blocked this request (403). Check User-Agent / usage policy."
                        )
                        !in 200..299 -> return@withContext AppResult.Err(
                            "Geocoding failed: HTTP ${response.code}"
                        )
                    }
                    val body = response.body?.string().orEmpty()
                    val arr = JSONArray(body)
                    if (arr.length() == 0) {
                        return@withContext AppResult.Err(
                            "Address not found. Try adding area / pincode, e.g. 'Sector 62, Noida 201309'."
                        )
                    }
                    val obj = arr.getJSONObject(0)
                    val lat = obj.optString("lat").toDoubleOrNull()
                    val lng = obj.optString("lon").toDoubleOrNull()
                    if (lat == null || lng == null) {
                        return@withContext AppResult.Err("Geocoder returned an invalid coordinate.")
                    }
                    val result = GeocodeResult(
                        lat = lat,
                        lng = lng,
                        displayName = obj.optString("display_name").takeIf { it.isNotBlank() }
                    )
                    synchronized(cache) {
                        if (cache.size > 500) cache.clear()
                        cache[key] = result
                    }
                    AppResult.Ok(result)
                }
            }
        } catch (e: Exception) {
            AppResult.Err("Geocoding failed: ${e.message ?: "network error"}. Check internet and retry.", e)
        }
    }

    private fun encode(s: String): String =
        java.net.URLEncoder.encode(s, Charsets.UTF_8.name())
}
