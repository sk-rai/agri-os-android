package com.agrios.app.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.agrios.app.AgriOsApp
import com.agrios.app.core.network.ApiConfig
import com.agrios.app.core.network.AuthInterceptor
import com.agrios.app.core.util.LanguageManager
import com.agrios.app.data.remote.api.AgriOsApi
import com.agrios.app.data.repository.BackendBootstrapRepository
import com.agrios.app.ui.auth.AuthScreen
import com.agrios.app.ui.auth.AuthViewModel
import com.agrios.app.ui.auth.LanguageSetupScreen
import com.agrios.app.ui.cropcycle.StageActivitiesScreen
import com.agrios.app.ui.cropcycle.StageTimelineScreen
import com.agrios.app.ui.dynamicform.DynamicFormScreen
import com.agrios.app.ui.enrollment.UnifiedEnrollmentScreen
import com.agrios.app.ui.farmer.FarmerEnrollScreen
import com.agrios.app.ui.farmer.FarmerProfileScreen
import com.agrios.app.ui.home.HomeScreen
import com.agrios.app.ui.home.MasterDataLoadingScreen
import com.agrios.app.ui.parcel.ParcelRegisterScreen
import com.agrios.app.ui.settings.SettingsScreen
import com.agrios.app.ui.soil.SoilProfileScreen
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object Routes {
    const val LANGUAGE_SETUP = "language_setup"
    const val AUTH = "auth"
    const val MASTER_DATA_LOADING = "master_data_loading"
    const val HOME = "home"
    const val FARMER_ENROLL = "farmer_enroll"
    const val UNIFIED_ENROLL = "unified_enroll"
    const val FARMER_PROFILE = "farmer_profile"
    const val PARCEL_REGISTER = "parcel_register"
    const val SOIL_PROFILE = "soil_profile"
    const val CROP_CYCLE_CREATE = "crop_cycle_create"
    const val STAGE_TIMELINE = "stage_timeline"
    const val ACTIVITY_LOG = "activity_log"
    const val STAGE_ACTIVITIES = "stage_activities"
    const val SETTINGS = "settings"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val db = AgriOsApp.instance.database

    // Determine start destination
    var startDestination by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        startDestination = when {
            // First time: language setup
            !LanguageManager.isSetupDone() -> Routes.LANGUAGE_SETUP
            // Not authenticated: login
            db.authDao().getAuthState()?.isAuthenticated != true -> Routes.AUTH
            // No master data cached: download
            db.geographyCacheDao().getStateCount() == 0 -> Routes.MASTER_DATA_LOADING
            // All good: home
            else -> Routes.HOME
        }
    }

    if (startDestination == null) return // Loading

    NavHost(
        navController = navController,
        startDestination = startDestination!!
    ) {
        composable(Routes.LANGUAGE_SETUP) {
            LanguageSetupScreen(
                onContinue = {
                    LanguageManager.markSetupDone()
                    navController.navigate(Routes.AUTH) {
                        popUpTo(Routes.LANGUAGE_SETUP) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.AUTH) {
            val authViewModel: AuthViewModel = viewModel()
            AuthScreen(
                viewModel = authViewModel,
                onAuthSuccess = {
                    navController.navigate(Routes.MASTER_DATA_LOADING) {
                        popUpTo(Routes.AUTH) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.MASTER_DATA_LOADING) {
            MasterDataLoadingScreen(
                onComplete = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.MASTER_DATA_LOADING) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                onNavigateToFarmerEnroll = { navController.navigate(Routes.FARMER_ENROLL) },
                onNavigateToParcelRegister = { navController.navigate(Routes.PARCEL_REGISTER) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                onNavigateToCropCycle = { navController.navigate(Routes.CROP_CYCLE_CREATE) },
                onNavigateToFarmerProfile = { farmerId ->
                    navController.navigate(Routes.FARMER_PROFILE + "?farmerId=$farmerId")
                },
                onNavigateToStageTimeline = { cycleId ->
                    navController.navigate(Routes.STAGE_TIMELINE + "?cycleId=$cycleId")
                }
            )
        }

        composable(Routes.FARMER_ENROLL) {
            BackendDrivenProfileFormGate(
                formId = "farmer_registration",
                featureFlag = "backend_driven_farmer_forms",
                onBack = { navController.popBackStack() },
                onSuccess = {
                    navController.navigate(Routes.PARCEL_REGISTER) {
                        popUpTo(Routes.FARMER_ENROLL) { inclusive = true }
                    }
                },
                fallback = {
                    FarmerEnrollScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToParcel = { farmerId ->
                            navController.navigate(Routes.PARCEL_REGISTER + "?farmerId=$farmerId") {
                                popUpTo(Routes.FARMER_ENROLL) { inclusive = true }
                            }
                        }
                    )
                }
            )
        }

        composable(Routes.UNIFIED_ENROLL) {
            UnifiedEnrollmentScreen(
                onBack = { navController.popBackStack() },
                onComplete = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.UNIFIED_ENROLL) { inclusive = true }
                    }
                }
            )
        }

        composable(
            Routes.PARCEL_REGISTER + "?farmerId={farmerId}",
            arguments = listOf(androidx.navigation.navArgument("farmerId") {
                type = androidx.navigation.NavType.StringType
                defaultValue = ""
            })
        ) { backStackEntry ->
            val farmerId = backStackEntry.arguments?.getString("farmerId") ?: ""
            BackendDrivenProfileFormGate(
                formId = "parcel_registration",
                featureFlag = "backend_driven_parcel_forms",
                contextValues = mapOf("farmer_id" to farmerId).filterValues { it.isNotBlank() },
                onBack = { navController.popBackStack() },
                onSuccess = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.PARCEL_REGISTER + "?farmerId={farmerId}") { inclusive = true }
                    }
                },
                fallback = {
                    ParcelRegisterScreen(
                        onBack = { navController.popBackStack() },
                        preselectedFarmerId = farmerId,
                        onNavigateToSoilProfile = { parcelId, fId ->
                            navController.navigate(Routes.SOIL_PROFILE + "?parcelId=$parcelId&farmerId=$fId") {
                                popUpTo(Routes.PARCEL_REGISTER + "?farmerId={farmerId}") { inclusive = true }
                            }
                        }
                    )
                }
            )
        }

        composable(Routes.PARCEL_REGISTER) {
            BackendDrivenProfileFormGate(
                formId = "parcel_registration",
                featureFlag = "backend_driven_parcel_forms",
                onBack = { navController.popBackStack() },
                onSuccess = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.PARCEL_REGISTER) { inclusive = true }
                    }
                },
                fallback = {
                    ParcelRegisterScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToSoilProfile = { parcelId, fId ->
                            navController.navigate(Routes.SOIL_PROFILE + "?parcelId=$parcelId&farmerId=$fId") {
                                popUpTo(Routes.PARCEL_REGISTER) { inclusive = true }
                            }
                        }
                    )
                }
            )
        }

        composable(
            Routes.SOIL_PROFILE + "?parcelId={parcelId}&farmerId={farmerId}",
            arguments = listOf(
                androidx.navigation.navArgument("parcelId") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = ""
                },
                androidx.navigation.navArgument("farmerId") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val parcelId = backStackEntry.arguments?.getString("parcelId") ?: ""
            val farmerId = backStackEntry.arguments?.getString("farmerId") ?: ""
            BackendDrivenProfileFormGate(
                formId = "soil_profile",
                featureFlag = "backend_driven_soil_forms",
                contextValues = mapOf(
                    "parcel_id" to parcelId,
                    "farmer_id" to farmerId
                ).filterValues { it.isNotBlank() },
                onBack = { navController.popBackStack() },
                onSuccess = { navController.popBackStack() },
                fallback = {
                    SoilProfileScreen(
                        parcelId = parcelId,
                        farmerId = farmerId,
                        onBack = { navController.popBackStack() }
                    )
                }
            )
        }

        composable(
            Routes.FARMER_PROFILE + "?farmerId={farmerId}",
            arguments = listOf(androidx.navigation.navArgument("farmerId") {
                type = androidx.navigation.NavType.StringType
                defaultValue = ""
            })
        ) { backStackEntry ->
            val farmerId = backStackEntry.arguments?.getString("farmerId") ?: ""
            FarmerProfileScreen(
                farmerId = farmerId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            Routes.STAGE_ACTIVITIES + "?cycleId={cycleId}&stageCode={stageCode}",
            arguments = listOf(
                androidx.navigation.navArgument("cycleId") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = ""
                },
                androidx.navigation.navArgument("stageCode") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val cycleId = backStackEntry.arguments?.getString("cycleId") ?: ""
            val stageCode = backStackEntry.arguments?.getString("stageCode") ?: ""
            StageActivitiesScreen(
                cycleId = cycleId,
                selectedStageCode = stageCode.ifBlank { null },
                onBack = { navController.popBackStack() },
                onLogActivity = { prefillData ->
                    val activityType = Uri.encode(prefillData["activity_type"] ?: "")
                    val inputName = Uri.encode(prefillData["input_name"] ?: "")
                    val activityDate = Uri.encode(prefillData["activity_date"] ?: "")
                    val targetStageCode = Uri.encode(prefillData["stage_code"] ?: stageCode)
                    navController.navigate(
                        Routes.ACTIVITY_LOG + "?cycleId=${Uri.encode(cycleId)}&stageCode=$targetStageCode&activityType=$activityType&inputName=$inputName&activityDate=$activityDate"
                    )
                },
                onLogCustomActivity = {
                    navController.navigate(
                        Routes.ACTIVITY_LOG + "?cycleId=${Uri.encode(cycleId)}&stageCode=${Uri.encode(stageCode)}"
                    )
                }
            )
        }

        composable(
            Routes.ACTIVITY_LOG + "?cycleId={cycleId}&stageCode={stageCode}&activityType={activityType}&inputName={inputName}&activityDate={activityDate}",
            arguments = listOf(
                androidx.navigation.navArgument("cycleId") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = ""
                },
                androidx.navigation.navArgument("stageCode") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = ""
                },
                androidx.navigation.navArgument("activityType") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = ""
                },
                androidx.navigation.navArgument("inputName") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = ""
                },
                androidx.navigation.navArgument("activityDate") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val cycleId = backStackEntry.arguments?.getString("cycleId") ?: ""
            val stageCode = backStackEntry.arguments?.getString("stageCode") ?: ""
            val activityType = backStackEntry.arguments?.getString("activityType") ?: ""
            val inputName = backStackEntry.arguments?.getString("inputName") ?: ""
            val activityDate = backStackEntry.arguments?.getString("activityDate") ?: ""
            val context = mutableMapOf("crop_cycle_id" to cycleId)
            if (stageCode.isNotBlank()) context["stage_code"] = stageCode
            if (activityType.isNotBlank()) context["activity_type"] = activityType
            if (inputName.isNotBlank()) context["input_name"] = inputName
            if (activityDate.isNotBlank()) context["activity_date"] = activityDate
            DynamicFormScreen(
                formId = "activity_log",
                contextValues = context,
                onBack = { navController.popBackStack() },
                onSuccess = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onLogout = {
                    navController.navigate(Routes.AUTH) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.CROP_CYCLE_CREATE) {
            DynamicFormScreen(
                formId = "crop_cycle_create",
                onBack = { navController.popBackStack() },
                onSuccess = {
                    // Navigate to stage timeline if we have a cycle ID
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.CROP_CYCLE_CREATE) { inclusive = true }
                    }
                },
                onCycleCreated = { cycleId ->
                    navController.navigate(Routes.STAGE_TIMELINE + "?cycleId=$cycleId") {
                        popUpTo(Routes.CROP_CYCLE_CREATE) { inclusive = true }
                    }
                }
            )
        }

        composable(
            Routes.STAGE_TIMELINE + "?cycleId={cycleId}",
            arguments = listOf(androidx.navigation.navArgument("cycleId") {
                type = androidx.navigation.NavType.StringType
                defaultValue = ""
            })
        ) { backStackEntry ->
            val cycleId = backStackEntry.arguments?.getString("cycleId") ?: ""
            StageTimelineScreen(
                cycleId = cycleId,
                onBack = { navController.popBackStack() },
                onNavigateToActivityLog = { cId, stageCode ->
                    navController.navigate(Routes.STAGE_ACTIVITIES + "?cycleId=${Uri.encode(cId)}&stageCode=${Uri.encode(stageCode)}")
                }
            )
        }
    }
}

@Composable
private fun BackendDrivenProfileFormGate(
    formId: String,
    featureFlag: String,
    contextValues: Map<String, String> = emptyMap(),
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    fallback: @Composable () -> Unit
) {
    val db = AgriOsApp.instance.database
    var useBackendForm by remember(formId, featureFlag) { mutableStateOf<Boolean?>(null) }

    val api = remember {
        val okHttp = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(db.authDao()))
            .connectTimeout(ApiConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(ApiConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(ApiConfig.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AgriOsApi::class.java)
    }

    LaunchedEffect(formId, featureFlag) {
        useBackendForm = try {
            val hint = BackendBootstrapRepository(api).resolveProfileFormHint(formId).getOrNull()
            val appConfig = api.getAppBootstrap().body()
            val flagEnabled = appConfig?.featureFlags?.get(featureFlag) == true
            val formEnabled = hint?.enabled != false
            flagEnabled && formEnabled
        } catch (_: Exception) {
            false
        }
    }

    when (useBackendForm) {
        true -> DynamicFormScreen(
            formId = formId,
            contextValues = contextValues,
            onBack = onBack,
            onSuccess = onSuccess
        )

        false -> fallback()

        null -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("Loading profile form...")
            }
        }
    }
}
