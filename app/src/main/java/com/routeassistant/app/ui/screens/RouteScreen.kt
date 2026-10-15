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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.routeassistant.app.data.entity.DeliveryStatus
import com.routeassistant.app.data.entity.ParcelEntity
import com.routeassistant.app.ui.viewmodel.ParcelViewModel

/**
 * Ordered route + progress + navigation.
 * Navigation: Google Maps intent -> geo: URI -> browser maps URL.
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
    // Next pending stop = first ordered stop that is still PENDING.
    val orderedParcels: List<ParcelEntity> = run {
        val byId = state.parcels.associateBy { it.id }
        val ordered = route?.orderedStops?.mapNotNull { byId[it.id] } ?: emptyList()
        // Include any PENDING parcels not in the ordered list (e.g. ungeocoded)
        // at the end so they are visible, not hidden.
        val orderedIds = ordered.map { it.id }.toSet()
        val rest = state.parcels.filter { it.status == DeliveryStatus.PENDING.name && it.id !in orderedIds }
        ordered + rest
    }
    val nextStop = orderedParcels.firstOrNull { it.status == DeliveryStatus.PENDING.name }

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
        // 3. Browser fallback - OSM / Google Maps web
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lng"))
            )
        } catch (e: Exception) {
            viewModel.showError("Could not open maps: ${e.message}")
        }
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
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { nextStop?.let { openNavigation(it) } },
                enabled = nextStop != null && nextStop.lat != null
            ) { Text("Navigate Next") }
            OutlinedButton(onClick = { viewModel.recalculate() }, enabled = !state.isOptimizing) {
                Text(if (state.isOptimizing) "Recalculating…" else "Recalculate")
            }
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
        Spacer(Modifier.height(12.dp))
        if (orderedParcels.isEmpty()) {
            Text("No pending stops.")
        }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(orderedParcels) { index, parcel ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${index + 1}. ${parcel.rawAddress}", style = MaterialTheme.typography.bodyMedium)
                        Text("Status: ${parcel.status} • Geo: ${parcel.geocodeStatus}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.markDelivered(parcel.id) },
                                enabled = parcel.status == DeliveryStatus.PENDING.name
                            ) { Text("Delivered") }
                            OutlinedButton(
                                onClick = { viewModel.markSkipped(parcel.id) },
                                enabled = parcel.status == DeliveryStatus.PENDING.name
                            ) { Text("Skip") }
                            OutlinedButton(
                                onClick = { openNavigation(parcel) },
                                enabled = parcel.lat != null
                            ) { Text("Navigate") }
                        }
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbar)
    }
}
