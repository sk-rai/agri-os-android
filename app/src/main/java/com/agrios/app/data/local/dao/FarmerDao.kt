package com.agrios.app.data.local.dao

import androidx.room.*
import com.agrios.app.data.local.entity.FarmerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FarmerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(farmer: FarmerEntity)

    @Query("SELECT * FROM farmers_local ORDER BY created_at DESC")
    fun observeAll(): Flow<List<FarmerEntity>>

    @Query("SELECT * FROM farmers_local WHERE id = :id")
    suspend fun getById(id: String): FarmerEntity?

    @Query("SELECT * FROM farmers_local WHERE mobile_number = :mobile")
    suspend fun getByMobile(mobile: String): FarmerEntity?

    @Query("UPDATE farmers_local SET sync_status = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: String)

    @Query("SELECT COUNT(*) FROM farmers_local")
    suspend fun getCount(): Int
}
