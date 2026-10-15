package com.routeassistant.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.routeassistant.app.data.db.AppDatabase
import com.routeassistant.app.data.entity.DeliveryStatus
import com.routeassistant.app.data.entity.GeocodeStatus
import com.routeassistant.app.data.entity.ParcelEntity
import com.routeassistant.app.data.geocoding.GeocoderProvider
import com.routeassistant.app.data.geocoding.NominatimGeocoder
import com.routeassistant.app.data.repository.ParcelRepository
import com.routeassistant.app.data.routing.OsrmRoutingProvider
import com.routeassistant.app.data.routing.RoutePoint
import com.routeassistant.app.data.routing.RoutingProvider
import com.routeassistant.app.domain.optimizer.RouteOptimizer
import com.routeassistant.app.domain.parser.AddressParser
import com.routeassistant.app.util.AppResult
import com.routeassistant.app.util.LocationHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val parcels: List<ParcelEntity> = emptyList(),
    val isGeocoding: Boolean = false,
    val isOptimizing: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val optimizedRoute: RouteOptimizer.OptimizedRoute? = null,
    val startLat: Double? = null,
    val startLng: Double? = null,
    val startLabel: String = "Current location (GPS)",
    val gpsAvailable: Boolean? = null, // null = unknown, false = denied/unavailable
    val ocrCandidates: List<String> = emptyList(),
    val isOcrLoading: Boolean = false
)

class ParcelViewModel(
    application: Application,
    private val geocoder: GeocoderProvider = NominatimGeocoder(),
    private val routing: RoutingProvider = OsrmRoutingProvider()
) : AndroidViewModel(application) {

    private val repository = ParcelRepository(AppDatabase.get(application).parcelDao())

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var geocodeJob: Job? = null
    private var autoJob: Job? = null
    private var autoFlowRequested = false
    private var launchRestoreAttempted = false

    init {
        viewModelScope.launch {
            repository.observeAll().collect { list ->
                val hadNoRoute = _uiState.value.optimizedRoute == null
                _uiState.update { it.copy(parcels = list) }
                // Auto-optimise on launch / restart: Room already restored parcels
                // + coordinates. Run once, only if a pending geocoded stop exists
                // and there is no route yet. No location fetch here (no permission
                // prompt on launch); optimise runs with start = null if needed.
                if (!launchRestoreAttempted) {
                    launchRestoreAttempted = true
                    val hasCoordsPending = list.any {
                        it.status == DeliveryStatus.PENDING.name && it.lat != null && it.lng != null
                    }
                    if (hadNoRoute && hasCoordsPending) {
                        viewModelScope.launch { optimizeInternal(list) }
                    }
                }
            }
        }
    }

    fun clearMessage() = _uiState.update { it.copy(error = null, info = null) }
    fun showError(msg: String) = _uiState.update { it.copy(error = msg) }
    fun showInfo(msg: String) = _uiState.update { it.copy(info = msg) }

    // ---- CRUD -----------------------------------------------------------
    fun addParcel(raw: String) {
        if (raw.isBlank()) { showError("Address is empty."); return }
        viewModelScope.launch {
            val row = repository.add(raw)
            if (row.isDuplicate) showInfo("Duplicate flagged - saved but marked as duplicate.")
            else showInfo("Parcel added.")
            requestAutoFlow()
        }
    }

    fun updateParcel(parcel: ParcelEntity, newRaw: String) {
        viewModelScope.launch { repository.updateAddress(parcel.id, newRaw) }
    }

    fun deleteParcel(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun deleteAll() {
        viewModelScope.launch {
            repository.deleteAll()
            _uiState.update { it.copy(optimizedRoute = null) }
        }
    }

    fun bulkImport(text: String) {
        val existing = _uiState.value.parcels
            .map { AddressParser.normalizeForCompare(it.rawAddress) }.toSet()
        val parsed = AddressParser.parseBulk(text, existing)
        if (parsed.addresses.isEmpty()) {
            showError("No new addresses found. Duplicates: ${parsed.duplicates.size}.")
            return
        }
        viewModelScope.launch {
            val result = repository.addBulk(parsed.addresses)
            val dupNote = if (parsed.duplicates.isNotEmpty()) " Duplicates flagged: ${parsed.duplicates.size}." else ""
            showInfo("Added ${result.inserted.size} parcels.$dupNote")
            requestAutoFlow()
        }
    }

    // ---- OCR ------------------------------------------------------------
    fun setOcrCandidates(candidates: List<String>) {
        _uiState.update { it.copy(ocrCandidates = candidates) }
    }

    fun updateOcrCandidate(index: Int, value: String) {
        _uiState.update {
            val copy = it.ocrCandidates.toMutableList()
            if (index in copy.indices) copy[index] = value
            it.copy(ocrCandidates = copy)
        }
    }

    fun removeOcrCandidate(index: Int) {
        _uiState.update {
            val copy = it.ocrCandidates.toMutableList()
            if (index in copy.indices) copy.removeAt(index)
            it.copy(ocrCandidates = copy)
        }
    }

    /** Save ONLY the checked candidates. Nothing is saved without confirmation. */
    fun confirmOcrCandidates(checkedIndices: Set<Int>) {
        val candidates = _uiState.value.ocrCandidates
        val selected = checkedIndices.sorted().mapNotNull { candidates.getOrNull(it)?.trim() }.filter { it.isNotBlank() }
        if (selected.isEmpty()) { showError("No candidates selected."); return }
        viewModelScope.launch {
            val result = repository.addBulk(selected)
            _uiState.update { it.copy(ocrCandidates = emptyList()) }
            showInfo("Saved ${result.inserted.size} parcels from OCR.")
            requestAutoFlow()
        }
    }

    fun clearOcrCandidates() = _uiState.update { it.copy(ocrCandidates = emptyList()) }

    // ---- Geocoding ------------------------------------------------------
    private data class GeocodeOutcome(val attempted: Int, val ok: Int, val failed: Int, val lastError: String?)

    /** Geocode all PENDING/FAILED parcels. Returns outcome; caller shows messages. */
    private suspend fun geocodePendingInternal(): GeocodeOutcome {
        val pending = repository.getAll().filter {
            it.geocodeStatus == GeocodeStatus.PENDING.name || it.geocodeStatus == GeocodeStatus.FAILED.name
        }
        if (pending.isEmpty()) return GeocodeOutcome(0, 0, 0, null)
        _uiState.update { it.copy(isGeocoding = true) }
        var ok = 0; var failed = 0; var lastError: String? = null
        try {
            for (parcel in pending) {
                when (val r = geocoder.geocode(parcel.normalizedAddress)) {
                    is AppResult.Ok -> {
                        repository.setGeocodeResult(parcel.id, r.value.lat, r.value.lng, GeocodeStatus.OK, null)
                        ok++
                    }
                    is AppResult.Err -> {
                        repository.setGeocodeResult(parcel.id, null, null, GeocodeStatus.FAILED, r.message)
                        failed++; lastError = r.message
                    }
                }
            }
        } finally {
            _uiState.update { it.copy(isGeocoding = false) }
        }
        return GeocodeOutcome(pending.size, ok, failed, lastError)
    }

    fun geocodeAll() {
        if (geocodeJob?.isActive == true) return
        geocodeJob = viewModelScope.launch {
            val outcome = geocodePendingInternal()
            if (outcome.attempted == 0) showInfo("Nothing to geocode.")
            else if (outcome.failed > 0) showError("Geocoded ${outcome.ok}, failed ${outcome.failed}. Last error: ${outcome.lastError}")
            else showInfo("Geocoded ${outcome.ok} parcels.")
        }
    }

    fun retryGeocode(parcel: ParcelEntity) {
        viewModelScope.launch {
            when (val r = geocoder.geocode(parcel.normalizedAddress)) {
                is AppResult.Ok -> {
                    repository.setGeocodeResult(parcel.id, r.value.lat, r.value.lng, GeocodeStatus.OK, null)
                    showInfo("Geocoded: ${parcel.rawAddress.take(40)}")
                }
                is AppResult.Err -> {
                    repository.setGeocodeResult(parcel.id, null, null, GeocodeStatus.FAILED, r.message)
                    showError(r.message)
                }
            }
        }
    }

    // ---- Location -------------------------------------------------------
    fun fetchStartLocation() {
        viewModelScope.launch {
            when (val r = LocationHelper.lastKnownLocation(getApplication())) {
                is AppResult.Ok -> {
                    _uiState.update {
                        it.copy(startLat = r.value.lat, startLng = r.value.lng, gpsAvailable = true, startLabel = "Current location (GPS)")
                    }
                }
                is AppResult.Err -> {
                    _uiState.update { it.copy(gpsAvailable = false) }
                    showError(r.message)
                }
            }
        }
    }

    fun setManualStart(lat: Double, lng: Double, label: String) {
        _uiState.update { it.copy(startLat = lat, startLng = lng, startLabel = label, gpsAvailable = true) }
    }

    // ---- Optimisation ---------------------------------------------------
    /**
     * Optimise over [parcels] (fresh list from repository). Shows user messages.
     * Returns the optimised route, or null if there was nothing to optimise or it failed.
     */
    private suspend fun optimizeInternal(parcels: List<ParcelEntity>): RouteOptimizer.OptimizedRoute? {
        val pending = parcels.filter { it.status == DeliveryStatus.PENDING.name }
        if (pending.isEmpty()) return null
        _uiState.update { it.copy(isOptimizing = true) }
        try {
            val start = if (_uiState.value.startLat != null && _uiState.value.startLng != null) {
                RouteOptimizer.StartPoint(_uiState.value.startLat!!, _uiState.value.startLng!!)
            } else null

            val stops = pending.map {
                RouteOptimizer.StopInput(it.id, it.lat, it.lng, it.rawAddress)
            }
            // Try road matrix first (OSRM). On ANY failure, fall back to haversine.
            var roadMatrix: List<List<com.routeassistant.app.data.routing.MatrixCell>>? = null
            var usedRoad = false
            if (start != null) {
                val points = listOf(RoutePoint(start.lat, start.lng)) +
                    stops.filter { it.lat != null && it.lng != null }
                        .map { RoutePoint(it.lat!!, it.lng!!) }
                // Only request a matrix if all pending stops are geocoded; otherwise
                // the optimizer handles exclusion and the matrix indices would mismatch.
                if (points.size == stops.size + 1) {
                    when (val m = routing.distanceMatrix(points)) {
                        is AppResult.Ok -> { roadMatrix = m.value.matrix; usedRoad = m.value.usedRoadDistances }
                        is AppResult.Err -> {
                            // Non-fatal: haversine fallback. Surface once, do not block.
                            showInfo("Road distances unavailable (${m.message}). Using straight-line distances.")
                        }
                    }
                }
            }
            val result = RouteOptimizer.optimize(stops, start, roadMatrix)
            _uiState.update { it.copy(optimizedRoute = result, isOptimizing = false) }
            if (result.orderedStops.isEmpty()) {
                showError("No stops could be optimised. Geocode the parcels first.")
            } else {
                val roadNote = if (result.usedRoadDistances) " (road distances)" else " (straight-line distances - road data unavailable)"
                val exclNote = if (result.excluded.isNotEmpty()) " Excluded ${result.excluded.size} ungeocoded." else ""
                showInfo("Optimised ${result.orderedStops.size} stops$roadNote.$exclNote")
                // Persist order so it survives restart.
                repository.saveOrder(result.orderedStops.map { it.id })
            }
            return result
        } catch (e: Exception) {
            _uiState.update { it.copy(isOptimizing = false) }
            showError("Optimisation failed: ${e.message}")
            return null
        }
    }

    fun optimizeRoute() {
        val parcels = _uiState.value.parcels
        val pending = parcels.filter { it.status == DeliveryStatus.PENDING.name }
        if (pending.isEmpty()) { showError("No pending parcels to optimise."); return }
        viewModelScope.launch {
            optimizeInternal(repository.getAll())
        }
    }

    /**
     * Auto-flow after add / bulk / OCR-confirm: geocode newly added parcels,
     * then optimise over fresh data. Messages come from optimizeInternal only,
     * so one auto-flow produces at most one user-visible message. Rapid
     * successive requests coalesce into one job that loops until no new work
     * arrived mid-run. Optimize never triggers geocode in a loop by itself.
     */
    private fun requestAutoFlow() {
        if (autoJob?.isActive == true) { autoFlowRequested = true; return }
        autoJob = viewModelScope.launch {
            do {
                autoFlowRequested = false
                geocodePendingInternal()
                optimizeInternal(repository.getAll())
            } while (autoFlowRequested)
        }
    }

    fun recalculate() {
        // Recalculate = optimise again over remaining PENDING stops.
        optimizeRoute()
    }

    fun markDelivered(id: Long) {
        viewModelScope.launch { repository.setDeliveryStatus(id, DeliveryStatus.DELIVERED) }
    }

    fun markSkipped(id: Long) {
        viewModelScope.launch { repository.setDeliveryStatus(id, DeliveryStatus.SKIPPED) }
    }

    fun markFailed(id: Long) {
        viewModelScope.launch { repository.setDeliveryStatus(id, DeliveryStatus.FAILED) }
    }

    fun clearCompleted() {
        viewModelScope.launch {
            repository.deleteCompleted()
            showInfo("Cleared delivered / skipped parcels.")
        }
    }

    /** CSV export of current parcels (for backup / debugging). No upload. */
    fun exportCsv(): String {
        val header = "id,rawAddress,lat,lng,geocodeStatus,status,duplicateOfId\n"
        val rows = _uiState.value.parcels.joinToString("\n") { p ->
            listOf(
                p.id.toString(),
                "\"${p.rawAddress.replace("\"", "\"\"")}\"",
                p.lat?.toString() ?: "",
                p.lng?.toString() ?: "",
                p.geocodeStatus,
                p.status,
                p.duplicateOfId?.toString() ?: ""
            ).joinToString(",")
        }
        return header + rows
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ParcelViewModel::class.java)) {
                return ParcelViewModel(application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
