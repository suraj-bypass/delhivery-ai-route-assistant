package com.routeassistant.app.domain.grouping

import com.routeassistant.app.data.entity.DeliveryStatus
import com.routeassistant.app.data.routing.Haversine
import com.routeassistant.app.domain.parser.AddressParser as Parser

/**
 * Pure-Kotlin grouping / area / COD helpers. NO Android dependencies - unit-testable.
 *
 * Same-address combining: parcels whose whitespace-normalised, case-insensitive
 * address matches are ONE stop. The stop is complete only when ALL its parcels
 * are DELIVERED. No parcel is dropped by grouping.
 */
object ParcelGrouping {

    data class ParcelGroup(
        /** Normalised (lowercase) address - stable key, used to match optimizer output. */
        val key: String,
        /** First parcel's raw address, for display. */
        val displayAddress: String,
        val parcels: List<com.routeassistant.app.data.entity.ParcelEntity>
    ) {
        val parcelCount: Int get() = parcels.size
        val lat: Double? get() = parcels.firstOrNull { it.lat != null }?.lat
        val lng: Double? get() = parcels.firstOrNull { it.lng != null }?.lng
        val isComplete: Boolean get() = parcels.isNotEmpty() &&
            parcels.all { it.status == DeliveryStatus.DELIVERED.name }
        val pendingCount: Int get() = parcels.count { it.status == DeliveryStatus.PENDING.name }
        val deliveredCount: Int get() = parcels.count { it.status == DeliveryStatus.DELIVERED.name }
        val urgentAny: Boolean get() = parcels.any { it.isUrgent }
        val parcelIds: List<Long> get() = parcels.map { it.id }
        val codTotals: CodTotals get() = codTotals(parcels)
    }

    data class CodTotals(val total: Double, val collected: Double, val pending: Double)

    data class AreaCount(val area: String, val count: Int)

    data class NearbyCluster(val label: String, val memberKeys: List<String>) {
        val size: Int get() = memberKeys.size
    }

    /** Group parcels by normalised address, preserving first-appearance order. */
    fun combine(
        parcels: List<com.routeassistant.app.data.entity.ParcelEntity>
    ): List<ParcelGroup> {
        val ordered = linkedMapOf<String, MutableList<com.routeassistant.app.data.entity.ParcelEntity>>()
        for (p in parcels) {
            val key = Parser.normalizeForCompare(p.rawAddress)
            ordered.getOrPut(key) { mutableListOf() }.add(p)
        }
        return ordered.entries.map { (key, list) ->
            ParcelGroup(key = key, displayAddress = list.first().rawAddress, parcels = list.toList())
        }
    }

    /**
     * Greedy nearby clustering: a stop joins the first cluster whose
     * representative (first member) is within [thresholdKm]. Stops without
     * coordinates get a singleton cluster each so they are never hidden.
     * Default threshold 500 m.
     */
    fun clusterNearby(
        groups: List<ParcelGroup>,
        thresholdKm: Double = 0.5
    ): List<NearbyCluster> {
        val clusters = mutableListOf<MutableList<ParcelGroup>>()
        for (g in groups) {
            var placed = false
            if (g.lat != null && g.lng != null) {
                for (cluster in clusters) {
                    val rep = cluster.first()
                    if (rep.lat != null && rep.lng != null &&
                        Haversine.distanceKm(g.lat!!, g.lng!!, rep.lat!!, rep.lng!!) <= thresholdKm
                    ) {
                        cluster.add(g)
                        placed = true
                        break
                    }
                }
            }
            if (!placed) clusters.add(mutableListOf(g))
        }
        return clusters.mapIndexed { index, members ->
            val label = if (members.size > 1) {
                "Nearby group ${index + 1} • ${members.size} stops within 500 m"
            } else {
                "Group ${index + 1}"
            }
            NearbyCluster(label = label, memberKeys = members.map { it.key })
        }
    }

    /**
     * Area derivation: 6-digit pincode from the raw address wins; else the
     * first usable segment of the Nominatim displayName (locality-ish);
     * else "Unknown area".
     */
    fun deriveArea(rawAddress: String, displayName: String?): String {
        val pin = Regex("""\b\d{6}\b""").find(rawAddress)?.value
        if (pin != null) return "Pincode $pin"
        if (!displayName.isNullOrBlank()) {
            val seg = displayName.split(",").map { it.trim() }.firstOrNull {
                it.isNotBlank() && !it.all { c -> c.isDigit() } &&
                    !it.equals("India", ignoreCase = true)
            }
            if (seg != null) return seg
        }
        return "Unknown area"
    }

    /** Per-area parcel counts, sorted by count desc, then area name. */
    fun areaCounts(
        parcels: List<com.routeassistant.app.data.entity.ParcelEntity>
    ): List<AreaCount> {
        return parcels.groupBy { deriveArea(it.rawAddress, it.displayName) }
            .map { (area, list) -> AreaCount(area, list.size) }
            .sortedWith(compareByDescending<AreaCount> { it.count }.thenBy { it.area })
    }

    fun codTotals(
        parcels: List<com.routeassistant.app.data.entity.ParcelEntity>
    ): CodTotals {
        var total = 0.0
        var collected = 0.0
        for (p in parcels) {
            val amount = p.codAmount ?: 0.0
            total += amount
            if (p.cashCollected) collected += amount
        }
        return CodTotals(total = total, collected = collected, pending = total - collected)
    }

    /**
     * Pure reorder helper (tested): swap [id] with its neighbour.
     * Returns the original list unchanged when the move is impossible.
     */
    fun reorderIds(ids: List<Long>, id: Long, moveUp: Boolean): List<Long> {
        val idx = ids.indexOf(id)
        if (idx < 0) return ids
        val target = if (moveUp) idx - 1 else idx + 1
        if (target < 0 || target >= ids.size) return ids
        val copy = ids.toMutableList()
        val tmp = copy[idx]
        copy[idx] = copy[target]
        copy[target] = tmp
        return copy
    }
}
