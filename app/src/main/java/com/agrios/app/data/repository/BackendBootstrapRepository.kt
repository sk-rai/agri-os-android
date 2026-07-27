package com.agrios.app.data.repository

import com.agrios.app.data.remote.api.AgriOsApi
import com.agrios.app.data.remote.dto.AppConfigBootstrapDto
import com.agrios.app.data.remote.dto.AuthModeBootstrapDto
import com.agrios.app.data.remote.dto.FormEndpointHintDto
import com.agrios.app.data.remote.dto.FormSchemaDto
import com.agrios.app.data.remote.dto.GeographyHierarchyProfileDto
import com.agrios.app.data.remote.dto.PinCodeVillageLookupDto
import com.agrios.app.data.remote.dto.ProfileContractDto
import com.agrios.app.data.remote.dto.SeasonLandUnitsMetadataDto
import com.agrios.app.data.remote.dto.WorkflowConfigDto
import com.agrios.app.data.remote.dto.WorkflowSummaryDto
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response

data class BackendBootstrapBundle(
    val modeBootstrap: AuthModeBootstrapDto?,
    val appBootstrap: AppConfigBootstrapDto?,
    val profileContract: ProfileContractDto?,
    val seasonLandUnits: SeasonLandUnitsMetadataDto?,
    val geographyHierarchy: GeographyHierarchyProfileDto?
)

class BackendBootstrapRepository(
    private val api: AgriOsApi
) {
    suspend fun loadInitialBackendContract(projectId: String? = null): Result<BackendBootstrapBundle> =
        withContext(Dispatchers.IO) {
            runCatching {
                BackendBootstrapBundle(
                    modeBootstrap = api.getModeBootstrap().bodyOrThrow("mode bootstrap"),
                    appBootstrap = api.getAppBootstrap(projectId).bodyOrThrow("app bootstrap"),
                    profileContract = api.getProfileContract(projectId).bodyOrThrow("profile contract"),
                    seasonLandUnits = api.getSeasonLandUnitsMetadata(projectId).bodyOrThrow("season/land-unit metadata"),
                    geographyHierarchy = api.getGeographyHierarchyProfile().bodyOrThrow("geography hierarchy")
                )
            }
        }

    suspend fun loadFormOptions(): Result<JsonElement> = withContext(Dispatchers.IO) {
        runCatching { api.getFormOptions().bodyOrThrow("form options") }
    }

    suspend fun loadFormSchema(formId: String): Result<FormSchemaDto> = withContext(Dispatchers.IO) {
        runCatching { api.getFormSchema(formId).bodyOrThrow("form schema $formId") }
    }

    suspend fun loadWorkflows(): Result<List<WorkflowSummaryDto>> = withContext(Dispatchers.IO) {
        runCatching { api.getWorkflows().bodyOrThrow("workflows") }
    }

    suspend fun loadWorkflow(workflowId: String): Result<WorkflowConfigDto> = withContext(Dispatchers.IO) {
        runCatching { api.getWorkflow(workflowId).bodyOrThrow("workflow $workflowId") }
    }

    suspend fun resolveProfileFormHint(
        profileFormKey: String,
        projectId: String? = null
    ): Result<FormEndpointHintDto?> = withContext(Dispatchers.IO) {
        runCatching {
            val appConfig = api.getAppBootstrap(projectId).bodyOrThrow("app bootstrap")
            appConfig.profileForms[profileFormKey]
                ?: appConfig.forms.firstOrNull { hint ->
                    hint.formId == profileFormKey ||
                        hint.endpoint?.trimEnd('/')?.endsWith("/$profileFormKey") == true
                }
        }
    }

    suspend fun loadProfileFormSchema(
        profileFormKey: String,
        projectId: String? = null
    ): Result<FormSchemaDto> = withContext(Dispatchers.IO) {
        runCatching {
            val hint = resolveProfileFormHint(profileFormKey, projectId).getOrThrow()
            val formId = hint?.formId
                ?: hint?.endpoint?.trimEnd('/')?.substringAfterLast('/')
                ?: profileFormKey
            api.getFormSchema(formId).bodyOrThrow("profile form schema $formId")
        }
    }

    suspend fun loadOptionSet(optionSet: String, projectId: String? = null): Result<JsonElement> =
        withContext(Dispatchers.IO) {
            runCatching { api.getFormOptionSet(optionSet, projectId).bodyOrThrow("form option set $optionSet") }
        }

    suspend fun lookupVillagesByPinCode(pinCode: String): Result<PinCodeVillageLookupDto> =
        withContext(Dispatchers.IO) {
            runCatching { api.getVillagesByPinCode(pinCode).bodyOrThrow("PIN-code village lookup") }
        }

    private fun <T> Response<T>.bodyOrThrow(label: String): T {
        if (isSuccessful) {
            return body() ?: throw IllegalStateException("Empty $label response")
        }
        val error = errorBody()?.string()
        throw IllegalStateException("$label failed (${code()}): ${error ?: message()}")
    }
}
