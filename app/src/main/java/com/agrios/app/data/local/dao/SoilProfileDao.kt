package com.agrios.app.data.local.dao

import androidx.room.*
import com.agrios.app.data.local.entity.SoilProfileEntity
import com.agrios.app.data.local.entity.requireUnchangedLocalOrigin
import kotlinx.coroutines.flow.Flow

@Dao
interface SoilProfileDao {
    @Query("SELECT * FROM soil_profiles_local WHERE farmer_id=:farmerId AND origin_tenant_id=:tenant AND origin_user_id=:user AND length(trim(origin_tenant_id))>0 AND length(trim(origin_user_id))>0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id=1 AND a.is_authenticated=1 AND a.reauth_required=0 AND a.tenant_id=:tenant AND a.user_id=:user) ORDER BY created_at DESC")
    fun observeOwnedByFarmer(farmerId: String, tenant: String, user: String): Flow<List<SoilProfileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRetained(profile: SoilProfileEntity)

    @Transaction
    suspend fun insert(profile: SoilProfileEntity) {
        requireUnchangedLocalOrigin(getById(profile.id), profile)
        insertRetained(profile)
    }

    @Query("SELECT * FROM soil_profiles_local WHERE parcel_id = :parcelId ORDER BY created_at DESC")
    fun observeByParcel(parcelId: String): Flow<List<SoilProfileEntity>>

    @Query("SELECT * FROM soil_profiles_local WHERE farmer_id = :farmerId ORDER BY created_at DESC")
    fun observeByFarmer(farmerId: String): Flow<List<SoilProfileEntity>>

    @Query("SELECT * FROM soil_profiles_local WHERE id = :id")
    suspend fun getById(id: String): SoilProfileEntity?

    @Query("SELECT COUNT(*) FROM soil_profiles_local WHERE parcel_id = :parcelId")
    suspend fun getCountForParcel(parcelId: String): Int
}
