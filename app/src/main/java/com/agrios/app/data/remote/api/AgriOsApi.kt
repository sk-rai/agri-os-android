package com.agrios.app.data.remote.api

import com.agrios.app.data.remote.dto.*
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit API interface for Agri-OS backend.
 * All endpoints aligned with backend contract.
 */
interface AgriOsApi {

    // --- Auth ---
    @POST("auth/otp/request")
    suspend fun requestOtp(@Body request: OtpRequestDto): Response<OtpResponseDto>

    @POST("auth/otp/verify")
    suspend fun verifyOtp(@Body request: OtpVerifyDto): Response<AuthResponseDto>

    @POST("auth/device")
    suspend fun authenticateDevice(@Body request: DeviceAuthDto): Response<AuthResponseDto>

    // --- Master Data ---
    @POST("master-data/sync")
    suspend fun syncMasterData(@Body request: MasterDataSyncRequestDto): Response<MasterDataSyncResponseDto>

    @GET("master-data/geography/states")
    suspend fun getStates(): Response<List<GeographyStateDto>>

    @GET("master-data/geography/districts")
    suspend fun getDistricts(@Query("state_id") stateId: String): Response<List<GeographyDistrictDto>>

    @GET("master-data/geography/blocks")
    suspend fun getBlocks(@Query("district_id") districtId: String): Response<List<GeographyBlockDto>>

    @GET("master-data/geography/villages")
    suspend fun getVillages(
        @Query("block_id") blockId: String? = null,
        @Query("district_id") districtId: String? = null,
        @Query("search") search: String? = null
    ): Response<List<GeographyVillageDto>>

    @GET("master-data/geography/villages/search")
    suspend fun searchVillages(
        @Query("q") query: String,
        @Query("district_id") districtId: String? = null,
        @Query("limit") limit: Int = 20
    ): Response<List<GeographyVillageDto>>

    @GET("master-data/crops/categories")
    suspend fun getCropCategories(): Response<List<CropCategoryDto>>

    @GET("master-data/crops")
    suspend fun getCrops(
        @Query("category_id") categoryId: String? = null,
        @Query("season") season: String? = null
    ): Response<List<CropDto>>

    // --- Farmers ---
    @POST("farmers")
    suspend fun createFarmer(@Body request: CreateFarmerDto): Response<FarmerResponseDto>

    @GET("farmers")
    suspend fun getFarmers(): Response<List<FarmerResponseDto>>

    // --- Parcels ---
    @POST("parcels")
    suspend fun createParcel(@Body request: CreateParcelDto): Response<ParcelResponseDto>

    @GET("parcels")
    suspend fun getParcels(): Response<List<ParcelResponseDto>>

    // --- Sync ---
    @POST("sync/events")
    suspend fun syncEvents(@Body request: SyncBatchRequestDto): Response<SyncBatchResponseDto>

    @GET("sync/conflicts")
    suspend fun getConflicts(): Response<List<SyncConflictDto>>

    @PATCH("sync/conflicts/{id}")
    suspend fun resolveConflict(
        @Path("id") conflictId: String,
        @Body request: ResolveConflictDto
    ): Response<Unit>

    // --- Soil Profiles ---
    @GET("soil-profiles/infer/{districtName}")
    suspend fun inferSoilType(
        @Path("districtName") districtName: String
    ): Response<SoilInferenceResponseDto>

    @POST("soil-profiles")
    suspend fun createSoilProfile(@Body request: CreateSoilProfileDto): Response<SoilProfileResponseDto>

    @GET("soil-profiles")
    suspend fun getSoilProfiles(@Query("parcel_id") parcelId: String): Response<List<SoilProfileResponseDto>>
}
