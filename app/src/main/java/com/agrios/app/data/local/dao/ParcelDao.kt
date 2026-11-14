package com.agrios.app.data.local.dao

import androidx.room.*
import com.agrios.app.data.local.entity.ParcelEntity
import com.agrios.app.data.local.entity.requireUnchangedLocalOrigin
import kotlinx.coroutines.flow.Flow

@Dao
interface ParcelDao {
    @Query("SELECT * FROM parcels_local WHERE farmer_id=:farmerId AND origin_tenant_id=:tenant AND origin_user_id=:user AND length(trim(origin_tenant_id))>0 AND length(trim(origin_user_id))>0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id=1 AND a.is_authenticated=1 AND a.reauth_required=0 AND a.tenant_id=:tenant AND a.user_id=:user) ORDER BY created_at DESC")
    fun observeOwnedByFarmer(farmerId: String, tenant: String, user: String): Flow<List<ParcelEntity>>

    @Query("UPDATE parcels_local SET survey_number=:surveyNumber, updated_at=:updatedAt WHERE id=:id AND origin_tenant_id=:tenant AND origin_user_id=:user AND length(trim(origin_tenant_id))>0 AND length(trim(origin_user_id))>0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id=1 AND a.is_authenticated=1 AND a.reauth_required=0 AND a.tenant_id=:tenant AND a.user_id=:user)")
    suspend fun updateOwnedSurveyNumber(id: String, tenant: String, user: String, surveyNumber: String?, updatedAt: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRetained(parcel: ParcelEntity)

    @Transaction
    suspend fun insert(parcel: ParcelEntity) {
        requireUnchangedLocalOrigin(getById(parcel.id), parcel)
        insertRetained(parcel)
    }

    @Query("SELECT * FROM parcels_local WHERE farmer_id = :farmerId ORDER BY created_at DESC")
    fun observeByFarmer(farmerId: String): Flow<List<ParcelEntity>>

    @Query("SELECT * FROM parcels_local ORDER BY created_at DESC")
    fun observeAll(): Flow<List<ParcelEntity>>

    @Query("SELECT * FROM parcels_local ORDER BY created_at DESC")
    suspend fun getAll(): List<ParcelEntity>

    @Query("SELECT * FROM parcels_local WHERE id = :id")
    suspend fun getById(id: String): ParcelEntity?

    @Query("UPDATE parcels_local SET sync_status = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: String)

    @Update
    suspend fun updateRetained(parcel: ParcelEntity)

    @Transaction
    suspend fun update(parcel: ParcelEntity) {
        requireUnchangedLocalOrigin(getById(parcel.id), parcel)
        updateRetained(parcel)
    }

    @Query("UPDATE parcels_local SET survey_number = :surveyNumber, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateSurveyNumber(id: String, surveyNumber: String?, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM parcels_local")
    suspend fun getCount(): Int
}
