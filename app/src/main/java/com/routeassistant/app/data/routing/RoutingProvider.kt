package com.routeassistant.app.data.routing

import com.routeassistant.app.util.AppResult

/** Point used by routing providers. */
data class RoutePoint(val lat: Double, val lng: Double)

/** One cell of a distance/duration matrix. */
data class MatrixCell(
    val distanceKm: Double,
    val durationMin: Double,
    val isRoadDistance: Boolean
)

data class MatrixResult(
    /** matrix[i][j] = from point i to point j */
    val matrix: List<List<MatrixCell>>,
    val usedRoadDistances: Boolean
)

data class RouteResult(
    val distanceKm: Double,
    val durationMin: Double,
    val isRoadDistance: Boolean
)

/**
 * Provider abstraction for routing / distance matrix.
 * Swap OSRM for Google / Mapbox / Valhalla by implementing this interface.
 */
interface RoutingProvider {
    /**
     * Distance/duration matrix for [points] (points[0] is usually the start).
     * On failure returns Err - caller MUST fall back to haversine, never crash.
     */
    suspend fun distanceMatrix(points: List<RoutePoint>): AppResult<MatrixResult>

    /** Single leg route. */
    suspend fun route(origin: RoutePoint, destination: RoutePoint): AppResult<RouteResult>
}
