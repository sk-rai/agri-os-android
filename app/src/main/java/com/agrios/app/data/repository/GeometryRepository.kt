package com.agrios.app.data.repository

import android.util.Log
import com.agrios.app.core.geo.GeoJson
import com.agrios.app.data.local.dao.SyncQueueDao
import com.agrios.app.data.local.entity.SyncPriority
import com.agrios.app.data.local.entity.SyncQueueEntity
import com.agrios.app.data.local.entity.SyncStatus
import com.agrios.app.data.remote.api.AgriOsApi
import com.agrios.app.data.remote.dto.ParcelGeometryUpdateRequest
import com.agrios.app.data.remote.dto.ParcelGeometryUpdateResponseDto
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.IOException
import java.util.UUID

data class GeometryCaptureResult(
    val geometrySource: String,
    val geoJson: String,
    val accuracyMeters: Double? = null
)

object GeometryRepository {
    private const val TAG = "GeometryRepository"
    const val ENTITY_TYPE_PARCEL_GEOMETRY = "PARCEL_GEOMETRY"

    fun resultForSource(geometrySource: String, geoJson: String, accuracyMeters: Double? = null): GeometryCaptureResult {
        return GeometryCaptureResult(
            geometrySource = geometrySource,
            geoJson = geoJson,
            accuracyMeters = accuracyMeters
        )
    }

    fun requestFromResult(result: GeometryCaptureResult): ParcelGeometryUpdateRequest {
        return ParcelGeometryUpdateRequest(
            geometrySource = result.geometrySource,
            geojson = JsonParser.parseString(result.geoJson),
            accuracyMeters = result.accuracyMeters
        )
    }

    suspend fun submitParcelGeometry(
        api: AgriOsApi,
        parcelId: String,
        result: GeometryCaptureResult
    ): Result<ParcelGeometryUpdateResponseDto?> {
        if (!GeoJson.isGeoJson(result.geoJson)) {
            return Result.failure(IllegalArgumentException("Invalid GeoJSON geometry"))
        }
        return try {
            val response = api.updateParcelGeometry(parcelId, requestFromResult(result))
            if (response.isSuccessful) {
                Result.success(response.body())
            } else {
                val errorBody = response.errorBody()?.string()
                Result.failure(IllegalStateException("HTTP ${response.code()}: ${errorBody ?: response.message()}"))
            }
        } catch (e: IOException) {
            Log.w(TAG, "Network error submitting parcel geometry: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun enqueueParcelGeometry(
        syncQueueDao: SyncQueueDao,
        parcelId: String,
        result: GeometryCaptureResult,
        dependencyIds: String? = parcelId,
        gson: Gson = Gson()
    ) {
        val payload = gson.toJson(
            mapOf(
                "geometry_source" to result.geometrySource,
                "geojson" to JsonParser.parseString(result.geoJson),
                "accuracy_meters" to result.accuracyMeters
            )
        )
        syncQueueDao.enqueue(
            SyncQueueEntity(
                eventId = UUID.randomUUID().toString(),
                entityType = ENTITY_TYPE_PARCEL_GEOMETRY,
                entityId = parcelId,
                operation = "PATCH_GEOMETRY",
                payload = payload,
                syncStatus = SyncStatus.PENDING.name,
                priority = SyncPriority.HIGH.name,
                dependencyIds = dependencyIds,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    fun resultFromQueuePayload(payload: String): GeometryCaptureResult {
        val obj = JsonParser.parseString(payload).asJsonObject
        return GeometryCaptureResult(
            geometrySource = obj.get("geometry_source").asString,
            geoJson = obj.get("geojson").toString(),
            accuracyMeters = obj.get("accuracy_meters")?.takeIf { !it.isJsonNull }?.asDouble
        )
    }
}
