package com.agrios.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.agrios.app.core.auth.RecoveryPhase
import com.agrios.app.core.database.AppDatabase
import com.agrios.app.core.sync.QueueDispatchLease
import com.agrios.app.data.local.entity.AuthStateEntity
import com.agrios.app.data.repository.SelfProfileCacheRefresh
import com.agrios.app.data.repository.SelfProfileCacheStore
import com.agrios.app.data.repository.SelfProfileProjection
import com.agrios.app.data.repository.SelfProfileRefreshResult
import com.agrios.app.ui.home.HomeServerProfileMessage.FAILED
import com.agrios.app.ui.home.HomeServerProfileMessage.NOT_FOUND
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal enum class HomeServerProfileMessage { FAILED, NOT_FOUND }

internal data class HomeServerProfileSession(val auth: AuthStateEntity, val epoch: Long) {
    override fun toString() = "HomeServerProfileSession(REDACTED)"
}

internal class HomeServerProfileState(private val session: HomeServerProfileSession) {
    private var generation = 0L
    private var pending = false
    private var display by mutableStateOf<Display?>(null)

    internal class Display(val projection: SelfProfileProjection?, val message: HomeServerProfileMessage?)

    fun begin(): Long {
        display = null
        pending = true
        return ++generation
    }

    fun dismiss() {
        ++generation
        pending = false
        display = null
    }

    fun publish(request: Long, observed: HomeServerProfileSession?, projection: SelfProfileProjection? = null,
                message: HomeServerProfileMessage? = null) {
        if (!pending || request != generation || session != observed) return
        pending = false
        val owner = projection?.farmer
        if (owner != null && (owner.tenantId != session.auth.tenantId || owner.userId != session.auth.userId)) return
        display = if (projection == null && message == null) null else Display(projection, message)
    }

    fun visible(observed: HomeServerProfileSession?): Display? =
        display.takeIf { observed == session }
}

@Composable
internal fun HomeServerProfilePanel(db: AppDatabase, observedAuth: AuthStateEntity?) {
    val coordinator = db.sessionExpiry
    val epoch by coordinator.sessionEpoch.collectAsState()
    val recovery by coordinator.state.collectAsState()
    val observed = observedAuth?.takeIf {
        it.role == "FARMER" && it.isAuthenticated && !it.reauthRequired && !it.jwt.isNullOrBlank() &&
            !it.tenantId.isNullOrBlank() && !it.userId.isNullOrBlank() &&
            (recovery.phase == RecoveryPhase.NORMAL || recovery.phase == RecoveryPhase.AUTHENTICATED_RESTORED)
    }?.let { HomeServerProfileSession(it, epoch) } ?: return
    key(db) {
        HomeServerProfileRequestPanel(
            observed,
            observed.takeIf { it.epoch == coordinator.sessionEpoch.value && recovery == coordinator.state.value }
        ) { atTap, publish ->
            val lease = coordinator.requestLease() ?: return@HomeServerProfileRequestPanel
            if (lease.epoch != atTap.epoch || lease.state != atTap.auth) return@HomeServerProfileRequestPanel
            val origin = QueueDispatchLease(coordinator, lease)
            try {
                val outcome = SelfProfileCacheRefresh.create(db).refresh(origin)
                val receipt = outcome.receipt
                val result = receipt?.takeIf { it.origin === origin }?.let {
                    SelfProfileCacheStore(db).read(origin, it.farmerId)
                        ?.takeIf { projection -> projection.farmer.farmerId == it.farmerId }
                }
                coordinator.withCurrentLease(lease) {
                    val message = if (outcome.result == SelfProfileRefreshResult.NO_PROFILE) NOT_FOUND else FAILED
                    publish(result, if (result == null) message else null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                coordinator.withCurrentLease(lease) { publish(null, FAILED) }
            }
        }
    }
}

/** The production tap/render boundary; isolation tests supply a fake request only. */
@Composable
internal fun HomeServerProfileRequestPanel(
    observed: HomeServerProfileSession,
    renderSession: HomeServerProfileSession?,
    requestProfile: suspend (HomeServerProfileSession, (SelfProfileProjection?, HomeServerProfileMessage?) -> Unit) -> Unit
) {
    val currentObserved by rememberUpdatedState(renderSession)
    val currentRequest by rememberUpdatedState(requestProfile)
    key(observed) {
        val state = remember { HomeServerProfileState(observed) }
        val scope = rememberCoroutineScope()
        var job by remember { mutableStateOf<Job?>(null) }
        val display = state.visible(renderSession)
        Column {
            OutlinedButton(enabled = renderSession == observed, onClick = {
                val request = state.begin()
                job?.cancel()
                if (currentObserved != observed) return@OutlinedButton
                job = scope.launch {
                    currentRequest(observed) { result, message ->
                        state.publish(request, currentObserved, result, message)
                    }
                }
            }) { Text("View server profile") }
            HomeServerProfileContent(display) {
                state.dismiss()
                job?.cancel()
            }
        }
    }
}

@Composable
internal fun HomeServerProfileContent(display: HomeServerProfileState.Display?, onDismiss: () -> Unit) {
    val result = display?.projection
    if (result == null) {
        when (display?.message) {
            NOT_FOUND -> Text("No linked server profile found.")
            FAILED -> Text("Server profile unavailable.")
            null -> Unit
        }
        return
    }
    Card {
        Column {
            Text("Cached server profile · read only")
            Text("This cache may not contain all server records.")
            result.farmer.displayName?.let { Text(it) }
            result.farmer.villageNameManual?.let { Text(it) }
            Text("Cached parcels: ${result.parcels.size}")
            Text("Cached soil profiles: ${result.soil.size}")
            OutlinedButton(onClick = onDismiss) { Text("Dismiss server profile") }
        }
    }
}
