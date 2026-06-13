package com.agrios.app.ui.navigation

import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.agrios.app.AgriOsApp
import com.agrios.app.core.util.LanguageManager
import com.agrios.app.ui.auth.AuthScreen
import com.agrios.app.ui.auth.AuthViewModel
import com.agrios.app.ui.auth.LanguageSetupScreen
import com.agrios.app.ui.dynamicform.DynamicFormScreen
import com.agrios.app.ui.enrollment.UnifiedEnrollmentScreen
import com.agrios.app.ui.farmer.FarmerEnrollScreen
import com.agrios.app.ui.farmer.FarmerProfileScreen
import com.agrios.app.ui.home.HomeScreen
import com.agrios.app.ui.home.MasterDataLoadingScreen
import com.agrios.app.ui.parcel.ParcelRegisterScreen
import com.agrios.app.ui.settings.SettingsScreen
import com.agrios.app.ui.soil.SoilProfileScreen

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
                onNavigateToFarmerEnroll = { navController.navigate(Routes.UNIFIED_ENROLL) },
                onNavigateToParcelRegister = { navController.navigate(Routes.PARCEL_REGISTER) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                onNavigateToCropCycle = { navController.navigate(Routes.CROP_CYCLE_CREATE) },
                onNavigateToFarmerProfile = { farmerId ->
                    navController.navigate(Routes.FARMER_PROFILE + "?farmerId=$farmerId")
                }
            )
        }

        composable(Routes.FARMER_ENROLL) {
            FarmerEnrollScreen(
                onBack = { navController.popBackStack() },
                onNavigateToParcel = { farmerId ->
                    navController.navigate(Routes.PARCEL_REGISTER + "?farmerId=$farmerId") {
                        popUpTo(Routes.FARMER_ENROLL) { inclusive = true }
                    }
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

        composable(Routes.PARCEL_REGISTER) {
            ParcelRegisterScreen(
                onBack = { navController.popBackStack() },
                onNavigateToSoilProfile = { parcelId, fId ->
                    navController.navigate(Routes.SOIL_PROFILE + "?parcelId=$parcelId&farmerId=$fId") {
                        popUpTo(Routes.PARCEL_REGISTER) { inclusive = true }
                    }
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
            SoilProfileScreen(
                parcelId = parcelId,
                farmerId = farmerId,
                onBack = { navController.popBackStack() }
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
                onSuccess = { navController.popBackStack() }
            )
        }
    }
}
