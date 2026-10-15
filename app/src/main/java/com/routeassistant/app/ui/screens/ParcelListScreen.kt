package com.routeassistant.app.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.routeassistant.app.data.entity.DeliveryStatus
import com.routeassistant.app.data.entity.GeocodeStatus
import com.routeassistant.app.data.entity.ParcelEntity
import com.routeassistant.app.domain.parser.OcrTextParser
import com.routeassistant.app.ocr.OcrHelper
import com.routeassistant.app.ui.viewmodel.ParcelViewModel
import com.routeassistant.app.util.AppResult
import kotlinx.coroutines.launch

@Composable
fun ParcelListScreen(
    viewModel: ParcelViewModel,
    onOpenOcrConfirm: () -> Unit,
    onOpenRoute: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var showAdd by remember { mutableStateOf(false) }
    var showBulk by remember { mutableStateOf(false) }
    var addText by remember { mutableStateOf("") }
    var bulkText by remember { mutableStateOf("") }
    var editTarget by remember { mutableStateOf<ParcelEntity?>(null) }
    var editText by remember { mutableStateOf("") }
    var isOcrRunning by remember { mutableStateOf(false) }

    LaunchedEffect(state.error, state.info) {
        val msg = state.error ?: state.info
        if (msg != null) {
            snackbar.showSnackbar(msg)
            viewModel.clearMessage()
        }
    }

    // Screenshot picker (user-initiated). GetContent does not need storage permission.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        isOcrRunning = true
        scope.launch {
            try {
                val stream = context.contentResolver.openInputStream(uri)
                val bitmap = stream?.use { BitmapFactory.decodeStream(it) }
                if (bitmap == null) {
                    viewModel.showError("Could not read this image. Try another screenshot.")
                    isOcrRunning = false
                    return@launch
                }
                when (val r = OcrHelper.recognise(bitmap)) {
                    is AppResult.Ok -> {
                        val candidates = OcrTextParser.parseCandidates(r.value)
                        if (candidates.isEmpty()) {
                            viewModel.showError("No usable addresses found in this image.")
                        } else {
                            viewModel.setOcrCandidates(candidates)
                            onOpenOcrConfirm()
                        }
                    }
                    is AppResult.Err -> viewModel.showError(r.message)
                }
            } catch (e: Exception) {
                viewModel.showError("OCR failed: ${e.message}")
            } finally {
                isOcrRunning = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Route Assistant", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Unofficial prototype — not affiliated with Delhivery. Data stays on-device.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { showAdd = true }) { Text("Add") }
            OutlinedButton(onClick = { showBulk = true }) { Text("Bulk Paste") }
            OutlinedButton(onClick = { picker.launch("image/*") }, enabled = !isOcrRunning) {
                Text(if (isOcrRunning) "OCR…" else "OCR Screenshot")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { viewModel.geocodeAll() }, enabled = !state.isGeocoding) {
                Text(if (state.isGeocoding) "Geocoding…" else "Geocode All")
            }
            Button(
                onClick = {
                    viewModel.fetchStartLocation()
                    viewModel.optimizeRoute()
                    onOpenRoute()
                },
                enabled = !state.isOptimizing && state.parcels.isNotEmpty()
            ) {
                Text(if (state.isOptimizing) "Optimising…" else "Optimize Route")
            }
            OutlinedButton(onClick = onOpenRoute) { Text("Route") }
        }
        if (state.isGeocoding || state.isOptimizing || isOcrRunning) {
            Row(Modifier.padding(vertical = 8.dp)) {
                CircularProgressIndicator()
                Text("  Working… (Nominatim/OSRM are rate-limited, please wait)", modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (state.parcels.isEmpty()) {
            Text("No parcels yet. Add one, paste many, or OCR a screenshot.", modifier = Modifier.padding(vertical = 16.dp))
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(state.parcels, key = { it.id }) { parcel ->
                ParcelRow(
                    parcel = parcel,
                    onEdit = { editTarget = parcel; editText = parcel.rawAddress },
                    onDelete = { viewModel.deleteParcel(parcel.id) },
                    onRetryGeocode = { viewModel.retryGeocode(parcel) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { viewModel.clearCompleted() }) { Text("Clear Delivered/Skipped") }
            TextButton(onClick = { viewModel.deleteAll() }) { Text("Delete All") }
        }
        SnackbarHost(hostState = snackbar)
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add Parcel") },
            text = {
                OutlinedTextField(value = addText, onValueChange = { addText = it }, label = { Text("Full address incl. pincode") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.addParcel(addText); addText = ""; showAdd = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } }
        )
    }
    if (showBulk) {
        AlertDialog(
            onDismissRequest = { showBulk = false },
            title = { Text("Bulk Paste") },
            text = {
                Column {
                    Text("One address per line. Duplicates will be flagged, not silently dropped.")
                    OutlinedTextField(value = bulkText, onValueChange = { bulkText = it }, label = { Text("Paste addresses") }, minLines = 6, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.bulkImport(bulkText); bulkText = ""; showBulk = false }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { showBulk = false }) { Text("Cancel") } }
        )
    }
    editTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("Edit Parcel") },
            text = {
                OutlinedTextField(value = editText, onValueChange = { editText = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                Button(onClick = { viewModel.updateParcel(target, editText); editTarget = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
fun ParcelRow(
    parcel: ParcelEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRetryGeocode: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(parcel.rawAddress, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            val badges = mutableListOf<String>()
            badges.add("Geo: ${parcel.geocodeStatus}")
            badges.add("Delivery: ${parcel.status}")
            if (parcel.isDuplicate) badges.add("DUPLICATE of #${parcel.duplicateOfId}")
            if (parcel.lat != null) badges.add("(${parcel.lat}, ${parcel.lng})")
            Text(badges.joinToString(" • "), style = MaterialTheme.typography.bodySmall)
            if (parcel.geocodeError != null) {
                Text("Error: ${parcel.geocodeError}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete") }
                if (parcel.geocodeStatus == GeocodeStatus.FAILED.name || parcel.geocodeStatus == GeocodeStatus.PENDING.name) {
                    TextButton(onClick = onRetryGeocode) { Text("Retry Geocode") }
                }
                if (parcel.status == DeliveryStatus.PENDING.name) {
                    Text("• Pending", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
