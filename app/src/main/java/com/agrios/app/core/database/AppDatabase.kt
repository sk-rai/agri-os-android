package com.agrios.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.agrios.app.data.local.dao.AuthDao
import com.agrios.app.data.local.dao.GeographyCacheDao
import com.agrios.app.data.local.dao.SyncQueueDao
import com.agrios.app.data.local.dao.FarmerDao
import com.agrios.app.data.local.dao.ParcelDao
import com.agrios.app.data.local.dao.SoilProfileDao
import com.agrios.app.data.local.entity.*
import com.agrios.app.core.auth.SessionExpiryCoordinator
import com.agrios.app.core.sync.holdDepartingQueueRows
import com.agrios.app.core.sync.invalidateSessionWithQueueHold

@Database(
    entities = [
        AuthStateEntity::class,
        GeographyStateEntity::class,
        GeographyDistrictEntity::class,
        GeographyBlockEntity::class,
        GeographyVillageEntity::class,
        CropCategoryEntity::class,
        CropEntity::class,
        SyncQueueEntity::class,
        FarmerEntity::class,
        ParcelEntity::class,
        CacheMetadataEntity::class,
        SoilProfileEntity::class,
        SelfProfileCacheFarmerEntity::class,
        SelfProfileCacheParcelEntity::class,
        SelfProfileCacheSoilEntity::class,
    ],
    version = 11,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    // Bind before publishing the singleton, including a cold WorkManager start
    // which can precede Application.onCreate. In-memory test DBs remain explicit.
    val sessionExpiry: SessionExpiryCoordinator by lazy {
        SessionExpiryCoordinator.attach(authDao(), this::invalidateExpiredSession, this::holdDepartingQueueOwner)
    }
    abstract fun authDao(): AuthDao
    abstract fun geographyCacheDao(): GeographyCacheDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun farmerDao(): FarmerDao
    abstract fun parcelDao(): ParcelDao
    abstract fun soilProfileDao(): SoilProfileDao

    suspend fun invalidateExpiredSession(expectedJwt: String): Boolean = withTransaction {
        invalidateSessionWithQueueHold(authDao(), syncQueueDao(), expectedJwt)
    }

    private suspend fun holdDepartingQueueOwner(departing: AuthStateEntity) = withTransaction {
        check(authDao().getAuthState() == departing) { "LOCAL_SESSION_OWNER_CHANGED" }
        holdDepartingQueueRows(syncQueueDao(), departing)
    }

    companion object {
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE `self_profile_cache_farmer` (
                        `tenant_id` TEXT NOT NULL, `user_id` TEXT NOT NULL, `farmer_id` TEXT NOT NULL,
                        `display_name` TEXT, `village_name_manual` TEXT,
                        PRIMARY KEY(`tenant_id`, `user_id`, `farmer_id`))"""
                )
                db.execSQL(
                    """CREATE TABLE `self_profile_cache_parcel` (
                        `tenant_id` TEXT NOT NULL, `user_id` TEXT NOT NULL, `farmer_id` TEXT NOT NULL,
                        `parcel_id` TEXT NOT NULL, `survey_number` TEXT, `reported_area_unit` TEXT, `reported_area` REAL,
                        PRIMARY KEY(`tenant_id`, `user_id`, `farmer_id`, `parcel_id`),
                        FOREIGN KEY(`tenant_id`, `user_id`, `farmer_id`)
                        REFERENCES `self_profile_cache_farmer`(`tenant_id`, `user_id`, `farmer_id`)
                        ON UPDATE NO ACTION ON DELETE NO ACTION)"""
                )
                db.execSQL(
                    """CREATE UNIQUE INDEX `index_self_profile_cache_parcel_tenant_id_user_id_parcel_id`
                        ON `self_profile_cache_parcel`(`tenant_id`, `user_id`, `parcel_id`)"""
                )
                db.execSQL(
                    """CREATE TABLE `self_profile_cache_soil` (
                        `tenant_id` TEXT NOT NULL, `user_id` TEXT NOT NULL, `farmer_id` TEXT NOT NULL,
                        `parcel_id` TEXT NOT NULL, `soil_profile_id` TEXT NOT NULL,
                        `soil_type_code` TEXT, `ph` REAL,
                        PRIMARY KEY(`tenant_id`, `user_id`, `farmer_id`, `parcel_id`, `soil_profile_id`),
                        FOREIGN KEY(`tenant_id`, `user_id`, `farmer_id`, `parcel_id`)
                        REFERENCES `self_profile_cache_parcel`(`tenant_id`, `user_id`, `farmer_id`, `parcel_id`)
                        ON UPDATE NO ACTION ON DELETE NO ACTION)"""
                )
                db.execSQL(
                    """CREATE UNIQUE INDEX `index_self_profile_cache_soil_tenant_id_user_id_soil_profile_id`
                        ON `self_profile_cache_soil`(`tenant_id`, `user_id`, `soil_profile_id`)"""
                )
            }
        }
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_queue ADD COLUMN dispatch_claim_id TEXT")
            }
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No active-login inference or backfill: pre-existing sources stay unproven.
                for (table in listOf("farmers_local", "parcels_local", "soil_profiles_local")) {
                    db.execSQL("ALTER TABLE $table ADD COLUMN origin_tenant_id TEXT")
                    db.execSQL("ALTER TABLE $table ADD COLUMN origin_user_id TEXT")
                }
            }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // NULL owners quarantine legacy rows without altering their retained history.
                db.execSQL("ALTER TABLE sync_queue ADD COLUMN origin_tenant_id TEXT")
                db.execSQL("ALTER TABLE sync_queue ADD COLUMN origin_user_id TEXT")
                db.execSQL("ALTER TABLE sync_queue ADD COLUMN dispatch_reserved INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE auth_state ADD COLUMN reauth_required INTEGER NOT NULL DEFAULT 0")
            }
        }
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "agrios_local.db"
                )
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                    .build()
                instance.sessionExpiry
                INSTANCE = instance
                instance
            }
        }
    }
}
