package com.agrios.app.data.repository

import androidx.room.withTransaction
import com.agrios.app.core.database.AppDatabase
import com.agrios.app.core.sync.QueueDispatchLease
import com.agrios.app.data.local.entity.SelfProfileCacheFarmerEntity
import com.agrios.app.data.local.entity.SelfProfileCacheParcelEntity
import com.agrios.app.data.local.entity.SelfProfileCacheSoilEntity

/** A projection, not local authoring data or an authoritative listing. */
internal data class SelfProfileProjection(
    val farmer: SelfProfileCacheFarmerEntity,
    val parcels: List<SelfProfileCacheParcelEntity>,
    val soil: List<SelfProfileCacheSoilEntity>
)

internal enum class CacheCommit { APPLIED, UNCHANGED, INVALID, CONFLICT, SESSION_CHANGED }

/** Storage only. The future caller must validate server identity before constructing projections. */
internal class SelfProfileCacheStore(private val db: AppDatabase) {
    private class Collision : IllegalStateException()

    suspend fun read(origin: QueueDispatchLease, farmerId: String): SelfProfileProjection? {
        val coordinator = db.sessionExpiry
        if (origin.coordinator !== coordinator || farmerId.isBlank()) return null
        val tenant = origin.selected.state.tenantId ?: return null
        val user = origin.selected.state.userId ?: return null
        return coordinator.withCurrentLease(origin.selected) {
            db.withTransaction {
                val dao = db.selfProfileCacheDao()
                dao.farmer(tenant, user, farmerId)?.let {
                    SelfProfileProjection(it, dao.parcels(tenant, user, farmerId), dao.soil(tenant, user, farmerId))
                }
            }
        }
    }

    suspend fun commit(origin: QueueDispatchLease, projection: SelfProfileProjection): CacheCommit {
        val coordinator = db.sessionExpiry
        if (origin.coordinator !== coordinator) return CacheCommit.SESSION_CHANGED
        val tenant = origin.selected.state.tenantId ?: return CacheCommit.SESSION_CHANGED
        val user = origin.selected.state.userId ?: return CacheCommit.SESSION_CHANGED
        val farmer = projection.farmer
        val parcels = projection.parcels.toList()
        val soil = projection.soil.toList()
        val parcelIds = parcels.map { it.parcelId }.toSet()
        if (tenant.isBlank() || user.isBlank() ||
            farmer.tenantId != tenant || farmer.userId != user || farmer.farmerId.isBlank() ||
            parcelIds.size != parcels.size || soil.map { it.soilProfileId }.toSet().size != soil.size ||
            parcels.any {
                it.tenantId != tenant || it.userId != user || it.farmerId != farmer.farmerId ||
                    it.parcelId.isBlank() || it.reportedArea?.isFinite() == false
            } || soil.any {
                it.tenantId != tenant || it.userId != user || it.farmerId != farmer.farmerId ||
                    it.parcelId !in parcelIds || it.soilProfileId.isBlank() || it.ph?.isFinite() == false
            }
        ) return CacheCommit.INVALID

        // SQLite stores both zero signs as the same numeric value.
        val normalizedParcels = parcels.map { it.copy(reportedArea = it.reportedArea?.let(::normalizeZero)) }
        val normalizedSoil = soil.map { it.copy(ph = it.ph?.let(::normalizeZero)) }
        return try {
            coordinator.withCurrentLease(origin.selected) {
                db.withTransaction {
                    val dao = db.selfProfileCacheDao()
                    var inserted = false
                    suspend fun <T> retain(incoming: T, existing: T?, insert: suspend (T) -> Unit) {
                        if (existing == null) {
                            insert(incoming)
                            inserted = true
                        } else if (existing != incoming) {
                            throw Collision()
                        }
                    }
                    retain(farmer, dao.farmer(tenant, user, farmer.farmerId), dao::insertFarmer)
                    for (parcel in normalizedParcels) {
                        retain(parcel, dao.parcelForCommit(tenant, user, parcel.parcelId), dao::insertParcel)
                    }
                    for (row in normalizedSoil) {
                        retain(row, dao.soilForCommit(tenant, user, row.soilProfileId), dao::insertSoil)
                    }
                    if (inserted) CacheCommit.APPLIED else CacheCommit.UNCHANGED
                }
            } ?: CacheCommit.SESSION_CHANGED
        } catch (_: Collision) {
            CacheCommit.CONFLICT
        }
    }

    private fun normalizeZero(value: Double) = if (value == 0.0) 0.0 else value
}
