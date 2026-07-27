package com.agrios.app.data.repository

import android.content.Context
import com.agrios.app.core.cache.CropCycleCache
import com.agrios.app.core.database.AppDatabase
import com.agrios.app.data.local.entity.FarmerEntity
import com.agrios.app.data.local.entity.ParcelEntity
import com.agrios.app.data.local.entity.SoilProfileEntity
import com.agrios.app.data.local.entity.SyncStatus
import com.agrios.app.data.remote.api.AgriOsApi
import com.agrios.app.data.remote.dto.FarmerProfileHydrationDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ProfileHydrationResult(
    val profileExists: Boolean,
    val farmerId: String? = null,
    val message: String? = null
)

class ProfileHydrationRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val api: AgriOsApi
) {
    suspend fun hydrateAfterLogin(): ProfileHydrationResult = withContext(Dispatchers.IO) {
        val response = api.getMyFarmerProfile()
        when {
            response.isSuccessful -> hydrate(response.body())
            response.code() == 404 -> ProfileHydrationResult(profileExists = false)
            else -> ProfileHydrationResult(
                profileExists = false,
                message = "Profile hydration failed (${response.code()})"
            )
        }
    }

    suspend fun hydrateByMobile(mobile: String): ProfileHydrationResult = withContext(Dispatchers.IO) {
        val response = api.getFarmerProfileByMobile(mobile)
        when {
            response.isSuccessful -> hydrate(response.body())
            response.code() == 404 -> ProfileHydrationResult(profileExists = false)
            else -> ProfileHydrationResult(
                profileExists = false,
                message = "Profile hydration failed (${response.code()})"
            )
        }
    }

    private suspend fun hydrate(profile: FarmerProfileHydrationDto?): ProfileHydrationResult {
        val farmer = profile?.farmer
        if (profile == null || !profile.profileExists || farmer == null) {
            return ProfileHydrationResult(profileExists = false)
        }

        val now = System.currentTimeMillis()
        val actorId = db.authDao().getAuthState()?.userId ?: farmer.id
        val villageId = farmer.villageId ?: "UNKNOWN"

        db.farmerDao().insert(
            FarmerEntity(
                id = farmer.id,
                mobileNumber = farmer.mobileNumber,
                villageId = villageId,
                villageName = farmer.villageNameManual,
                primaryCropCode = farmer.primaryCropCode,
                displayName = farmer.displayName,
                fatherName = farmer.fatherName,
                age = farmer.age,
                gender = farmer.gender,
                assistanceMode = "DEALER_ASSISTED",
                syncStatus = SyncStatus.SYNCED.name,
                createdAt = now,
                updatedAt = now,
                actorId = actorId
            )
        )

        profile.parcels.forEach { parcel ->
            db.parcelDao().insert(
                ParcelEntity(
                    id = parcel.id,
                    farmerId = parcel.farmerId,
                    villageId = parcel.villageId ?: villageId,
                    villageName = parcel.villageName ?: farmer.villageNameManual,
                    reportedArea = parcel.reportedArea ?: 0.0,
                    reportedAreaUnit = parcel.reportedAreaUnit ?: "BIGHA",
                    areaHectares = parcel.areaHectares,
                    geometrySource = parcel.geometrySource ?: "NONE",
                    gpsLat = parcel.centroidLat,
                    gpsLng = parcel.centroidLng,
                    ownershipType = parcel.ownershipType ?: "OWNED",
                    irrigationSource = parcel.irrigationSource,
                    surveyNumber = parcel.surveyNumber,
                    annualRent = parcel.annualRent,
                    sharePercentage = parcel.sharePercentage,
                    sharecropPercentage = parcel.sharecropPercentage,
                    syncStatus = SyncStatus.SYNCED.name,
                    createdAt = now,
                    updatedAt = now,
                    actorId = actorId
                )
            )
        }

        profile.soilProfiles.forEach { soil ->
            db.soilProfileDao().insert(
                SoilProfileEntity(
                    id = soil.id,
                    parcelId = soil.parcelId,
                    farmerId = soil.farmerId,
                    soilTypeCode = soil.soilTypeCode,
                    soilTexture = soil.soilTexture,
                    soilColor = soil.soilColor,
                    ph = soil.ph,
                    nitrogenN = soil.nitrogenN,
                    phosphorusP = soil.phosphorusP,
                    potassiumK = soil.potassiumK,
                    sulphurS = soil.sulphurS,
                    zincZn = soil.zincZn,
                    ironFe = soil.ironFe,
                    copperCu = soil.copperCu,
                    manganeseMn = soil.manganeseMn,
                    boronB = soil.boronB,
                    ec = soil.ec,
                    organicCarbonOc = soil.organicCarbonOc,
                    shcCardNumber = soil.shcCardNumber,
                    dataSource = soil.dataSource ?: "HYDRATED",
                    testDate = soil.testDate,
                    syncStatus = SyncStatus.SYNCED.name,
                    createdAt = now,
                    updatedAt = now,
                    actorId = actorId
                )
            )
        }

        val cycles = profile.cropCycles
        (cycles?.active.orEmpty() + cycles?.completed.orEmpty() + cycles?.other.orEmpty())
            .forEach { CropCycleCache.upsert(context, it) }

        return ProfileHydrationResult(
            profileExists = true,
            farmerId = farmer.id,
            message = "Profile restored"
        )
    }
}
