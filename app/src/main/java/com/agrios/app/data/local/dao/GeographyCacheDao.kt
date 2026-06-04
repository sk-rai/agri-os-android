package com.agrios.app.data.local.dao

import androidx.room.*
import com.agrios.app.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GeographyCacheDao {

    // --- States ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStates(states: List<GeographyStateEntity>)

    @Query("SELECT * FROM geography_states WHERE is_active = 1 ORDER BY canonical_name")
    fun getAllStates(): Flow<List<GeographyStateEntity>>

    // --- Districts ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDistricts(districts: List<GeographyDistrictEntity>)

    @Query("SELECT * FROM geography_districts WHERE state_id = :stateId AND is_active = 1 ORDER BY canonical_name")
    fun getDistrictsByState(stateId: String): Flow<List<GeographyDistrictEntity>>

    // --- Blocks ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlocks(blocks: List<GeographyBlockEntity>)

    @Query("SELECT * FROM geography_blocks WHERE district_id = :districtId AND is_active = 1 ORDER BY canonical_name")
    fun getBlocksByDistrict(districtId: String): Flow<List<GeographyBlockEntity>>

    // --- Villages ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVillages(villages: List<GeographyVillageEntity>)

    @Query("SELECT * FROM geography_villages WHERE block_id = :blockId AND is_active = 1 ORDER BY canonical_name")
    fun getVillagesByBlock(blockId: String): Flow<List<GeographyVillageEntity>>

    @Query("SELECT * FROM geography_villages WHERE district_id = :districtId AND is_active = 1 ORDER BY canonical_name")
    fun getVillagesByDistrict(districtId: String): Flow<List<GeographyVillageEntity>>

    @Query("SELECT COUNT(*) FROM geography_villages WHERE district_id = :districtId")
    suspend fun getVillageCountForDistrict(districtId: String): Int

    @Query("SELECT * FROM geography_villages WHERE canonical_name LIKE '%' || :query || '%' AND is_active = 1 ORDER BY canonical_name LIMIT :limit")
    suspend fun searchVillages(query: String, limit: Int = 20): List<GeographyVillageEntity>

    // --- Cache Metadata ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCacheMetadata(metadata: CacheMetadataEntity)

    @Query("SELECT * FROM cache_metadata WHERE entity_type = :entityType")
    suspend fun getCacheMetadata(entityType: String): CacheMetadataEntity?

    @Query("SELECT COUNT(*) FROM geography_villages")
    suspend fun getVillageCount(): Int

    @Query("SELECT COUNT(*) FROM geography_states")
    suspend fun getStateCount(): Int

    @Query("SELECT COUNT(*) FROM geography_districts WHERE state_id = :stateId")
    suspend fun getDistrictCountForState(stateId: String): Int

    @Query("SELECT COUNT(*) FROM geography_blocks WHERE district_id = :districtId")
    suspend fun getBlockCountForDistrict(districtId: String): Int

    @Query("SELECT COUNT(*) FROM geography_villages WHERE block_id = :blockId")
    suspend fun getVillageCountForBlock(blockId: String): Int
}
