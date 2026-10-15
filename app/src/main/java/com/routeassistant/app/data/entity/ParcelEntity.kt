package com.routeassistant.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One parcel / delivery stop.
 * - rawAddress: exactly what the user pasted / OCR produced.
 * - normalizedAddress: whitespace-normalised version used for dedupe + geocoding.
 * - geocodeStatus: PENDING / OK / FAILED. FAILED stops are excluded from
 *   optimisation but kept in the list with a retry affordance.
 * - status: PENDING / DELIVERED / SKIPPED / FAILED (delivery outcome).
 * - duplicateOfId: if this row is a duplicate, points at the canonical row.
 *   Duplicates are FLAGGED, never silently dropped.
 */
@Entity(tableName = "parcels")
data class ParcelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val rawAddress: String,
    val normalizedAddress: String,
    val lat: Double? = null,
    val lng: Double? = null,
    val displayName: String? = null,
    val geocodeStatus: String = GeocodeStatus.PENDING.name,
    val geocodeError: String? = null,
    val status: String = DeliveryStatus.PENDING.name,
    val orderIndex: Int = Int.MAX_VALUE,
    val duplicateOfId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isDuplicate: Boolean get() = duplicateOfId != null
}

enum class GeocodeStatus { PENDING, OK, FAILED }
enum class DeliveryStatus { PENDING, DELIVERED, SKIPPED, FAILED }
