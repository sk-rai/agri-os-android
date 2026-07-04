package com.agrios.app.data.remote.dto

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

data class ParcelGeometryUpdateRequest(
    @SerializedName("geometry_source") val geometrySource: String,
    @SerializedName("geojson") val geojson: JsonElement,
    @SerializedName("accuracy_meters") val accuracyMeters: Double? = null
)

data class ParcelGeometryUpdateResponseDto(
    @SerializedName("status") val status: String? = null,
    @SerializedName("geometry_source") val geometrySource: String? = null,
    @SerializedName("parcel_id") val parcelId: String? = null,
    @SerializedName("centroid_lat") val centroidLat: String? = null,
    @SerializedName("centroid_lng") val centroidLng: String? = null,
    @SerializedName("computed_area_hectares") val computedAreaHectares: String? = null,
    @SerializedName("geojson_type") val geojsonType: String? = null
)
