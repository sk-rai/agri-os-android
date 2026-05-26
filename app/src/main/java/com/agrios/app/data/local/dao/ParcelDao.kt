package com.agrios.app.data.local.dao

import androidx.room.*
import com.agrios.app.data.local.entity.ParcelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ParcelDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(parcel: ParcelEntity)

    @Query("SELECT * FROM parcels_local WHERE farmer_id = :farmerId ORDER BY created_at DESC")
    fun observeByFarmer(farmerId: String): Flow<List<ParcelEntity>>

    @Query("SELECT * FROM parcels_local ORDER BY created_at DESC")
    fun observeAll(): Flow<List<ParcelEntity>>

    @Query("SELECT * FROM parcels_local WHERE id = :id")
    suspend fun getById(id: String): ParcelEntity?

    @Query("UPDATE parcels_local SET sync_status = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: String)

    @Query("SELECT COUNT(*) FROM parcels_local")
    suspend fun getCount(): Int
}
