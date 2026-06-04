package com.agrios.app.data.local.dao

import androidx.room.*
import com.agrios.app.data.local.entity.SoilProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SoilProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: SoilProfileEntity)

    @Query("SELECT * FROM soil_profiles_local WHERE parcel_id = :parcelId ORDER BY created_at DESC")
    fun observeByParcel(parcelId: String): Flow<List<SoilProfileEntity>>

    @Query("SELECT * FROM soil_profiles_local WHERE farmer_id = :farmerId ORDER BY created_at DESC")
    fun observeByFarmer(farmerId: String): Flow<List<SoilProfileEntity>>

    @Query("SELECT * FROM soil_profiles_local WHERE id = :id")
    suspend fun getById(id: String): SoilProfileEntity?

    @Query("SELECT COUNT(*) FROM soil_profiles_local WHERE parcel_id = :parcelId")
    suspend fun getCountForParcel(parcelId: String): Int
}
