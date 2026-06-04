package com.agrios.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.agrios.app.data.local.dao.AuthDao
import com.agrios.app.data.local.dao.GeographyCacheDao
import com.agrios.app.data.local.dao.SyncQueueDao
import com.agrios.app.data.local.dao.FarmerDao
import com.agrios.app.data.local.dao.ParcelDao
import com.agrios.app.data.local.dao.SoilProfileDao
import com.agrios.app.data.local.entity.*

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
    ],
    version = 6,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun authDao(): AuthDao
    abstract fun geographyCacheDao(): GeographyCacheDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun farmerDao(): FarmerDao
    abstract fun parcelDao(): ParcelDao
    abstract fun soilProfileDao(): SoilProfileDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "agrios_local.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
