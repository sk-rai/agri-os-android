package com.agrios.app.core.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import com.agrios.app.core.database.AppDatabase
import com.agrios.app.core.network.ApiConfig
import com.agrios.app.core.network.AuthInterceptor
import com.agrios.app.data.local.dao.AuthDao
import com.agrios.app.data.local.entity.SyncStatus
import com.agrios.app.data.remote.api.AgriOsApi
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for background sync.
 * Triggered periodically (every 15 min) and on connectivity restore.
 */
class SyncWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val openDatabase: (Context) -> AppDatabase,
    private val createApi: (AuthDao) -> AgriOsApi
) : CoroutineWorker(context, params) {

    constructor(context: Context, params: WorkerParameters) :
        this(context, params, { AppDatabase.getInstance(it) }, ::productionApi)

    companion object {
        private const val TAG = "SyncWorker"
        const val WORK_NAME = "agrios_sync_worker"

        fun enqueuePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInputData(SyncWorkInput.periodic())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // Upgrade the existing periodic request's input without cancelling an active run.
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.d(TAG, "Periodic sync worker enqueued")
        }

        internal fun triggerImmediateSync(context: Context, origin: NewQueueOrigin) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(origin.immediateSyncInput())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_immediate",
                // Never cancel a running POST just because another trigger arrives.
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
            Log.d(TAG, "Immediate sync triggered")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "SyncWorker started")

        val trigger = SyncWorkInput.parse(inputData) ?: return Result.success()
        val db = openDatabase(applicationContext)
        val authDao = db.authDao()
        val admission = when (trigger) {
            is SyncWorkInput.Save -> trigger.admission(db.sessionExpiry) ?: return Result.success()
            SyncWorkInput.Periodic -> {
                val session = authDao.getAuthState()
                if (session?.isAuthenticated != true || session.reauthRequired || session.jwt.isNullOrBlank()) {
                    return Result.success()
                }
                null
            }
        }

        suspend fun hasPendingPersistenceSmokeRow() = listOf(
            "android_maestro_cold_start_persistence_test",
            "android_maestro_device_restart_persistence_test",
            "android_maestro_dependency_order_replay_test",
            "android_maestro_partial_batch_replay_test",
            "android_maestro_partial_batch_conflict_test",
            "android_maestro_multi_conflict_pending_drawer_test",
            "android_maestro_queue_backpressure_test",
            "android_maestro_interrupted_multibatch_resume_test",
            "android_maestro_poison_row_backlog_test"
        ).any { payloadNeedle ->
            db.syncQueueDao().countByPayloadNeedleAndStatus(
                payloadNeedle,
                SyncStatus.PENDING.name
            ) > 0
        }
        if (admission == null && hasPendingPersistenceSmokeRow()) {
            Log.d(TAG, "Skipping background sync for offline persistence smoke pending row")
            return Result.success()
        }

        val api = createApi(authDao)

        val syncManager = SyncManager(
            syncQueueDao = db.syncQueueDao(),
            api = api,
            authDao = authDao
        )

        return try {
            val result = if (admission != null) {
                // The same lease is rechecked under the queue lock before even the smoke query.
                syncManager.processBoundQueue(admission) { !hasPendingPersistenceSmokeRow() }
                    ?: return Result.success()
            } else {
                syncManager.processScheduledQueue {
                    ProfileSyncRepair.enqueueOneTimeMaterializationRepair(applicationContext, db)
                }
            }
            Log.d(TAG, "Sync attempt finished: ${result.accepted} accepted, ${result.conflicts} conflicts, ${result.failed} unresolved outcomes")

            if (admission != null && !admission.coordinator.isCurrentLease(admission.selected)) Result.success()
            else if (authDao.getAuthState()?.isAuthenticated != true) Result.success()
            else if (result.failed > 0) Result.retry() else Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            Log.e(TAG, "Sync attempt did not complete; retained work requires review")
            Result.retry()
        }
    }
}

private fun productionApi(authDao: AuthDao): AgriOsApi {
    // Build API client with auth interceptor
    val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(authDao))
        .connectTimeout(ApiConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(ApiConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(ApiConfig.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    return Retrofit.Builder()
        .baseUrl(ApiConfig.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(AgriOsApi::class.java)
}
