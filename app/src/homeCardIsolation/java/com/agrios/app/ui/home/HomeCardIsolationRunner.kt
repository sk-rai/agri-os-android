package com.agrios.app.ui.home

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

class HomeCardIsolationRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        check(context.packageName == TARGET && className == Application::class.java.name) {
            "HOME_CARD_TARGET_APPLICATION_MISMATCH"
        }
        return super.newApplication(cl, Application::class.java.name, context)
    }

    override fun onCreate(arguments: Bundle) {
        val filter = arguments.getString("class")
        check(filter == TEST_CLASS || filter == CYCLE_TEST_CLASS || filter == QUEUE_TEST_CLASS ||
            filter == SERVER_PROFILE_TEST_CLASS) {
            "HOME_CARD_EXACT_FILTER_REQUIRED"
        }
        super.onCreate(arguments)
    }

    private companion object {
        const val TARGET = "com.agrios.app.homecard"
        const val TEST_CLASS = "com.agrios.app.ui.home.HomeOwnedFarmerCardTest"
        const val CYCLE_TEST_CLASS = "com.agrios.app.ui.home.HomeCropCyclePanelTest"
        const val QUEUE_TEST_CLASS = "com.agrios.app.ui.home.HomeQueuePanelTest"
        const val SERVER_PROFILE_TEST_CLASS = "com.agrios.app.ui.home.HomeServerProfilePanelTest"
    }
}
