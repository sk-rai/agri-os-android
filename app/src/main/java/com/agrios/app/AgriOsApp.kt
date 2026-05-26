package com.agrios.app

import android.app.Application
import com.agrios.app.core.database.AppDatabase
import com.agrios.app.core.sync.SyncWorker
import com.agrios.app.core.util.LanguageManager

class AgriOsApp : Application() {

    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize language manager
        LanguageManager.init(this)

        // Initialize database
        database = AppDatabase.getInstance(this)

        // Start periodic background sync
        SyncWorker.enqueuePeriodicSync(this)
    }

    companion object {
        lateinit var instance: AgriOsApp
            private set
    }
}
