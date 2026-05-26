package com.agrios.app.data.remote.dto

import com.google.gson.annotations.SerializedName

// --- Master Data Sync ---
data class MasterDataSyncRequestDto(
    @SerializedName("versions") val versions: Map<String, String> = emptyMap()
)

data class MasterDataSyncResponseDto(
    @SerializedName("geography") val geography: GeographySyncDto? = null,
    @SerializedName("crops") val crops: CropsSyncDto? = null
)

data class GeographySyncDto(
    @SerializedName("version") val version: String,
    @SerializedName("states") val states: List<GeographyStateDto>? = null,
    @SerializedName("districts") val districts: List<GeographyDistrictDto>? = null,
    @SerializedName("blocks") val blocks: List<GeographyBlockDto>? = null,
    @SerializedName("villages") val villages: List<GeographyVillageDto>? = null
)

data class CropsSyncDto(
    @SerializedName("version") val version: String,
    @SerializedName("categories") val categories: List<CropCategoryDto>? = null,
    @SerializedName("crops") val crops: List<CropDto>? = null
)

// --- Geography DTOs ---
data class GeographyStateDto(
    @SerializedName("id") val id: String,
    @SerializedName("lgd_code") val lgdCode: String,
    @SerializedName("canonical_name") val canonicalName: String,
    @SerializedName("aliases") val aliases: List<String>? = null
)

data class GeographyDistrictDto(
    @SerializedName("id") val id: String,
    @SerializedName("lgd_code") val lgdCode: String,
    @SerializedName("state_id") val stateId: String,
    @SerializedName("canonical_name") val canonicalName: String,
    @SerializedName("aliases") val aliases: List<String>? = null
)

data class GeographyBlockDto(
    @SerializedName("id") val id: String,
    @SerializedName("lgd_code") val lgdCode: String,
    @SerializedName("district_id") val districtId: String,
    @SerializedName("canonical_name") val canonicalName: String,
    @SerializedName("aliases") val aliases: List<String>? = null
)

data class GeographyVillageDto(
    @SerializedName("id") val id: String,
    @SerializedName("lgd_code") val lgdCode: String,
    @SerializedName("block_id") val blockId: String,
    @SerializedName("district_id") val districtId: String,
    @SerializedName("canonical_name") val canonicalName: String,
    @SerializedName("pin_codes") val pinCodes: List<String>? = null,
    @SerializedName("aliases") val aliases: List<String>? = null
)

// --- Crop DTOs ---
data class CropCategoryDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String? = null
)

data class CropDto(
    @SerializedName("id") val id: String,
    @SerializedName("crop_code") val cropCode: String,
    @SerializedName("canonical_name") val canonicalName: String,
    @SerializedName("category_id") val categoryId: String,
    @SerializedName("season") val season: String? = null,
    @SerializedName("aliases") val aliases: List<String>? = null
)
