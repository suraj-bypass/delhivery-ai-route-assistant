package com.routeassistant.app.domain.maps

/**
 * Google Maps URLs (https://developers.google.com/maps/documentation/urls) —
 * Directions action, no API key.
 *
 * Verified limit (official docs): "The number of waypoints allowed varies by
 * the platform where the link opens, with up to three waypoints supported on
 * mobile browsers, and a maximum of nine waypoints supported otherwise."
 * This app is phone-first, so the safe ceiling is 3 intermediate waypoints
 * per link: origin + up to 3 waypoints + destination = at most 5 stops per
 * link. Longer routes split into sequential, overlapping parts: each part's
 * destination is the next part's origin, so no stop is lost or reordered.
 * Next-stop navigation (google.navigation intent) stays as the fallback.
 */
object MapsLinks {

    /** Max intermediate waypoints per Maps URL (mobile-browser ceiling). */
    const val MAX_WAYPOINTS = 3

    /** Max stops per link: origin + waypoints + destination. */
    const val MAX_STOPS_PER_LINK = MAX_WAYPOINTS + 2 // 5

    data class LatLng(val lat: Double, val lng: Double)

    data class MapsLink(
        /** e.g. "Part 1: stops 1–5" */
        val title: String,
        val url: String,
        val firstStopNumber: Int,
        val lastStopNumber: Int
    )

    /**
     * Build sequential Maps direction links for [stops] in order.
     * @param origin GPS start; null = first stop acts as the origin.
     * A single stop with no origin becomes a search link (Maps picks origin).
     */
    fun buildFullRouteLinks(stops: List<LatLng>, origin: LatLng?): List<MapsLink> {
        if (stops.isEmpty()) return emptyList()
        if (stops.size == 1 && origin == null) {
            val only = stops.first()
            return listOf(
                MapsLink(
                    title = "Stop 1",
                    url = "https://www.google.com/maps/search/?api=1&query=${fmt(only)}",
                    firstStopNumber = 1,
                    lastStopNumber = 1
                )
            )
        }
        // Points sequence: [GPS origin?] + stops. Point index -> stop number.
        val points = mutableListOf<LatLng>()
        val pointStopNumber = mutableListOf<Int?>()
        if (origin != null) {
            points.add(origin)
            pointStopNumber.add(null) // GPS origin is not a stop
        }
        stops.forEachIndexed { index, stop ->
            points.add(stop)
            pointStopNumber.add(index + 1)
        }

        val links = mutableListOf<MapsLink>()
        var start = 0
        var part = 1
        while (start < points.size - 1) {
            val end = minOf(start + MAX_STOPS_PER_LINK - 1, points.size - 1)
            val window = points.subList(start, end + 1)
            val firstStop = pointStopNumber.subList(start, end + 1).firstOrNull { it != null }
                ?: (pointStopNumber[end] ?: 1)
            val lastStop = pointStopNumber[end] ?: firstStop
            val originParam = window.first()
            val destParam = window.last()
            val waypointParams = if (window.size > 2) window.subList(1, window.size - 1) else emptyList()
            val url = buildString {
                append("https://www.google.com/maps/dir/?api=1")
                append("&origin=").append(fmt(originParam))
                append("&destination=").append(fmt(destParam))
                if (waypointParams.isNotEmpty()) {
                    append("&waypoints=").append(waypointParams.joinToString("|") { fmt(it) })
                }
                append("&travelmode=driving")
            }
            links.add(
                MapsLink(
                    title = if (firstStop == lastStop) "Part $part: stop $firstStop"
                    else "Part $part: stops $firstStop–$lastStop",
                    url = url,
                    firstStopNumber = firstStop,
                    lastStopNumber = lastStop
                )
            )
            if (end == points.size - 1) break
            start = end // overlap: destination becomes next part's origin
            part++
        }
        return links
    }

    private fun fmt(p: LatLng): String = "${p.lat},${p.lng}"
}
