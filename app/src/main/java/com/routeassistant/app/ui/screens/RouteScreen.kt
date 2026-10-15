package com.routeassistant.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.routeassistant.app.data.entity.DeliveryStatus
import com.routeassistant.app.data.entity.GeocodeStatus
import com.routeassistant.app.data.entity.ParcelEntity
import com.routeassistant.app.domain.grouping.ParcelGrouping
import com.routeassistant.app.domain.maps.MapsLinks
import com.routeassistant.app.ui.viewmodel.ParcelViewModel

/**
 * Ordered route + progress + navigation.
 *
 * Stops are GROUPED by normalised address: same-address parcels are one stop
 * showing a parcel count (xN). A stop is complete only when ALL its parcels
 * are DELIVERED.
 *
 * Navigation: per-stop Google Maps intent -> geo: URI -> browser fallback;
 * "Open Full Route in Maps" opens the whole ordered route as sequential
 * Maps links (Google Maps mobile links allow at most 3 intermediate
 * waypoints = 5 stops per link, so longer routes split into parts).
 * Next-stop navigation stays as the fallback.
 * GPS denied: does NOT crash; shows manual-start hint and uses first stop.
 */
@Composable
fun RouteScreen(
    viewModel: ParcelViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.error, state.info) {
        val msg = state.error ?: state.info
        if (msg != null) {
            snackbar.showSnackbar(msg)
            viewModel.clearMessage()
        }
    }

    val route = state.optimizedRoute
    // Group ALL parcels by address (stable keys), then order groups by the
    // optimiser output (matched by group key), with un-ordered pending groups
    // at the end so they are visible, not hidden.
    val allGroups = remember(state.parcels) { ParcelGrouping.combine(state.parcels) }
    val groupsByKey = remember(allGroups) { allGroups.associateBy { it.key } }
    val orderedGroups = remember(route, allGroups) {
        val ordered = route?.orderedStops?.mapNotNull { groupsByKey[it.label] } ?: emptyList()
        val orderedKeys = ordered.map { it.key }.toSet()
        val rest = allGroups.filter {
            it.key !in orderedKeys && it.parcels.any { p -> p.status == DeliveryStatus.PENDING.name }
        }
        ordered + rest
    }
    val clustersByKey = remember(orderedGroups) {
        ParcelGrouping.clusterNearby(orderedGroups)
            .flatMap { cluster -> cluster.memberKeys.map { it to cluster.label } }
            .toMap()
    }
    val codTotals = remember(state.parcels) { ParcelGrouping.codTotals(state.parcels) }

    // Next suggested stop = first group with any PENDING parcel.
    val nextGroup = orderedGroups.firstOrNull { it.pendingCount > 0 }

    // Full-route Maps links (geocoded groups only, in displayed order).
    val mapsLinks = remember(orderedGroups, state.startLat, state.startLng) {
        val stops = orderedGroups.mapNotNull { g ->
            if (g.lat != null && g.lng != null) MapsLinks.LatLng(g.lat!!, g.lng!!) else null
        }
        val origin = if (state.startLat != null && state.startLng != null) {
            MapsLinks.LatLng(state.startLat!!, state.startLng!!)
        } else null
        MapsLinks.buildFullRouteLinks(stops, origin)
    }

    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            viewModel.showError("Could not open maps: ${e.message}")
        }
    }

    fun openNavigation(parcel: ParcelEntity) {
        val lat = parcel.lat
        val lng = parcel.lng
        if (lat == null || lng == null) {
            viewModel.showError("This stop has no coordinates. Geocode it first.")
            return
        }
        // 1. Google Maps navigation intent (preferred)
        val navUri = Uri.parse("google.navigation:q=$lat,$lng")
        val navIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
            setPackage("com.google.android.apps.maps")
        }
        try {
            if (navIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(navIntent)
                return
            }
        } catch (_: Exception) { /* fall through */ }
        // 2. Generic geo: URI
        try {
            val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(parcel.rawAddress)})"))
            if (geo.resolveActivity(context.packageManager) != null) {
                context.startActivity(geo)
                return
            }
        } catch (_: Exception) { /* fall through */ }
        // 3. Browser fallback - Google Maps web
        openUrl("https://www.google.com/maps/search/?api=1&query=$lat,$lng")
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Optimized Route", style = MaterialTheme.typography.headlineSmall)
        if (state.gpsAvailable == false) {
            Text(
                "Location permission denied / GPS unavailable. Route starts from the first stop. Grant location in Settings or use the Recalculate flow after moving.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (route != null) {
            Text("Total: %.1f km • %.0f min".format(route.totalDistanceKm, route.totalDurationMin), style = MaterialTheme.typography.bodyMedium)
            Text(
                if (route.usedRoadDistances) "Distances: road (OSRM)" else "Distances: straight-line (road data unavailable)",
                style = MaterialTheme.typography.bodySmall
            )
            if (route.excluded.isNotEmpty()) {
                Text(
                    "Excluded ${route.excluded.size} ungeocoded: ${route.excluded.joinToString { it.label.take(30) }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        } else {
            Text("No optimised route yet. Go back, geocode, then tap Optimize Route.", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "${orderedGroups.size} stops • ${state.parcels.size} parcels • COD total ₹${codTotals.total} • Collected ₹${codTotals.collected} • Pending ₹${codTotals.pending}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (nextGroup != null) {
            Text(
                "Next best stop: ${nextGroup.displayAddress}" +
                    if (nextGroup.parcelCount > 1) " (×${nextGroup.parcelCount})" else "",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { nextGroup?.let { g -> g.parcels.firstOrNull { it.lat != null }?.let { openNavigation(it) } } },
                enabled = nextGroup != null && nextGroup.lat != null
            ) { Text("Navigate Next", maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = { viewModel.recalculate() }, enabled = !state.isOptimizing) {
                Text(if (state.isOptimizing) "Recalculating…" else "Recalculate", maxLines = 1, softWrap = false)
            }
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
        if (mapsLinks.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Google Maps mobile links allow up to 3 intermediate waypoints (5 stops per link). " +
                    "Longer routes split into sequential parts - open each part in order. " +
                    "Next-stop navigation above stays as the fallback.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                mapsLinks.forEach { link ->
                    OutlinedButton(onClick = { openUrl(link.url) }) {
                        Text("Open ${link.title} in Maps", maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (orderedGroups.isEmpty()) {
            Text("No pending stops.")
        }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(orderedGroups, key = { _, g -> g.key }) { index, group ->
                GroupCard(
                    index = index,
                    group = group,
                    clusterLabel = clustersByKey[group.key],
                    onDelivered = { cash -> viewModel.markGroupDelivered(group.parcelIds, cash) },
                    onSkipped = { viewModel.markGroupSkipped(group.parcelIds) },
                    onNavigate = {
                        group.parcels.firstOrNull { it.lat != null }?.let { openNavigation(it) }
                    }
                )
            }
        }
        SnackbarHost(hostState = snackbar)
    }
}

@Composable
private fun GroupCard(
    index: Int,
    group: ParcelGrouping.ParcelGroup,
    clusterLabel: String?,
    onDelivered: (cashCollected: Boolean) -> Unit,
    onSkipped: () -> Unit,
    onNavigate: () -> Unit
) {
    var cashChecked by remember(group.key) { mutableStateOf(false) }
    val hasCod = group.codTotals.total > 0
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "${index + 1}. ${group.displayAddress}" +
                    if (group.parcelCount > 1) "  (×${group.parcelCount} parcels)" else "",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Delivered ${group.deliveredCount}/${group.parcelCount} • " +
                    if (group.isComplete) "Complete" else "Pending ${group.pendingCount}" +
                    " • Geo: " + if (group.lat != null) "OK" else GeocodeStatus.PENDING.name,
                style = MaterialTheme.typography.bodySmall
            )
            if (group.urgentAny) {
                Text("URGENT", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (clusterLabel != null) {
                Text(clusterLabel, style = MaterialTheme.typography.bodySmall)
            }
            if (hasCod) {
                Text(
                    "COD ₹${group.codTotals.total} • Collected ₹${group.codTotals.collected} • Pending ₹${group.codTotals.pending}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onDelivered(cashChecked) },
                    enabled = group.pendingCount > 0
                ) { Text("Delivered") }
                OutlinedButton(
                    onClick = onSkipped,
                    enabled = group.pendingCount > 0
                ) { Text("Skip") }
                OutlinedButton(
                    onClick = onNavigate,
                    enabled = group.lat != null
                ) { Text("Navigate") }
            }
            if (hasCod && group.pendingCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = cashChecked, onCheckedChange = { cashChecked = it })
                    Text("Cash collected", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
