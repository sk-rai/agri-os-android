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
import com.agrios.app.ui.farmer.FarmerEnrollScreen
import com.agrios.app.ui.home.HomeScreen
import com.agrios.app.ui.home.MasterDataLoadingScreen
import com.agrios.app.ui.parcel.ParcelRegisterScreen

object Routes {
    const val LANGUAGE_SETUP = "language_setup"
    const val AUTH = "auth"
    const val MASTER_DATA_LOADING = "master_data_loading"
    const val HOME = "home"
    const val FARMER_ENROLL = "farmer_enroll"
    const val PARCEL_REGISTER = "parcel_register"
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
                onNavigateToParcelRegister = { navController.navigate(Routes.PARCEL_REGISTER) }
            )
        }

        composable(Routes.FARMER_ENROLL) {
            FarmerEnrollScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.PARCEL_REGISTER) {
            ParcelRegisterScreen(onBack = { navController.popBackStack() })
        }
    }
}
