package com.agrios.app.core.sync

import android.content.Context
import com.agrios.app.core.database.AppDatabase

/** Legacy local entities do not carry a proven tenant/user source owner.
 * Never manufacture new outbound events from them under the current login.
 * Existing queue rows and repair preferences are deliberately left untouched.
 */
object ProfileSyncRepair {
    @Suppress("UNUSED_PARAMETER")
    suspend fun enqueueOneTimeMaterializationRepair(context: Context, db: AppDatabase): Int = 0
}
