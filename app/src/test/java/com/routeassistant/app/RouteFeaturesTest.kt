package com.routeassistant.app

import com.routeassistant.app.data.entity.DeliveryStatus
import com.routeassistant.app.data.entity.GeocodeStatus
import com.routeassistant.app.data.entity.ParcelEntity
import com.routeassistant.app.domain.grouping.ParcelGrouping
import com.routeassistant.app.domain.maps.MapsLinks
import com.routeassistant.app.domain.optimizer.RouteOptimizer
import com.routeassistant.app.domain.optimizer.RouteOptimizer.StartPoint
import com.routeassistant.app.domain.optimizer.RouteOptimizer.StopInput
import com.routeassistant.app.domain.parser.AddressParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin tests for the route-planner features. No Android, no network.
 * Run with: ./gradlew :app:testDebugUnitTest
 */
class RouteFeaturesTest {

    private fun parcel(
        id: Long,
        address: String,
        status: String = DeliveryStatus.PENDING.name,
        lat: Double? = null,
        lng: Double? = null,
        isUrgent: Boolean = false,
        codAmount: Double? = null,
        cashCollected: Boolean = false,
        displayName: String? = null
    ) = ParcelEntity(
        id = id,
        rawAddress = address,
        normalizedAddress = AddressParser.normalize(address),
        lat = lat,
        lng = lng,
        displayName = displayName,
        geocodeStatus = if (lat != null) GeocodeStatus.OK.name else GeocodeStatus.PENDING.name,
        status = status,
        isUrgent = isUrgent,
        codAmount = codAmount,
        cashCollected = cashCollected
    )

    // ---- Same-address combining ------------------------------------------
    @Test
    fun combine_groupsSameAddress_caseInsensitive() {
        val parcels = listOf(
            parcel(1, "Flat 12, Sector 62, Noida 201309", lat = 28.6, lng = 77.3),
            parcel(2, "FLAT 12, Sector 62, Noida 201309", lat = 28.6, lng = 77.3),
            parcel(3, "India Gate, New Delhi", lat = 28.61, lng = 77.23)
        )
        val groups = ParcelGrouping.combine(parcels)
        assertEquals(2, groups.size)
        assertEquals(2, groups[0].parcelCount)
        assertEquals(listOf(1L, 2L), groups[0].parcelIds)
        assertEquals(1, groups[1].parcelCount)
    }

    @Test
    fun combine_stopCompleteOnlyWhenAllParcelsDelivered() {
        val parcels = listOf(
            parcel(1, "Same Address", status = DeliveryStatus.DELIVERED.name),
            parcel(2, "Same Address", status = DeliveryStatus.PENDING.name)
        )
        val group = ParcelGrouping.combine(parcels).single()
        assertFalse(group.isComplete)
        assertEquals(1, group.pendingCount)
        assertEquals(1, group.deliveredCount)
        val done = ParcelGrouping.combine(
            parcels.map { it.copy(status = DeliveryStatus.DELIVERED.name) }
        ).single()
        assertTrue(done.isComplete)
    }

    // ---- Nearby clustering -------------------------------------------------
    @Test
    fun clusterNearby_groupsWithin500m() {
        // ~110 m apart (0.001 deg lat) vs ~11 km apart (0.1 deg).
        val parcels = listOf(
            parcel(1, "A", lat = 28.600, lng = 77.200),
            parcel(2, "B", lat = 28.601, lng = 77.200),
            parcel(3, "C", lat = 28.700, lng = 77.200)
        )
        val groups = ParcelGrouping.combine(parcels)
        val clusters = ParcelGrouping.clusterNearby(groups)
        assertEquals(2, clusters.size)
        assertEquals(2, clusters[0].size)
        assertEquals(1, clusters[1].size)
    }

    @Test
    fun clusterNearby_ungeocodedGetSingletonClusters() {
        val parcels = listOf(parcel(1, "No coords"), parcel(2, "Also no coords"))
        val clusters = ParcelGrouping.clusterNearby(ParcelGrouping.combine(parcels))
        assertEquals(2, clusters.size)
    }

    // ---- Area derivation ---------------------------------------------------
    @Test
    fun deriveArea_pincodeWins() {
        assertEquals(
            "Pincode 201309",
            ParcelGrouping.deriveArea("Flat 12, Sector 62, Noida 201309", "Somewhere, Elsewhere")
        )
    }

    @Test
    fun deriveArea_displayNameLocalityFallback() {
        assertEquals(
            "Connaught Place",
            ParcelGrouping.deriveArea(
                "Connaught Place, New Delhi",
                "Connaught Place, New Delhi, Delhi, India"
            )
        )
    }

    @Test
    fun deriveArea_unknownWhenNothing() {
        assertEquals("Unknown area", ParcelGrouping.deriveArea("Somewhere", null))
    }

    @Test
    fun areaCounts_countsPerArea() {
        val parcels = listOf(
            parcel(1, "A, Noida 201309"),
            parcel(2, "B, Noida 201309"),
            parcel(3, "C, Delhi 110001")
        )
        val counts = ParcelGrouping.areaCounts(parcels)
        assertEquals(2, counts.size)
        assertEquals("Pincode 201309", counts[0].area)
        assertEquals(2, counts[0].count)
        assertEquals("Pincode 110001", counts[1].area)
        assertEquals(1, counts[1].count)
    }

    // ---- Maps URL chunking -------------------------------------------------
    @Test
    fun mapsLinks_singleLinkForFourStopsPlusOrigin() {
        // 5 points total (origin + 4 stops) = exactly one window.
        val stops = (1..4).map { MapsLinks.LatLng(28.0 + it * 0.01, 77.0) }
        val links = MapsLinks.buildFullRouteLinks(stops, MapsLinks.LatLng(28.0, 77.0))
        assertEquals(1, links.size)
        assertTrue(links[0].url.contains("origin=28.0,77.0"))
        assertTrue(links[0].url.contains("travelmode=driving"))
        assertEquals(3, links[0].url.split("waypoints=")[1].split("&")[0].split("|").size)
    }

    @Test
    fun mapsLinks_chunking_respectsThreeWaypointLimit() {
        val stops = (1..12).map { MapsLinks.LatLng(28.0 + it * 0.01, 77.0) }
        val links = MapsLinks.buildFullRouteLinks(stops, MapsLinks.LatLng(28.0, 77.0))
        // 13 points (origin + 12 stops), windows of 5 stepping by 4 -> 3 links.
        assertEquals(3, links.size)
        for (link in links) {
            val waypointPart = link.url.split("waypoints=").getOrNull(1)?.split("&")?.get(0)
            val waypointCount = if (waypointPart.isNullOrEmpty()) 0 else waypointPart.split("|").size
            assertTrue("link ${link.title} has $waypointCount waypoints", waypointCount <= MapsLinks.MAX_WAYPOINTS)
        }
        // Every stop covered exactly once as a destination/origin across parts:
        // last part ends at stop 12, first part starts at stop 1.
        assertEquals(1, links.first().firstStopNumber)
        assertEquals(12, links.last().lastStopNumber)
    }

    @Test
    fun mapsLinks_noOrigin_firstStopIsOrigin() {
        val stops = (1..6).map { MapsLinks.LatLng(28.0 + it * 0.01, 77.0) }
        val links = MapsLinks.buildFullRouteLinks(stops, null)
        assertEquals(2, links.size)
        assertTrue(links[0].url.startsWith("https://www.google.com/maps/dir/?api=1&origin=28.01,77.0"))
    }

    @Test
    fun mapsLinks_emptyForNoStops() {
        assertTrue(MapsLinks.buildFullRouteLinks(emptyList(), null).isEmpty())
    }

    // ---- Urgent-first ordering ----------------------------------------------
    @Test
    fun optimizer_urgentStopComesFirstEvenWhenFar() {
        val start = StartPoint(28.5, 77.1)
        val stops = listOf(
            StopInput(1, 28.51, 77.11, "Near, not urgent"),
            StopInput(2, 28.52, 77.12, "Near 2, not urgent"),
            StopInput(3, 29.50, 78.10, "Far but URGENT", isUrgent = true)
        )
        val result = RouteOptimizer.optimize(stops, start)
        assertEquals(3, result.orderedStops.size)
        assertEquals(3L, result.orderedStops[0].id)
        assertTrue(result.orderedStops.subList(1, 3).map { it.id }.containsAll(listOf(1L, 2L)))
    }

    @Test
    fun optimizer_noUrgent_behavesAsBefore() {
        val start = StartPoint(28.5, 77.1)
        val stops = listOf(
            StopInput(1, 28.51, 77.11, "A"),
            StopInput(2, 28.52, 77.12, "B")
        )
        val result = RouteOptimizer.optimize(stops, start)
        assertEquals(2, result.orderedStops.size)
    }

    @Test
    fun optimizer_multipleUrgent_nearestAmongThemselvesFirst() {
        val start = StartPoint(0.0, 0.0)
        val stops = listOf(
            StopInput(1, 10.0, 0.0, "Far urgent", isUrgent = true),
            StopInput(2, 1.0, 0.0, "Near urgent", isUrgent = true),
            StopInput(3, 0.5, 0.0, "Nearest but NOT urgent")
        )
        val result = RouteOptimizer.optimize(stops, start)
        // Both urgent stops precede the non-urgent one; nearer urgent first.
        assertEquals(2L, result.orderedStops[0].id)
        assertEquals(1L, result.orderedStops[1].id)
        assertEquals(3L, result.orderedStops[2].id)
    }

    // ---- COD totals ----------------------------------------------------------
    @Test
    fun codTotals_sumsTotalCollectedPending() {
        val parcels = listOf(
            parcel(1, "A", codAmount = 500.0, cashCollected = true),
            parcel(2, "B", codAmount = 300.0, cashCollected = false),
            parcel(3, "C", codAmount = null)
        )
        val totals = ParcelGrouping.codTotals(parcels)
        assertEquals(800.0, totals.total, 0.001)
        assertEquals(500.0, totals.collected, 0.001)
        assertEquals(300.0, totals.pending, 0.001)
    }

    // ---- Reorder -------------------------------------------------------------
    @Test
    fun reorderIds_swapsWithNeighbour() {
        val ids = listOf(10L, 20L, 30L)
        assertEquals(listOf(20L, 10L, 30L), ParcelGrouping.reorderIds(ids, 10L, moveUp = false))
        assertEquals(listOf(10L, 30L, 20L), ParcelGrouping.reorderIds(ids, 20L, moveUp = false))
        assertEquals(listOf(10L, 20L, 30L), ParcelGrouping.reorderIds(ids, 10L, moveUp = true))
        assertEquals(listOf(10L, 20L, 30L), ParcelGrouping.reorderIds(ids, 30L, moveUp = false))
        assertEquals(listOf(10L, 20L, 30L), ParcelGrouping.reorderIds(ids, 99L, moveUp = true))
    }

    // ---- Multi-line Add split -------------------------------------------------
    @Test
    fun parseBulk_splitsMultiLineAddInput() {
        val result = AddressParser.parseBulk(
            "Connaught Place, New Delhi\nIndia Gate, New Delhi\nKarol Bagh, New Delhi"
        )
        assertEquals(3, result.addresses.size)
        assertEquals("Connaught Place, New Delhi", result.addresses[0])
    }

    @Test
    fun parseBulk_singleLineStaysSingle() {
        val result = AddressParser.parseBulk("Flat 12, Sector 62, Noida 201309")
        assertEquals(1, result.addresses.size)
    }
}
