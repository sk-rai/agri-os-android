package com.agrios.app.ui.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal enum class StartupStage { SESSION_RESTORE, SESSION_READ, LANGUAGE_READ, GEOGRAPHY_READ }
internal enum class StartupDestination { LANGUAGE_SETUP, AUTH, MASTER_DATA_LOADING, HOME }
internal enum class StartupFailure { TIMEOUT, STORAGE_BLOCKED, UNAVAILABLE }
internal sealed interface StartupResult {
    data class Ready(val destination: StartupDestination) : StartupResult
    data class Failed(val stage: StartupStage, val failure: StartupFailure) : StartupResult
}

/** Local startup reads only. Failure never grants a route or resets authentication. */
internal suspend fun resolveStartup(
    restoreSession: suspend () -> Boolean,
    hasActiveSession: suspend () -> Boolean,
    languageConfigured: suspend () -> Boolean,
    cachedStateCount: suspend () -> Int,
    onStage: (StartupStage) -> Unit = {},
    timeoutMillis: Long = 15_000L
): StartupResult {
    require(timeoutMillis > 0)
    var stage = StartupStage.SESSION_RESTORE
    fun enter(next: StartupStage) { stage = next; onStage(next) }
    return try {
        withTimeoutOrNull(timeoutMillis) {
            enter(StartupStage.SESSION_RESTORE)
            if (!restoreSession()) return@withTimeoutOrNull StartupResult.Failed(stage, StartupFailure.STORAGE_BLOCKED)
            enter(StartupStage.SESSION_READ)
            val active = hasActiveSession()
            enter(StartupStage.LANGUAGE_READ)
            val destination = when {
                !languageConfigured() -> StartupDestination.LANGUAGE_SETUP
                !active -> StartupDestination.AUTH
                else -> {
                    enter(StartupStage.GEOGRAPHY_READ)
                    val count = cachedStateCount()
                    check(count >= 0)
                    if (count == 0) StartupDestination.MASTER_DATA_LOADING else StartupDestination.HOME
                }
            }
            StartupResult.Ready(destination)
        } ?: StartupResult.Failed(stage, StartupFailure.TIMEOUT)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        StartupResult.Failed(stage, StartupFailure.UNAVAILABLE)
    }
}
