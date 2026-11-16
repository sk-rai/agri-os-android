package com.agrios.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.agrios.app.data.local.entity.SelfProfileCacheFarmerEntity
import com.agrios.app.data.local.entity.SelfProfileCacheParcelEntity
import com.agrios.app.data.local.entity.SelfProfileCacheSoilEntity

private const val ACTIVE_CACHE_OWNER = """
    AND EXISTS (SELECT 1 FROM auth_state
      WHERE id = 1 AND is_authenticated = 1 AND reauth_required = 0
      AND length(trim(jwt)) > 0 AND tenant_id = :tenant AND user_id = :user)
"""

@Dao
internal interface SelfProfileCacheDao {
    @Query("SELECT * FROM self_profile_cache_farmer WHERE tenant_id = :tenant AND user_id = :user AND farmer_id = :farmer" + ACTIVE_CACHE_OWNER)
    suspend fun farmer(tenant: String, user: String, farmer: String): SelfProfileCacheFarmerEntity?

    @Query("SELECT * FROM self_profile_cache_parcel WHERE tenant_id = :tenant AND user_id = :user AND farmer_id = :farmer" +
        ACTIVE_CACHE_OWNER + " ORDER BY parcel_id")
    suspend fun parcels(tenant: String, user: String, farmer: String): List<SelfProfileCacheParcelEntity>

    @Query("SELECT * FROM self_profile_cache_soil WHERE tenant_id = :tenant AND user_id = :user AND farmer_id = :farmer" +
        ACTIVE_CACHE_OWNER + " ORDER BY soil_profile_id")
    suspend fun soil(tenant: String, user: String, farmer: String): List<SelfProfileCacheSoilEntity>

    // Collision checks span farmer/parcel parents within the same active owner.
    @Query("SELECT * FROM self_profile_cache_parcel WHERE tenant_id = :tenant AND user_id = :user AND parcel_id = :id" + ACTIVE_CACHE_OWNER)
    suspend fun parcelForCommit(tenant: String, user: String, id: String): SelfProfileCacheParcelEntity?

    @Query("SELECT * FROM self_profile_cache_soil WHERE tenant_id = :tenant AND user_id = :user AND soil_profile_id = :id" + ACTIVE_CACHE_OWNER)
    suspend fun soilForCommit(tenant: String, user: String, id: String): SelfProfileCacheSoilEntity?

    @Insert suspend fun insertFarmer(row: SelfProfileCacheFarmerEntity)
    @Insert suspend fun insertParcel(row: SelfProfileCacheParcelEntity)
    @Insert suspend fun insertSoil(row: SelfProfileCacheSoilEntity)
}
