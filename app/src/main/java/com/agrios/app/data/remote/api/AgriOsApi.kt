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

    @GET("auth/mode-bootstrap")
    suspend fun getModeBootstrap(): Response<AuthModeBootstrapDto>

    @GET("app-config/bootstrap")
    suspend fun getAppBootstrap(
        @Query("project_id") projectId: String? = null
    ): Response<AppConfigBootstrapDto>

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

    @GET("master-data/geography/hierarchy-profile")
    suspend fun getGeographyHierarchyProfile(
        @Query("country_code") countryCode: String? = null
    ): Response<GeographyHierarchyProfileDto>

    @GET("master-data/geography/villages/by-pin-code")
    suspend fun getVillagesByPinCode(
        @Query("pin_code") pinCode: String
    ): Response<PinCodeVillageLookupDto>

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

    @GET("farmers/profile-readiness")
    suspend fun getProfileReadiness(
        @Query("project_id") projectId: String? = null,
        @Query("section") section: String? = null,
        @Query("section_status") sectionStatus: String? = null
    ): Response<com.google.gson.JsonElement>

    @GET("profile/land-intelligence-context")
    suspend fun getLandIntelligenceContext(
        @Query("state_lgd_code") stateLgdCode: String? = null,
        @Query("district_lgd_code") districtLgdCode: String? = null,
        @Query("pin_code") pinCode: String? = null,
        @Query("crop_code") cropCode: String? = null,
        @Query("season_code") seasonCode: String? = null,
        @Query("project_id") projectId: String? = null
    ): Response<LandIntelligenceContextDto>

    @GET("farmers/me/profile")
    suspend fun getMyFarmerProfile(): Response<FarmerProfileHydrationDto>

    @GET("farmers/by-mobile/{mobile}")
    suspend fun getFarmerProfileByMobile(
        @Path(value = "mobile", encoded = true) mobile: String
    ): Response<FarmerProfileHydrationDto>

    // --- Parcels ---
    @POST("parcels")
    suspend fun createParcel(@Body request: CreateParcelDto): Response<ParcelResponseDto>

    @GET("parcels")
    suspend fun getParcels(): Response<List<ParcelResponseDto>>

    @PATCH("parcels/{parcelId}/geometry")
    suspend fun updateParcelGeometry(
        @Path("parcelId") parcelId: String,
        @Body body: ParcelGeometryUpdateRequest
    ): Response<ParcelGeometryUpdateResponseDto>

    // --- Backend-driven forms/options/contracts ---
    @GET("forms/profile-contract")
    suspend fun getProfileContract(
        @Query("project_id") projectId: String? = null
    ): Response<ProfileContractDto>

    @GET("forms/options")
    suspend fun getFormOptions(): Response<com.google.gson.JsonElement>

    @GET("forms/options/{optionSet}")
    suspend fun getFormOptionSet(
        @Path("optionSet") optionSet: String,
        @Query("project_id") projectId: String? = null
    ): Response<com.google.gson.JsonElement>

    @GET("forms/{formId}")
    suspend fun getFormSchema(
        @Path("formId") formId: String,
        @Query("project_id") projectId: String? = null
    ): Response<FormSchemaDto>

    @GET("forms/metadata/season-land-units")
    suspend fun getSeasonLandUnitsMetadata(
        @Query("project_id") projectId: String? = null
    ): Response<SeasonLandUnitsMetadataDto>

    // --- Backend-configured workflows ---
    @GET("workflows")
    suspend fun getWorkflows(): Response<List<WorkflowSummaryDto>>

    @GET("workflows/{workflowId}")
    suspend fun getWorkflow(
        @Path("workflowId") workflowId: String
    ): Response<WorkflowConfigDto>

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

    // --- Crop Cycles ---
    @GET("crop-cycles/templates/{cropCode}")
    suspend fun getCropTemplate(
        @Path("cropCode") cropCode: String,
        @Query("season") season: String? = null
    ): Response<CropTemplateDto>

    @POST("crop-cycles")
    suspend fun createCropCycle(@Body request: Map<String, @JvmSuppressWildcards Any?>): Response<CropCycleResponseDto>

    @GET("crop-cycles")
    suspend fun getCropCycles(
        @Query("parcel_id") parcelId: String? = null,
        @Query("farmer_id") farmerId: String? = null,
        @Query("status") status: String? = null
    ): Response<List<CropCycleResponseDto>>

    @GET("crop-cycles/{id}")
    suspend fun getCropCycle(@Path("id") id: String): Response<CropCycleResponseDto>

    @GET("crop-cycles/{cycleId}/recommended-activities")
    suspend fun getRecommendedActivities(
        @Path("cycleId") cycleId: String
    ): Response<CycleRecommendedActivitiesResponseDto>

    @PATCH("crop-cycles/{cycleId}/stages/{stageId}")
    suspend fun updateStage(
        @Path("cycleId") cycleId: String,
        @Path("stageId") stageId: String,
        @Body request: StageUpdateDto
    ): Response<StageTransitionResponseDto>
}
