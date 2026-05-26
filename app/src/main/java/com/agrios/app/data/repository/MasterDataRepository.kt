package com.agrios.app.data.repository

import android.util.Log
import com.agrios.app.data.local.dao.GeographyCacheDao
import com.agrios.app.data.local.entity.*
import com.agrios.app.data.remote.api.AgriOsApi
import com.google.gson.Gson

/**
 * Downloads and caches master data from backend using individual endpoints.
 * Progressive: states → districts → blocks → villages (per state).
 * This avoids the massive /sync payload and works on slow connections.
 */
class MasterDataRepository(
    private val api: AgriOsApi,
    private val cacheDao: GeographyCacheDao
) {
    companion object {
        private const val TAG = "MasterDataRepo"
    }

    suspend fun ensureCacheReady(): Boolean {
        val stateCount = cacheDao.getStateCount()
        if (stateCount > 0) {
            Log.d(TAG, "Cache has $stateCount states. Ready.")
            return true
        }
        return downloadAll()
    }

    /**
     * Download geography data using individual REST endpoints.
     * Flow: states → districts (per state) → blocks (per district) → villages (per block)
     */
    suspend fun downloadAll(): Boolean {
        Log.d(TAG, "Starting progressive master data download...")
        return try {
            // Step 1: Download states
            val statesResponse = api.getStates()
            if (!statesResponse.isSuccessful) {
                Log.e(TAG, "Failed to get states: ${statesResponse.code()}")
                return false
            }
            val states = statesResponse.body() ?: emptyList()
            val stateEntities = states.map {
                GeographyStateEntity(
                    id = it.id,
                    lgdCode = it.lgdCode,
                    canonicalName = it.canonicalName,
                    aliases = Gson().toJson(it.aliases ?: emptyList<String>())
                )
            }
            cacheDao.insertStates(stateEntities)
            Log.d(TAG, "✅ Cached ${stateEntities.size} states")

            // Step 2: Download districts for each state
            var totalDistricts = 0
            for (state in states) {
                val distResponse = api.getDistricts(state.id)
                if (distResponse.isSuccessful) {
                    val districts = distResponse.body() ?: emptyList()
                    val distEntities = districts.map {
                        GeographyDistrictEntity(
                            id = it.id,
                            lgdCode = it.lgdCode,
                            stateId = it.stateId,
                            canonicalName = it.canonicalName,
                            aliases = Gson().toJson(it.aliases ?: emptyList<String>())
                        )
                    }
                    cacheDao.insertDistricts(distEntities)
                    totalDistricts += distEntities.size
                }
            }
            Log.d(TAG, "✅ Cached $totalDistricts districts")

            // Step 3: Download blocks for each district
            // For MVP, we'll load blocks on-demand (when user selects district)
            // This avoids downloading 350 blocks upfront
            Log.d(TAG, "Blocks will be loaded on-demand when district is selected")

            // Save metadata
            cacheDao.saveCacheMetadata(CacheMetadataEntity(
                entityType = "geography",
                serverVersion = "v1.0",
                lastSyncedAt = System.currentTimeMillis(),
                recordCount = totalDistricts
            ))

            Log.d(TAG, "✅ Master data download complete: ${stateEntities.size} states, $totalDistricts districts")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Master data download failed", e)
            false
        }
    }

    /**
     * Download blocks for a specific district (called on-demand).
     */
    suspend fun downloadBlocksForDistrict(districtId: String): Boolean {
        return try {
            val response = api.getBlocks(districtId)
            if (response.isSuccessful) {
                val blocks = response.body() ?: emptyList()
                val entities = blocks.map {
                    GeographyBlockEntity(
                        id = it.id,
                        lgdCode = it.lgdCode,
                        districtId = it.districtId,
                        canonicalName = it.canonicalName,
                        aliases = Gson().toJson(it.aliases ?: emptyList<String>())
                    )
                }
                cacheDao.insertBlocks(entities)
                Log.d(TAG, "Cached ${entities.size} blocks for district $districtId")
                true
            } else {
                Log.e(TAG, "Failed to get blocks: ${response.code()}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Block download failed", e)
            false
        }
    }

    /**
     * Download villages for a specific block (called on-demand).
     */
    suspend fun downloadVillagesForBlock(blockId: String): Boolean {
        return try {
            val response = api.getVillages(blockId)
            if (response.isSuccessful) {
                val villages = response.body() ?: emptyList()
                val entities = villages.map {
                    GeographyVillageEntity(
                        id = it.id,
                        lgdCode = it.lgdCode,
                        blockId = it.blockId,
                        districtId = it.districtId,
                        canonicalName = it.canonicalName,
                        pinCodes = Gson().toJson(it.pinCodes ?: emptyList<String>()),
                        aliases = Gson().toJson(it.aliases ?: emptyList<String>())
                    )
                }
                cacheDao.insertVillages(entities)
                Log.d(TAG, "Cached ${entities.size} villages for block $blockId")
                true
            } else {
                Log.e(TAG, "Failed to get villages: ${response.code()}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Village download failed", e)
            false
        }
    }
}
