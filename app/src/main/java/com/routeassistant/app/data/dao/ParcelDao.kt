package com.routeassistant.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.routeassistant.app.data.entity.ParcelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ParcelDao {
    @Query("SELECT * FROM parcels ORDER BY orderIndex ASC, createdAt ASC")
    fun observeAll(): Flow<List<ParcelEntity>>

    @Query("SELECT * FROM parcels ORDER BY orderIndex ASC, createdAt ASC")
    suspend fun getAll(): List<ParcelEntity>

    @Query("SELECT * FROM parcels WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ParcelEntity?

    @Insert
    suspend fun insert(parcel: ParcelEntity): Long

    @Insert
    suspend fun insertAll(parcels: List<ParcelEntity>): List<Long>

    @Update
    suspend fun update(parcel: ParcelEntity)

    @Query("DELETE FROM parcels WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM parcels WHERE status IN ('DELIVERED','SKIPPED')")
    suspend fun deleteCompleted()

    @Query("DELETE FROM parcels")
    suspend fun deleteAll()

    @Query("UPDATE parcels SET orderIndex = :orderIndex WHERE id = :id")
    suspend fun updateOrder(id: Long, orderIndex: Int)

    @Query("UPDATE parcels SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)
}
