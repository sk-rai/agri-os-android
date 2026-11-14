package com.agrios.app.core.auth

import com.agrios.app.data.local.dao.AuthDao
import com.agrios.app.data.local.entity.AuthStateEntity
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class RecoveryPhase { NORMAL, EXPIRY_DETECTED, SESSION_CLEARED, LOGIN_REQUIRED, REAUTHENTICATING, AUTHENTICATED_RESTORED, CLEAR_FAILED }

internal enum class AuthenticationCompletion {
    ACCEPTED, STALE_ATTEMPT, INVALID_IDENTITY, STORAGE_BLOCKED, RECOVERY_IDENTITY_MISMATCH
}

internal data class RecoveryIdentityComparison(val userMatches: Boolean, val tenantMatches: Boolean, val roleMatches: Boolean)

internal data class AuthenticationDecision(val completion: AuthenticationCompletion,
    val comparison: RecoveryIdentityComparison? = null)

internal class AuthenticationRejected(val reason: AuthenticationCompletion,
    val comparison: RecoveryIdentityComparison? = null) :
    IllegalStateException("AUTHENTICATION_${reason.name}")

/** Only enums/counters/booleans cross into UI or diagnostic state. Destination is always safe home. */
data class RecoveryState(val phase: RecoveryPhase = RecoveryPhase.NORMAL,
    val loginEvent: Long = 0, val successEvent: Long = 0, val recovered: Boolean = false)

internal class AuthAttempt internal constructor(internal val generation: Long) {
    override fun toString() = "AuthAttempt(REDACTED)"
}
internal class SessionLease(val state: AuthStateEntity, val epoch: Long) {
    override fun toString() = "SessionLease(REDACTED)"
}

class SessionExpiryCoordinator(private val dao: AuthDao,
    private val invalidateStored: suspend (String) -> Boolean = dao::invalidateSession,
    private val holdDeparting: suspend (AuthStateEntity) -> Unit = {}) {
    private val mutex = Mutex()
    // Nonsecret instance discriminator: a reset in-memory epoch cannot revive persisted work.
    internal val workSessionInstance = UUID.randomUUID().toString()
    private val mutable = MutableStateFlow(RecoveryState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private val credentialEpochState = MutableStateFlow(0L)
    internal val sessionEpoch = credentialEpochState.asStateFlow()
    private var credentialEpoch: Long
        get() = credentialEpochState.value
        set(value) { credentialEpochState.value = value }
    private var claimedLogin = 0L
    private var claimedSuccess = 0L

    internal fun owns(candidate: AuthDao): Boolean = dao === candidate

    private fun restoreMarker(current: AuthStateEntity?) {
        if (current?.reauthRequired == true && mutable.value.phase == RecoveryPhase.NORMAL)
            mutable.value = mutable.value.copy(phase = RecoveryPhase.LOGIN_REQUIRED, loginEvent = mutable.value.loginEvent + 1)
    }

    suspend fun restore() = mutex.withLock {
        try { restoreMarker(dao.getAuthState()) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.value = mutable.value.copy(phase = RecoveryPhase.CLEAR_FAILED) }
    }

    private suspend fun activeLocked(): AuthStateEntity? {
        val current = dao.getAuthState()
        restoreMarker(current)
        return current?.takeIf { it.isAuthenticated && !it.reauthRequired && !it.jwt.isNullOrBlank() &&
            !it.userId.isNullOrBlank() && !it.tenantId.isNullOrBlank() && mutable.value.phase != RecoveryPhase.CLEAR_FAILED }
    }
    internal suspend fun requestLease(): SessionLease? = mutex.withLock {
        try { activeLocked()?.let { SessionLease(it, credentialEpoch) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.value = mutable.value.copy(phase = RecoveryPhase.CLEAR_FAILED); null }
    }
    internal suspend fun requestState() = requestLease()?.state

    private suspend fun matchesLocked(lease: SessionLease): Boolean {
        val current = activeLocked() ?: return false
        return credentialEpoch == lease.epoch && current == lease.state
    }

    /** Short local reservation/settlement only; never hold through network I/O. */
    internal suspend fun <T> withCurrentLease(lease: SessionLease, local: suspend () -> T): T? = mutex.withLock {
        if (!matchesLocked(lease)) null else local()
    }

    internal suspend fun isCurrentLease(lease: SessionLease): Boolean = mutex.withLock { matchesLocked(lease) }

    internal suspend fun requireLogin() = mutex.withLock {
        if (mutable.value.phase == RecoveryPhase.CLEAR_FAILED) return@withLock
        val current = dao.getAuthState()
        if (current?.isAuthenticated == true && !current.reauthRequired && !current.jwt.isNullOrBlank() &&
            !current.userId.isNullOrBlank() && !current.tenantId.isNullOrBlank()) return@withLock
        restoreMarker(current)
        if (mutable.value.phase !in setOf(RecoveryPhase.LOGIN_REQUIRED, RecoveryPhase.REAUTHENTICATING)) {
            generation++
            credentialEpoch++
            mutable.value = mutable.value.copy(phase = RecoveryPhase.LOGIN_REQUIRED, loginEvent = mutable.value.loginEvent + 1)
        }
    }

    internal suspend fun unauthorized(sent: AuthStateEntity): Boolean = invalidate(sent, null)
    internal suspend fun unauthorized(lease: SessionLease): Boolean = invalidate(lease.state, lease.epoch)
    private suspend fun invalidate(sent: AuthStateEntity, epoch: Long?): Boolean = withContext(NonCancellable) {
        mutex.withLock {
            if (epoch != null && epoch != credentialEpoch) return@withLock false
            try {
                val current = dao.getAuthState()
                if (mutable.value.phase == RecoveryPhase.CLEAR_FAILED || sent.jwt.isNullOrBlank() || current?.jwt != sent.jwt || current.userId != sent.userId ||
                current.tenantId != sent.tenantId || !current.isAuthenticated || current.reauthRequired) return@withLock false
                generation++ // In-flight OTP/device responses cannot resurrect this generation.
                credentialEpoch++
                mutable.value = mutable.value.copy(phase = RecoveryPhase.EXPIRY_DETECTED)
                if (!invalidateStored(sent.jwt)) {
                    mutable.value = mutable.value.copy(phase = RecoveryPhase.CLEAR_FAILED)
                    return@withLock false
                }
                mutable.value = mutable.value.copy(phase = RecoveryPhase.SESSION_CLEARED)
                mutable.value = mutable.value.copy(phase = RecoveryPhase.LOGIN_REQUIRED, loginEvent = mutable.value.loginEvent + 1)
                true
            } catch (e: CancellationException) {
                mutable.value = mutable.value.copy(phase = RecoveryPhase.CLEAR_FAILED)
                throw e
            } catch (_: Exception) {
                // Do not claim durable logout if storage did not confirm it. Block further protected calls.
                mutable.value = mutable.value.copy(phase = RecoveryPhase.CLEAR_FAILED)
                false
            }
        }
    }

    internal suspend fun beginAuthentication(): AuthAttempt = mutex.withLock {
        check(mutable.value.phase != RecoveryPhase.CLEAR_FAILED) { "LOCAL_SESSION_STORAGE_UNAVAILABLE" }
        restoreMarker(dao.getAuthState())
        generation++
        if (dao.getAuthState()?.reauthRequired == true)
            mutable.value = mutable.value.copy(phase = RecoveryPhase.REAUTHENTICATING)
        AuthAttempt(generation)
    }

    internal suspend fun completeAuthentication(attempt: AuthAttempt, fresh: AuthStateEntity): Boolean =
        completeAuthenticationResult(attempt, fresh) == AuthenticationCompletion.ACCEPTED

    internal suspend fun completeAuthenticationResult(attempt: AuthAttempt, fresh: AuthStateEntity): AuthenticationCompletion =
        completeAuthenticationDecision(attempt, fresh).completion

    internal suspend fun completeAuthenticationDecision(attempt: AuthAttempt, fresh: AuthStateEntity): AuthenticationDecision = withContext(NonCancellable) { mutex.withLock {
        if (mutable.value.phase == RecoveryPhase.CLEAR_FAILED) return@withLock AuthenticationDecision(AuthenticationCompletion.STORAGE_BLOCKED)
        if (attempt.generation != generation) return@withLock AuthenticationDecision(AuthenticationCompletion.STALE_ATTEMPT)
        if (!fresh.isAuthenticated || fresh.reauthRequired ||
            fresh.jwt.isNullOrBlank() || fresh.userId.isNullOrBlank() || fresh.tenantId.isNullOrBlank())
            return@withLock AuthenticationDecision(AuthenticationCompletion.INVALID_IDENTITY)
        val checkpoint = dao.getAuthState()
        // Capture only equality results from the exact checkpoint used for the
        // decision, under the same mutex. No second diagnostic database read.
        val comparison = if (checkpoint?.reauthRequired == true) RecoveryIdentityComparison(
            checkpoint.userId == fresh.userId, checkpoint.tenantId == fresh.tenantId, checkpoint.role == fresh.role) else null
        if (comparison != null && (!comparison.userMatches || !comparison.tenantMatches || !comparison.roleMatches))
            return@withLock AuthenticationDecision(AuthenticationCompletion.RECOVERY_IDENTITY_MISMATCH, comparison)
        if (checkpoint?.isAuthenticated == true &&
            (checkpoint.userId != fresh.userId || checkpoint.tenantId != fresh.tenantId)) {
            try { holdDeparting(checkpoint) }
            catch (e: CancellationException) {
                mutable.value = mutable.value.copy(phase = RecoveryPhase.CLEAR_FAILED)
                throw e
            }
            catch (_: Exception) {
                mutable.value = mutable.value.copy(phase = RecoveryPhase.CLEAR_FAILED)
                return@withLock AuthenticationDecision(AuthenticationCompletion.STORAGE_BLOCKED)
            }
        }
        dao.saveAuthState(fresh)
        generation++
        credentialEpoch++
        mutable.value = mutable.value.copy(phase = RecoveryPhase.AUTHENTICATED_RESTORED,
            successEvent = mutable.value.successEvent + 1, recovered = checkpoint?.reauthRequired == true)
        AuthenticationDecision(AuthenticationCompletion.ACCEPTED)
    } }

    internal suspend fun authenticationFailed(attempt: AuthAttempt) = mutex.withLock {
        if (attempt.generation == generation && dao.getAuthState()?.reauthRequired == true)
            mutable.value = mutable.value.copy(phase = RecoveryPhase.LOGIN_REQUIRED)
    }

    suspend fun cancelAuthentication() = mutex.withLock {
        generation++
        restoreMarker(dao.getAuthState())
        if (dao.getAuthState()?.reauthRequired == true)
            mutable.value = mutable.value.copy(phase = RecoveryPhase.LOGIN_REQUIRED)
    }

    suspend fun logout() = mutex.withLock {
        generation++
        credentialEpoch++
        try {
            dao.getAuthState()?.let { holdDeparting(it) }
            dao.clearAuth()
        } catch (e: CancellationException) {
            mutable.value = mutable.value.copy(phase = RecoveryPhase.CLEAR_FAILED)
            throw e
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(phase = RecoveryPhase.CLEAR_FAILED)
            error("LOCAL_SESSION_HOLD_NOT_CONFIRMED")
        }
        mutable.value = mutable.value.copy(phase = RecoveryPhase.NORMAL)
    }

    suspend fun claimLoginNavigation(event: Long): Boolean = mutex.withLock {
        if (event <= claimedLogin || event != mutable.value.loginEvent || mutable.value.phase !in
            setOf(RecoveryPhase.LOGIN_REQUIRED, RecoveryPhase.REAUTHENTICATING)) false
        else { claimedLogin = event; true }
    }

    suspend fun claimSuccessNavigation(event: Long): Boolean = mutex.withLock {
        if (event <= claimedSuccess || event != mutable.value.successEvent || mutable.value.phase != RecoveryPhase.AUTHENTICATED_RESTORED) false
        else { claimedSuccess = event; true }
    }

    override fun toString() = "SessionExpiry(${mutable.value.phase.name})"

    companion object {
        private val owners = WeakHashMap<AuthDao, WeakReference<SessionExpiryCoordinator>>()
        /** Bound once by Application; isolated in-memory clients have no production owner. */
        @Synchronized fun attach(dao: AuthDao, invalidate: suspend (String) -> Boolean = dao::invalidateSession,
            holdDeparting: suspend (AuthStateEntity) -> Unit = {}): SessionExpiryCoordinator =
            registeredFor(dao) ?: SessionExpiryCoordinator(dao, invalidate, holdDeparting).also { owners[dao] = WeakReference(it) }
        @Synchronized fun registeredFor(dao: AuthDao): SessionExpiryCoordinator? = owners[dao]?.get()
    }
}
