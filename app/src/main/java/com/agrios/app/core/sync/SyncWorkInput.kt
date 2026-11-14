package com.agrios.app.core.sync

import androidx.work.Data
import com.agrios.app.core.auth.SessionExpiryCoordinator

/** Persist only noncredential routing data. A coordinator restart invalidates saved triggers. */
internal sealed interface SyncWorkInput {
    data object Periodic : SyncWorkInput

    class Save(private val tenant: String, private val user: String, private val instance: String,
               private val epoch: Long) : SyncWorkInput {
        suspend fun admission(coordinator: SessionExpiryCoordinator): QueueDispatchLease? {
            if (coordinator.workSessionInstance != instance) return null
            val lease = coordinator.requestLease() ?: return null
            if (lease.epoch != epoch || lease.state.tenantId != tenant || lease.state.userId != user) return null
            return QueueDispatchLease(coordinator, lease)
        }

        override fun toString() = "SyncWorkInput.Save(REDACTED)"
    }

    companion object {
        private const val TYPE = "sync_trigger"
        private const val TENANT = "origin_tenant_id"
        private const val USER = "origin_user_id"
        private const val INSTANCE = "session_instance"
        private const val EPOCH = "security_epoch"
        private val saveKeys = setOf(TYPE, TENANT, USER, INSTANCE, EPOCH)

        fun periodic() = Data.Builder().putString(TYPE, "periodic").build()

        fun forSave(coordinator: SessionExpiryCoordinator, lease: com.agrios.app.core.auth.SessionLease) =
            Data.Builder().putString(TYPE, "save")
                .putString(TENANT, checkNotNull(lease.state.tenantId))
                .putString(USER, checkNotNull(lease.state.userId))
                .putString(INSTANCE, coordinator.workSessionInstance)
                .putLong(EPOCH, lease.epoch).build()

        fun parse(data: Data): SyncWorkInput? {
            val values = data.keyValueMap
            return when (values[TYPE]) {
                "periodic" -> Periodic.takeIf { values.keys == setOf(TYPE) }
                "save" -> {
                    if (values.keys != saveKeys) return null
                    val tenant = (values[TENANT] as? String)?.takeIf { it.isNotBlank() } ?: return null
                    val user = (values[USER] as? String)?.takeIf { it.isNotBlank() } ?: return null
                    val instance = (values[INSTANCE] as? String)?.takeIf { it.isNotBlank() } ?: return null
                    val epoch = (values[EPOCH] as? Long)?.takeIf { it >= 0 } ?: return null
                    Save(tenant, user, instance, epoch)
                }
                else -> null
            }
        }
    }
}
