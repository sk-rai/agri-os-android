package com.agrios.app.data.local.dao

import androidx.room.*
import com.agrios.app.data.local.entity.FarmerEntity
import com.agrios.app.data.local.entity.requireUnchangedLocalOrigin
import kotlinx.coroutines.flow.Flow

data class ActiveOwnerFarmer(
    @ColumnInfo(name = "active_owner") val activeOwner: Int,
    @Embedded val farmer: FarmerEntity?,
)

@Dao
interface FarmerDao {
    @Query("SELECT * FROM farmers_local WHERE id=:id AND origin_tenant_id=:tenant AND origin_user_id=:user AND length(trim(origin_tenant_id))>0 AND length(trim(origin_user_id))>0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id=1 AND a.is_authenticated=1 AND a.reauth_required=0 AND a.tenant_id=:tenant AND a.user_id=:user)")
    fun observeOwnedById(id: String, tenant: String, user: String): Flow<FarmerEntity?>

    // No row means inactive owner; a row with NULL farmer columns means active and empty.
    @Query("""
        SELECT 1 AS active_owner, f.* FROM auth_state a
        LEFT JOIN farmers_local f ON f.origin_tenant_id=:tenant AND f.origin_user_id=:user
            AND length(trim(f.origin_tenant_id))>0 AND length(trim(f.origin_user_id))>0
        WHERE a.id=1 AND a.is_authenticated=1 AND a.reauth_required=0
            AND a.tenant_id=:tenant AND a.user_id=:user
            AND length(trim(a.tenant_id))>0 AND length(trim(a.user_id))>0
            AND length(trim(a.jwt))>0
        ORDER BY f.created_at DESC, f.id ASC LIMIT 1
    """)
    fun observeFirstOwned(tenant: String, user: String): Flow<ActiveOwnerFarmer?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRetained(farmer: FarmerEntity)

    @Transaction
    suspend fun insert(farmer: FarmerEntity) {
        requireUnchangedLocalOrigin(getById(farmer.id), farmer)
        insertRetained(farmer)
    }

    @Query("SELECT * FROM farmers_local ORDER BY created_at DESC")
    fun observeAll(): Flow<List<FarmerEntity>>

    @Query("SELECT * FROM farmers_local ORDER BY created_at DESC")
    suspend fun getAll(): List<FarmerEntity>

    @Query("SELECT * FROM farmers_local ORDER BY created_at DESC LIMIT 1")
    suspend fun getFirst(): FarmerEntity?

    @Query("SELECT * FROM farmers_local WHERE id = :id")
    suspend fun getById(id: String): FarmerEntity?

    @Query("SELECT * FROM farmers_local WHERE mobile_number = :mobile")
    suspend fun getByMobile(mobile: String): FarmerEntity?

    @Query("UPDATE farmers_local SET sync_status = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: String)

    @Update
    suspend fun updateRetained(farmer: FarmerEntity)

    @Transaction
    suspend fun update(farmer: FarmerEntity) {
        requireUnchangedLocalOrigin(getById(farmer.id), farmer)
        updateRetained(farmer)
    }

    @Query("UPDATE farmers_local SET display_name = :name, aadhaar_number = :aadhaar, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateProfile(id: String, name: String?, aadhaar: String?, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM farmers_local")
    suspend fun getCount(): Int
}
