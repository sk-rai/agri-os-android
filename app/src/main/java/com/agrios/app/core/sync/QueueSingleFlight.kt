package com.agrios.app.core.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap

/** One boundary for the application's Room queue, stronger than per-account locking.
 * Does not retain credentials. A new process gets a new coordinator and recovers
 * abandoned SYNCING rows once, before any new submission can start.
 */
class QueueSingleFlight {
    private val mutex = Mutex()
    private val recovered = IdentityHashMap<Any, Boolean>()

    suspend fun <T> run(queue: Any, recover: suspend () -> Unit, block: suspend () -> T): T = mutex.withLock {
        if (recovered[queue] != true) {
            recover()
            recovered[queue] = true
        }
        block()
    }

    companion object { val applicationQueue = QueueSingleFlight() }
}

/** Per-form intent, remembered across recompositions. Successful local transactions
 * are never repeated, even if scheduling throws afterwards. Failed transactions
 * may be explicitly retried; their Room writes have rolled back.
 */
class ProfileSubmissionOnce {
    private val mutex = Mutex()
    private var committedId: String? = null
    suspend fun submit(transaction: suspend () -> String): String = mutex.withLock {
        committedId ?: withContext(NonCancellable) { transaction().also { committedId = it } }
    }
}
