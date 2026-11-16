package com.agrios.app.core.sync

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import com.agrios.app.core.database.AppDatabase
import com.agrios.app.data.local.entity.AuthStateEntity
import com.agrios.app.data.local.entity.FarmerEntity
import com.agrios.app.data.local.entity.SelfProfileCacheFarmerEntity
import com.agrios.app.data.local.entity.SelfProfileCacheParcelEntity
import com.agrios.app.data.local.entity.SelfProfileCacheSoilEntity
import com.agrios.app.data.local.entity.SyncQueueEntity
import com.agrios.app.data.repository.CacheCommit
import com.agrios.app.data.repository.SelfProfileCacheStore
import com.agrios.app.data.repository.SelfProfileProjection
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class SelfProfileCacheRoomHostTest {
    @Test fun partialRepeatPreservesChildrenAndLocalAuthoring() = exercise { db, store ->
        val lease = login(db)
        val unit = projection()
        val local = FarmerEntity("farmer", "synthetic-not-a-phone", "village", displayName = "Local draft",
            createdAt = 1, updatedAt = 2, actorId = "fixture-user")
        val queued = SyncQueueEntity("fixture-event", "FARMER", "farmer", "CREATE", "{}", createdAt = 3)
        db.farmerDao().insertRetained(local)
        db.syncQueueDao().insertRetained(queued)
        val before = authoringSnapshot(db)
        check(store.commit(lease, unit) == CacheCommit.APPLIED)
        check(store.commit(lease, unit) == CacheCommit.UNCHANGED)
        check(store.commit(lease, unit.copy(parcels = emptyList(), soil = emptyList())) == CacheCommit.UNCHANGED)
        check(store.read(lease, "farmer") == unit && store.read(lease, "other") == null)
        check(authoringSnapshot(db) == before)
    }

    @Test fun staleAndWrongOwnerLeasesCannotReadOrCommit() = exercise { db, store ->
        val a = login(db)
        val unit = projection()
        check(store.commit(a, unit) == CacheCommit.APPLIED)
        val b = login(db, user = "other-user")
        check(store.read(a, "farmer") == null && store.read(b, "farmer") == null)
        check(db.selfProfileCacheDao().farmer("fixture-tenant", "fixture-user", "farmer") == null)
        check(db.selfProfileCacheDao().parcels("fixture-tenant", "fixture-user", "farmer").isEmpty())
        check(db.selfProfileCacheDao().soil("fixture-tenant", "fixture-user", "farmer").isEmpty())
        check(store.commit(a, unit) == CacheCommit.SESSION_CHANGED)
        check(store.commit(b, unit) == CacheCommit.INVALID)
        val c = login(db, tenant = "other-tenant")
        check(store.read(c, "farmer") == null && store.commit(a, unit) == CacheCommit.SESSION_CHANGED)
        check(store.commit(c, unit) == CacheCommit.INVALID)
        val aAgain = login(db)
        val newEpoch = login(db)
        check(store.read(aAgain, "farmer") == null && store.commit(aAgain, unit) == CacheCommit.SESSION_CHANGED)
        check(store.read(newEpoch, "farmer") == unit)
    }

    @Test fun lateSoilCollisionRollsBackEarlierParcelAndRejectsReparent() = exercise { db, store ->
        val lease = login(db)
        val unit = projection()
        check(store.commit(lease, unit) == CacheCommit.APPLIED)
        val sibling = unit.parcels.single().copy(parcelId = "sibling")
        val changed = unit.soil.single().copy(ph = 7.0)
        check(store.commit(lease, unit.copy(parcels = unit.parcels + sibling, soil = listOf(changed))) == CacheCommit.CONFLICT)
        check(store.read(lease, "farmer") == unit)
        val reparent = unit.copy(farmer = unit.farmer.copy(farmerId = "other"),
            parcels = unit.parcels.map { it.copy(farmerId = "other") }, soil = emptyList())
        check(store.commit(lease, reparent) == CacheCommit.CONFLICT)
        check(store.read(lease, "other") == null && store.read(lease, "farmer") == unit)
        check(store.commit(lease, unit.copy(parcels = emptyList())) == CacheCommit.INVALID)
        check(store.read(lease, "farmer") == unit)
    }

    @Test fun generatedInsertsEnforceOwnerForeignKeysAndUniqueChildIds() = exercise { db, store ->
        val lease = login(db)
        val unit = projection()
        check(store.commit(lease, unit) == CacheCommit.APPLIED)
        val dao = db.selfProfileCacheDao()
        rejected { dao.insertParcel(unit.parcels.single().copy(userId = "other-user")) }
        rejected { dao.insertSoil(unit.soil.single().copy(tenantId = "other-tenant")) }
        dao.insertFarmer(unit.farmer.copy(farmerId = "other"))
        rejected { dao.insertParcel(unit.parcels.single().copy(farmerId = "other")) }
        dao.insertParcel(unit.parcels.single().copy(parcelId = "other-parcel"))
        rejected { dao.insertSoil(unit.soil.single().copy(parcelId = "other-parcel")) }
        check(store.read(lease, "farmer")?.soil == unit.soil)
        check(store.read(lease, "other")?.parcels?.isEmpty() == true)
    }

    @Test fun lateInsertFailureRollsBackWholeProjection() = exercise { db, store ->
        val lease = login(db)
        val unit = projection()
        val sql = db.openHelper.writableDatabase
        sql.execSQL("""CREATE TRIGGER reject_test_soil BEFORE INSERT ON self_profile_cache_soil
            BEGIN SELECT RAISE(ABORT, 'SYNTHETIC_SOIL_INSERT_REJECTED'); END""")
        val before = authoringSnapshot(db)
        try {
            check(store.commit(lease, unit.copy(soil = emptyList())) == CacheCommit.APPLIED)
            val newUnit = projection().let { original ->
                original.copy(farmer = original.farmer.copy(farmerId = "other"),
                    parcels = original.parcels.map { it.copy(farmerId = "other", parcelId = "other-parcel") },
                    soil = original.soil.map { it.copy(farmerId = "other", parcelId = "other-parcel") })
            }
            rejected { store.commit(lease, newUnit) }
            check(store.read(lease, "other") == null)
            check(store.read(lease, "farmer") == unit.copy(soil = emptyList()))
            check(authoringSnapshot(db) == before)
        } finally {
            sql.execSQL("DROP TRIGGER reject_test_soil")
        }
    }

    private fun exercise(block: suspend (AppDatabase, SelfProfileCacheStore) -> Unit) = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        check(app.javaClass == Application::class.java)
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            block(db, SelfProfileCacheStore(db))
        } finally {
            db.close()
        }
    }

    private suspend fun rejected(insert: suspend () -> Unit) {
        try {
            insert()
        } catch (_: SQLiteConstraintException) {
            return
        }
        error("CACHE_CONSTRAINT_NOT_ENFORCED")
    }

    private suspend fun login(db: AppDatabase, tenant: String = "fixture-tenant", user: String = "fixture-user"): QueueDispatchLease {
        val coordinator = db.sessionExpiry
        val state = AuthStateEntity(jwt = "synthetic-cache-session", deviceKey = null, userId = user,
            tenantId = tenant, role = "FARMER", mobileNumber = null, isAuthenticated = true)
        check(coordinator.completeAuthentication(coordinator.beginAuthentication(), state))
        return QueueDispatchLease(coordinator, checkNotNull(coordinator.requestLease()))
    }

    private fun projection(): SelfProfileProjection {
        val farmer = SelfProfileCacheFarmerEntity("fixture-tenant", "fixture-user", "farmer", "Remote profile", null)
        val parcel = SelfProfileCacheParcelEntity(farmer.tenantId, farmer.userId, farmer.farmerId, "parcel", null, "BIGHA", 1.5)
        val soil = SelfProfileCacheSoilEntity(farmer.tenantId, farmer.userId, farmer.farmerId, "parcel", "soil", null, 6.5)
        return SelfProfileProjection(farmer, listOf(parcel), listOf(soil))
    }

    private fun authoringSnapshot(db: AppDatabase) =
        listOf("farmers_local", "parcels_local", "soil_profiles_local", "sync_queue").associateWith { table ->
            db.openHelper.writableDatabase.query("SELECT * FROM $table").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(List(cursor.columnCount) { i -> cursor.getType(i) to cursor.getString(i) })
                }
            }
        }
}
