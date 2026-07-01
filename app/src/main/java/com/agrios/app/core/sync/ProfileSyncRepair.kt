package com.agrios.app.core.sync

import android.content.Context
import android.util.Log
import com.agrios.app.core.database.AppDatabase
import com.agrios.app.core.util.LanguageManager
import com.agrios.app.core.util.VillageIdUtil
import com.agrios.app.data.local.entity.SyncPriority
import com.agrios.app.data.local.entity.SyncQueueEntity
import com.agrios.app.data.local.entity.SyncStatus
import com.google.gson.Gson
import java.util.UUID

/**
 * One-time repair for MVP test devices that synced profile events before the
 * backend materialized FARMER/PARCEL rows from /sync/events.
 *
 * The backend now upserts by Android entity_id, so resending these events with
 * fresh event IDs is safe and restores missing server-side farmer/parcel rows.
 */
object ProfileSyncRepair {
    private const val TAG = "ProfileSyncRepair"
    private const val PREFS_NAME = "profile_sync_repair"
    private const val KEY_REQUEUED_V1 = "requeued_profile_materialization_v4"

    suspend fun enqueueOneTimeMaterializationRepair(context: Context, db: AppDatabase): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_REQUEUED_V1, false)) {
            Log.d(TAG, "Skipping profile materialization repair: already completed for key=$KEY_REQUEUED_V1")
            return 0
        }

        val farmers = db.farmerDao().getAll()
        val parcels = db.parcelDao().getAll()
        Log.d(TAG, "Profile materialization repair check: farmers=${farmers.size}, parcels=${parcels.size}, key=$KEY_REQUEUED_V1")
        if (farmers.isEmpty() && parcels.isEmpty()) {
            Log.d(TAG, "Skipping profile materialization repair: no local farmer/parcel rows found")
            return 0
        }

        val gson = Gson()
        val now = System.currentTimeMillis()
        var count = 0

        farmers.forEach { farmer ->
            Log.d(TAG, "Queueing FARMER materialization event: id=${farmer.id}, mobile=${farmer.mobileNumber}")
            db.syncQueueDao().enqueue(
                SyncQueueEntity(
                    eventId = UUID.randomUUID().toString(),
                    entityType = "FARMER",
                    entityId = farmer.id,
                    operation = "CREATE",
                    payload = gson.toJson(
                        mapOf(
                            "mobile_number" to farmer.mobileNumber,
                            "village_id" to VillageIdUtil.getSyncVillageId(farmer.villageId),
                            "village_name_manual" to VillageIdUtil.getSyncVillageNameManual(farmer.villageId, farmer.villageName),
                            "display_name" to farmer.displayName,
                            "father_name" to farmer.fatherName,
                            "age" to farmer.age,
                            "gender" to farmer.gender,
                            "aadhaar_number" to farmer.aadhaarNumber,
                            "language_preference" to LanguageManager.getLanguage(),
                            "assistance_mode" to farmer.assistanceMode,
                            "gps_lat" to farmer.gpsLat,
                            "gps_lng" to farmer.gpsLng
                        )
                    ),
                    syncStatus = SyncStatus.PENDING.name,
                    priority = SyncPriority.HIGH.name,
                    createdAt = now
                )
            )
            count++
        }

        parcels.forEach { parcel ->
            Log.d(TAG, "Queueing PARCEL materialization event: id=${parcel.id}, farmerId=${parcel.farmerId}, ownership=${parcel.ownershipType}")
            db.syncQueueDao().enqueue(
                SyncQueueEntity(
                    eventId = UUID.randomUUID().toString(),
                    entityType = "PARCEL",
                    entityId = parcel.id,
                    operation = "CREATE",
                    payload = gson.toJson(
                        mapOf(
                            "farmer_id" to parcel.farmerId,
                            "village_id" to VillageIdUtil.getSyncVillageId(parcel.villageId),
                            "village_name_manual" to VillageIdUtil.getSyncVillageNameManual(parcel.villageId, parcel.villageName),
                            "reported_area" to parcel.reportedArea,
                            "reported_area_unit" to parcel.reportedAreaUnit,
                            "area_hectares" to parcel.areaHectares,
                            "ownership_type" to parcel.ownershipType,
                            "geometry_source" to parcel.geometrySource,
                            "gps_lat" to parcel.gpsLat,
                            "gps_lng" to parcel.gpsLng,
                            "irrigation_type" to parcel.irrigationType,
                            "irrigation_source" to parcel.irrigationSource,
                            "survey_number" to parcel.surveyNumber,
                            "annual_rent" to parcel.annualRent,
                            "share_percentage" to parcel.sharePercentage,
                            "sharecrop_percentage" to parcel.sharecropPercentage
                        )
                    ),
                    syncStatus = SyncStatus.PENDING.name,
                    priority = SyncPriority.HIGH.name,
                    dependencyIds = parcel.farmerId,
                    createdAt = now
                )
            )
            count++
        }

        prefs.edit().putBoolean(KEY_REQUEUED_V1, true).apply()
        Log.d(TAG, "Requeued $count local farmer/parcel profile events for backend materialization repair")
        return count
    }
}
