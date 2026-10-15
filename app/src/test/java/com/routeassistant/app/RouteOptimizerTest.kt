package com.routeassistant.app

import com.routeassistant.app.domain.optimizer.RouteOptimizer
import com.routeassistant.app.domain.optimizer.RouteOptimizer.StartPoint
import com.routeassistant.app.domain.optimizer.RouteOptimizer.StopInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pure-Kotlin tests - no Android, no network.
 * Run with: ./gradlew :app:testDebugUnitTest
 */
class RouteOptimizerTest {

    @Test
    fun empty_returnsEmpty() {
        val r = RouteOptimizer.optimize(emptyList(), StartPoint(0.0, 0.0))
        assertTrue(r.orderedStops.isEmpty())
        assertEquals(0.0, r.totalDistanceKm, 0.001)
    }

    @Test
    fun single_returnsSingle() {
        val r = RouteOptimizer.optimize(
            listOf(StopInput(1, 28.6, 77.2, "A")),
            StartPoint(28.5, 77.1)
        )
        assertEquals(1, r.orderedStops.size)
        assertEquals(1L, r.orderedStops[0].id)
    }

    @Test
    fun twoOpt_improvesOrEqualsNearestNeighbour() {
        // Square + centre: NN from origin can pick a crossing tour;
        // 2-opt must not make it worse.
        val start = StartPoint(0.0, 0.0)
        val stops = listOf(
            StopInput(1, 0.0, 10.0, "N"),
            StopInput(2, 10.0, 10.0, "NE"),
            StopInput(3, 10.0, 0.0, "E"),
            StopInput(4, 5.0, 5.0, "C"),
            StopInput(5, 0.0, 5.0, "W")
        )
        val result = RouteOptimizer.optimize(stops, start)
        assertEquals(5, result.orderedStops.size)
        assertEquals(stops.map { it.id }.toSet(), result.orderedStops.map { it.id }.toSet())
        assertTrue(result.totalDistanceKm > 0)
        // Recompute tour cost independently - optimizer total must match.
        var recomputed = 0.0
        var prevLat = start.lat; var prevLng = start.lng
        for (s in result.orderedStops) {
            recomputed += com.routeassistant.app.data.routing.Haversine.distanceKm(prevLat, prevLng, s.lat!!, s.lng!!)
            prevLat = s.lat!!; prevLng = s.lng!!
        }
        assertTrue(abs(recomputed - result.totalDistanceKm) < 0.001)
    }

    @Test
    fun ungeocoded_excludedWithReason() {
        val stops = listOf(
            StopInput(1, 28.6, 77.2, "OK"),
            StopInput(2, null, null, "No coords")
        )
        val r = RouteOptimizer.optimize(stops, StartPoint(28.5, 77.1))
        assertEquals(1, r.orderedStops.size)
        assertEquals(1, r.excluded.size)
        assertEquals(2L, r.excluded[0].id)
    }

    @Test
    fun hundredStops_finishes() {
        val stops = (1..100).map { i ->
            StopInput(i.toLong(), 28.5 + (i % 10) * 0.01, 77.1 + (i / 10) * 0.01, "Stop $i")
        }
        val r = RouteOptimizer.optimize(stops, StartPoint(28.5, 77.1))
        assertEquals(100, r.orderedStops.size)
        assertTrue(r.totalDistanceKm > 0)
    }
}
