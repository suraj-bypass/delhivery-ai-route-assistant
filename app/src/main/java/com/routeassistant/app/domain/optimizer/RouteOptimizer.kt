package com.routeassistant.app.domain.optimizer

import com.routeassistant.app.data.routing.Haversine
import com.routeassistant.app.data.routing.MatrixCell
import kotlin.math.min

/**
 * Pure-Kotlin route optimiser. NO Android dependencies - unit-testable.
 *
 * Algorithm:
 *  1. Build a cost matrix. If road distances are supplied, use them.
 *     Otherwise use haversine (straight-line) distances.
 *  2. Nearest-Neighbour initial tour (greedy, from start point).
 *  3. 2-opt improvement: repeatedly reverse segments while it reduces
 *     total tour cost. Capped so 100 stops still finish quickly.
 *
 * Tour is OPEN (does not return to start) - correct for deliveries.
 * Stops without coordinates are EXCLUDED and returned in [excluded]
 * with a reason, never silently dropped from the UI.
 */
object RouteOptimizer {

    data class StopInput(
        val id: Long,
        val lat: Double?,
        val lng: Double?,
        val label: String
    )

    data class StartPoint(val lat: Double, val lng: Double)

    data class OptimizedRoute(
        val orderedStops: List<StopInput>,
        val totalDistanceKm: Double,
        val totalDurationMin: Double,
        val usedRoadDistances: Boolean,
        val excluded: List<ExcludedStop>
    )

    data class ExcludedStop(val id: Long, val label: String, val reason: String)

    /**
     * @param roadMatrix Optional road matrix. If provided it must cover
     *   [start] + all geocoded stops in the same order:
     *   index 0 = start, 1..n = geocoded stops in [stops] order.
     *   If null / mismatched, haversine is used.
     * @param maxTwoOptIterations Safety cap (default 500). Also bounded by
     *   a wall-clock cap in [optimize] via iteration counting - see below.
     */
    fun optimize(
        stops: List<StopInput>,
        start: StartPoint?,
        roadMatrix: List<List<MatrixCell>>? = null,
        maxTwoOptIterations: Int = 500
    ): OptimizedRoute {
        if (stops.isEmpty()) {
            return OptimizedRoute(emptyList(), 0.0, 0.0, false, emptyList())
        }
        val excluded = mutableListOf<ExcludedStop>()
        val geocoded = stops.filter {
            if (it.lat == null || it.lng == null) {
                excluded.add(ExcludedStop(it.id, it.label, "Not geocoded - tap Retry on the parcel."))
                false
            } else true
        }
        if (geocoded.isEmpty()) {
            return OptimizedRoute(emptyList(), 0.0, 0.0, false, excluded)
        }
        if (geocoded.size == 1) {
            val only = geocoded.first()
            val d = if (start != null) Haversine.distanceKm(start.lat, start.lng, only.lat!!, only.lng!!) else 0.0
            return OptimizedRoute(geocoded, d, Haversine.estimateDurationMin(d), false, excluded)
        }

        // --- Cost function -------------------------------------------------
        val useRoad = roadMatrix != null &&
            roadMatrix.size == geocoded.size + 1 &&
            roadMatrix.all { it.size == geocoded.size + 1 }
        // Index mapping for road matrix: 0 = start, 1..n = geocoded[i-1].
        // If there is no start, road matrix index 0 is treated as geocoded[0]'s
        // origin row - callers that pass a road matrix MUST pass a start.
        // If start is null we fall back to haversine to avoid mis-indexing.
        val effectiveUseRoad = useRoad && start != null

        fun cost(fromIdx: Int, toIdx: Int): Double {
            // fromIdx/toIdx are indices into geocoded, or -1 for start.
            // No start point (GPS denied / not fetched yet): the leg from/to
            // "start" is free, so the tour starts at the first stop. Without
            // this guard, start!! below would throw NPE for multi-stop routes.
            if (start == null && (fromIdx == -1 || toIdx == -1)) return 0.0
            if (effectiveUseRoad) {
                val r = if (fromIdx == -1) 0 else fromIdx + 1
                val c = if (toIdx == -1) 0 else toIdx + 1
                return roadMatrix!![r][c].distanceKm
            }
            val aLat: Double; val aLng: Double
            val bLat: Double; val bLng: Double
            if (fromIdx == -1) {
                aLat = start!!.lat; aLng = start.lng
            } else {
                aLat = geocoded[fromIdx].lat!!; aLng = geocoded[fromIdx].lng!!
            }
            if (toIdx == -1) {
                bLat = start!!.lat; bLng = start.lng
            } else {
                bLat = geocoded[toIdx].lat!!; bLng = geocoded[toIdx].lng!!
            }
            return Haversine.distanceKm(aLat, aLng, bLat, bLng)
        }

        fun duration(fromIdx: Int, toIdx: Int): Double {
            if (effectiveUseRoad) {
                val r = if (fromIdx == -1) 0 else fromIdx + 1
                val c = if (toIdx == -1) 0 else toIdx + 1
                return roadMatrix!![r][c].durationMin
            }
            return Haversine.estimateDurationMin(cost(fromIdx, toIdx))
        }

        // --- 1. Nearest Neighbour -----------------------------------------
        val unvisited = geocoded.indices.toMutableSet()
        val tour = mutableListOf<Int>()
        var current = -1 // start
        while (unvisited.isNotEmpty()) {
            var best: Int? = null
            var bestCost = Double.MAX_VALUE
            for (cand in unvisited) {
                val c = cost(current, cand)
                if (c < bestCost) { bestCost = c; best = cand }
            }
            val chosen = best ?: break
            tour.add(chosen)
            unvisited.remove(chosen)
            current = chosen
        }

        // --- 2. 2-opt improvement (open tour) ------------------------------
        // For an open tour, reversing segment [i..k] changes cost by:
        //   - cost(prev(i), i) - cost(k, next(k)) + cost(prev(i), k) + cost(i, next(k))
        // where prev(-1) is start and next(end) does not exist.
        var improved = true
        var iterations = 0
        while (improved && iterations < maxTwoOptIterations) {
            improved = false
            iterations++
            outer@ for (i in 0 until tour.size - 1) {
                for (k in i + 1 until tour.size) {
                    val a = if (i == 0) -1 else tour[i - 1]
                    val b = tour[i]
                    val c = tour[k]
                    val d = if (k == tour.size - 1) null else tour[k + 1]
                    val before = cost(a, b) + (if (d == null) 0.0 else cost(c, d))
                    val after = cost(a, c) + (if (d == null) 0.0 else cost(b, d))
                    if (after + 1e-9 < before) {
                        tour.subList(i, k + 1).reverse()
                        improved = true
                        break@outer
                    }
                }
            }
        }

        // --- Totals ---------------------------------------------------------
        var totalDist = 0.0
        var totalDur = 0.0
        var prev = -1
        for (idx in tour) {
            totalDist += cost(prev, idx)
            totalDur += duration(prev, idx)
            prev = idx
        }

        return OptimizedRoute(
            orderedStops = tour.map { geocoded[it] },
            totalDistanceKm = totalDist,
            totalDurationMin = totalDur,
            usedRoadDistances = effectiveUseRoad,
            excluded = excluded
        )
    }
}
