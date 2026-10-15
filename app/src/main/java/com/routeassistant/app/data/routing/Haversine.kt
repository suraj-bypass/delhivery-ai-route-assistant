package com.routeassistant.app.data.routing

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Straight-line distance. Used ONLY as a fallback when OSRM is unavailable. */
object Haversine {
    private const val EARTH_KM = 6371.0

    fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_KM * asin(sqrt(a))
    }

    /** Rough duration estimate from straight-line distance at ~25 km/h city speed. */
    fun estimateDurationMin(distanceKm: Double): Double = (distanceKm / 25.0) * 60.0
}
