package com.agrios.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.agrios.app.core.auth.RecoveryPhase
import com.agrios.app.core.auth.SessionExpiryCoordinator

/** HOME is deliberate: no mutation form, arbitrary URI or request is retained/replayed. */
@Composable
internal fun SessionRecoveryNavigation(coordinator: SessionExpiryCoordinator, nav: NavHostController) {
    val recovery by coordinator.state.collectAsState()
    val entry by nav.currentBackStackEntryAsState()
    LaunchedEffect(recovery, entry?.destination?.route) {
        when (recovery.phase) {
            RecoveryPhase.LOGIN_REQUIRED, RecoveryPhase.REAUTHENTICATING -> {
                coordinator.claimLoginNavigation(recovery.loginEvent)
                // Also guard a newly restored controller if the old controller
                // consumed its event immediately before recreation.
                if (entry != null && entry?.destination?.route != Routes.AUTH) nav.navigate(Routes.AUTH) {
                    popUpTo(nav.graph.id) { inclusive = true }; launchSingleTop = true
                }
            }
            RecoveryPhase.AUTHENTICATED_RESTORED -> {
                coordinator.claimSuccessNavigation(recovery.successEvent)
                if (entry?.destination?.route == Routes.AUTH) {
                    nav.navigate(if (recovery.recovered) Routes.HOME else Routes.MASTER_DATA_LOADING) {
                        popUpTo(nav.graph.id) { inclusive = true }; launchSingleTop = true
                    }
                }
            }
            else -> Unit
        }
    }
}
