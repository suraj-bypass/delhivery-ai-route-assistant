package com.routeassistant.app.ui.screens

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.routeassistant.app.ui.viewmodel.ParcelViewModel

/**
 * OCR confirmation: NOTHING is saved until the user checks candidates
 * and taps Confirm. Every candidate is editable, removable, uncheckable.
 */
@Composable
fun OcrConfirmScreen(
    viewModel: ParcelViewModel,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var checked by remember(state.ocrCandidates) {
        mutableStateOf(state.ocrCandidates.indices.toSet())
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Confirm OCR Addresses", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Only checked addresses will be saved. Edit any line before confirming.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))
        if (state.ocrCandidates.isEmpty()) {
            Text("No candidates. Go back and try a clearer screenshot.")
            OutlinedButton(onClick = onCancel) { Text("Back") }
            return
        }
        Row {
            TextButton(onClick = { checked = state.ocrCandidates.indices.toSet() }) { Text("Select All") }
            TextButton(onClick = { checked = emptySet() }) { Text("Deselect All") }
        }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(state.ocrCandidates) { index, candidate ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Checkbox(
                        checked = index in checked,
                        onCheckedChange = { isChecked ->
                            checked = if (isChecked) checked + index else checked - index
                        }
                    )
                    OutlinedTextField(
                        value = candidate,
                        onValueChange = { viewModel.updateOcrCandidate(index, it) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Address ${index + 1}") }
                    )
                    TextButton(onClick = {
                        viewModel.removeOcrCandidate(index)
                        checked = checked - index
                    }) { Text("X") }
                }
            }
        }
        Row {
            Button(onClick = {
                viewModel.confirmOcrCandidates(checked)
                onDone()
            }) { Text("Confirm & Save (${checked.size})") }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
