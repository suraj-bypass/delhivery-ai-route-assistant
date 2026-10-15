package com.routeassistant.app.data.repository

import com.routeassistant.app.data.dao.ParcelDao
import com.routeassistant.app.data.entity.DeliveryStatus
import com.routeassistant.app.data.entity.GeocodeStatus
import com.routeassistant.app.data.entity.ParcelEntity
import com.routeassistant.app.domain.parser.AddressParser
import kotlinx.coroutines.flow.Flow

/**
 * Single place that owns parcel persistence + dedupe flagging.
 * No network / geocoding here - that lives in the ViewModel + providers
 * so it can be swapped and tested independently.
 */
class ParcelRepository(private val dao: ParcelDao) {

    fun observeAll(): Flow<List<ParcelEntity>> = dao.observeAll()

    suspend fun getAll(): List<ParcelEntity> = dao.getAll()

    suspend fun add(rawAddress: String): ParcelEntity {
        val normalized = AddressParser.normalize(rawAddress)
        val existing = dao.getAll()
        // Case-insensitive compare (same as parseBulk) - otherwise "FLAT 12"
        // vs "Flat 12" would slip through as a non-duplicate.
        val duplicateOf = existing.firstOrNull {
            AddressParser.normalizeForCompare(it.rawAddress) == AddressParser.normalizeForCompare(rawAddress)
        }?.id
        val entity = ParcelEntity(
            rawAddress = rawAddress.trim(),
            normalizedAddress = normalized,
            duplicateOfId = duplicateOf
        )
        val id = dao.insert(entity)
        return entity.copy(id = id)
    }

    /** Bulk import: returns inserted rows + duplicates that were flagged. */
    suspend fun addBulk(rawAddresses: List<String>): BulkAddResult {
        val inserted = mutableListOf<ParcelEntity>()
        val duplicates = mutableListOf<ParcelEntity>()
        for (raw in rawAddresses) {
            val row = add(raw)
            if (row.isDuplicate) duplicates.add(row) else inserted.add(row)
        }
        return BulkAddResult(inserted = inserted, duplicates = duplicates)
    }

    suspend fun update(parcel: ParcelEntity) = dao.update(parcel)

    suspend fun updateAddress(id: Long, newRaw: String) {
        val existing = dao.getById(id) ?: return
        val normalized = AddressParser.normalize(newRaw)
        val duplicateOf = dao.getAll()
            .firstOrNull { it.id != id && AddressParser.normalizeForCompare(it.rawAddress) == AddressParser.normalizeForCompare(newRaw) }?.id
        dao.update(
            existing.copy(
                rawAddress = newRaw.trim(),
                normalizedAddress = normalized,
                // Address changed -> geocode must run again.
                lat = null,
                lng = null,
                geocodeStatus = GeocodeStatus.PENDING.name,
                geocodeError = null,
                duplicateOfId = duplicateOf
            )
        )
    }

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun deleteAll() = dao.deleteAll()

    suspend fun deleteCompleted() = dao.deleteCompleted()

    suspend fun setDeliveryStatus(id: Long, status: DeliveryStatus) {
        dao.updateStatus(id, status.name)
    }

    suspend fun setUrgent(id: Long, urgent: Boolean) {
        dao.getById(id)?.let { dao.update(it.copy(isUrgent = urgent)) }
    }

    suspend fun setCodAmount(id: Long, amount: Double?) {
        dao.getById(id)?.let { dao.update(it.copy(codAmount = amount)) }
    }

    suspend fun setCashCollected(id: Long, collected: Boolean) {
        dao.getById(id)?.let { dao.update(it.copy(cashCollected = collected)) }
    }

    suspend fun setGeocodeResult(
        id: Long,
        lat: Double?,
        lng: Double?,
        status: GeocodeStatus,
        error: String?,
        displayName: String? = null
    ) {
        val existing = dao.getById(id) ?: return
        dao.update(
            existing.copy(
                lat = lat,
                lng = lng,
                geocodeStatus = status.name,
                geocodeError = error,
                // Keep the old displayName when the caller has none (retry w/o name).
                displayName = displayName ?: existing.displayName
            )
        )
    }

    suspend fun saveOrder(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id ->
            dao.updateOrder(id, index)
        }
    }
}

data class BulkAddResult(
    val inserted: List<ParcelEntity>,
    val duplicates: List<ParcelEntity>
)
