package com.routeassistant.app.data.geocoding

import com.routeassistant.app.util.AppResult

/**
 * Provider abstraction for geocoding.
 * Swap implementation by providing a different GeocoderProvider -
 * the ViewModel / optimizer never talk to Nominatim / Google directly.
 */
interface GeocoderProvider {
    /** Resolve one address to lat/lng. Returns Err on failure - never throws. */
    suspend fun geocode(address: String): AppResult<GeocodeResult>
}

data class GeocodeResult(
    val lat: Double,
    val lng: Double,
    val displayName: String?
)

sealed class GeocodeOutcome {
    data class Success(val result: GeocodeResult) : GeocodeOutcome()
    data class Failure(val message: String) : GeocodeOutcome()
}
