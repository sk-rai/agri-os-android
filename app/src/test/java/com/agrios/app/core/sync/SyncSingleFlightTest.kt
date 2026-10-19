package com.agrios.app.core.sync

import com.agrios.app.data.local.dao.SyncQueueDao
import com.agrios.app.data.local.entity.SyncQueueEntity
import com.agrios.app.data.remote.api.AgriOsApi
import com.agrios.app.data.remote.dto.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class SyncSingleFlightTest {
    private class Store {
        val rows = linkedMapOf<String, SyncQueueEntity>()
        var now = 100_000L
        var pendingReads = 0
        var dependencyGate: CompletableDeferred<Unit>? = null
        val dao: SyncQueueDao = Proxy.newProxyInstance(SyncQueueDao::class.java.classLoader,
            arrayOf(SyncQueueDao::class.java)) { _, method, args ->
            val a = args.orEmpty()
            fun change(id: String, update: (SyncQueueEntity) -> SyncQueueEntity) { rows[id]?.let { rows[id] = update(it) } }
            when (method.name) {
                "getPendingBatch" -> { pendingReads++; rows.values.filter { it.syncStatus == "PENDING" && (it.nextRetryAfter ?: 0) <= a[0] as Long && it.retryCount < it.maxRetries }
                    .sortedBy { it.createdAt }.take(a[1] as Int) }
                "recoverInterruptedSubmissions" -> { rows.keys.toList().forEach { id -> change(id) { if (it.syncStatus == "SYNCING") it.copy(syncStatus = "PENDING") else it } }; Unit }
                "getAllForDependencyCheck" -> {
                    val gate = dependencyGate
                    if (gate == null) rows.values.toList() else {
                        @Suppress("UNCHECKED_CAST")
                        val continuation = a.last() as Continuation<List<SyncQueueEntity>>
                        val read: suspend () -> List<SyncQueueEntity> = { gate.await(); rows.values.toList() }
                        read.startCoroutine(continuation)
                        COROUTINE_SUSPENDED
                    }
                }
                "getFailedItems" -> rows.values.filter { it.syncStatus == "FAILED" }
                "enqueue" -> { val row = a[0] as SyncQueueEntity; rows.putIfAbsent(row.eventId, row); Unit }
                "updateStatus" -> { change(a[0] as String) { it.copy(syncStatus = a[1] as String) }; Unit }
                "markSynced" -> { change(a[0] as String) { it.copy(syncStatus = "SYNCED") }; Unit }
                "markForRetry" -> { change(a[0] as String) { if (it.syncStatus == "SYNCING") it.copy(syncStatus = "PENDING", retryCount = it.retryCount + 1, nextRetryAfter = a[1] as Long, lastError = a[2] as String?) else it }; Unit }
                "markFailed" -> { change(a[0] as String) { if (it.syncStatus != "SYNCED") it.copy(syncStatus = "FAILED", lastError = a[1] as String?) else it }; Unit }
                "resetFailedItem" -> { change(a[0] as String) { if (it.syncStatus == "FAILED") it.copy(syncStatus = "PENDING", retryCount = 0, nextRetryAfter = null) else it }; Unit }
                "updatePayload" -> { change(a[0] as String) { it.copy(payload = a[1] as String) }; Unit }
                "toString" -> "FakeQueue"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> error("Unexpected fake queue operation")
            }
        } as SyncQueueDao
        fun add(id: String, status: String = "PENDING") {
            rows[id] = SyncQueueEntity(id, "FARMER", "entity-$id", "CREATE", "{}", status, createdAt = rows.size.toLong())
        }
    }

    private class FakeApi(var action: suspend (SyncBatchRequestDto) -> Response<SyncBatchResponseDto> = { request ->
        Response.success(SyncBatchResponseDto(accepted = request.events.map { it.eventId }))
    }) {
        val calls = mutableListOf<List<String>>()
        var active = 0
        var maximumActive = 0
        val api = Proxy.newProxyInstance(AgriOsApi::class.java.classLoader, arrayOf(AgriOsApi::class.java)) { _, method, args ->
            check(method.name == "syncEvents")
            val request = args!![0] as SyncBatchRequestDto
            @Suppress("UNCHECKED_CAST")
            val continuation = args.last() as Continuation<Response<SyncBatchResponseDto>>
            val invocation: suspend () -> Response<SyncBatchResponseDto> = {
                calls += request.events.map { it.eventId }
                active++; maximumActive = maxOf(maximumActive, active)
                try { action(request) } finally { active-- }
            }
            invocation.startCoroutine(continuation)
            COROUTINE_SUSPENDED
        } as AgriOsApi
    }

    private fun manager(store: Store, fake: FakeApi, flight: QueueSingleFlight,
                        records: MutableList<SyncDiagnostic> = mutableListOf()) = SyncManager(
        store.dao, fake.api, singleFlight = flight, diagnostic = { records += it },
        clock = { store.now }, retryJitter = { 0 })

    @Test fun concurrentImmediateAndWorkerDrainSubmitEventOnce() = runTest {
        val store = Store().apply { add("event-1") }; val flight = QueueSingleFlight()
        val release = CompletableDeferred<Unit>()
        val fake = FakeApi { request -> release.await(); Response.success(SyncBatchResponseDto(accepted = request.events.map { it.eventId })) }
        val immediate = async { manager(store, fake, flight).processQueue() }
        runCurrent()
        var repairRuns = 0
        val worker = async { manager(store, fake, flight).processScheduledQueue { repairRuns++ } }
        runCurrent()
        assertEquals(0, repairRuns); assertEquals(1, fake.calls.size)
        release.complete(Unit); advanceUntilIdle()
        assertEquals(1, immediate.await().accepted); assertEquals(0, worker.await().accepted)
        assertEquals(1, repairRuns); assertEquals(1, fake.maximumActive)
        assertEquals(listOf(listOf("event-1")), fake.calls)
        assertEquals("SYNCED", store.rows["event-1"]!!.syncStatus)
    }

    @Test fun concurrentSelectionCannotReadSamePendingEventBeforeClaim() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = Store().apply { add("event"); dependencyGate = gate }
        val fake = FakeApi(); val flight = QueueSingleFlight()
        val first = async { manager(store, fake, flight).processQueue() }; runCurrent()
        assertEquals("PENDING", store.rows["event"]!!.syncStatus)
        val second = async { manager(store, fake, flight).processQueue() }; runCurrent()
        assertEquals(1, store.pendingReads); assertTrue(fake.calls.isEmpty())
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, first.await().accepted); assertEquals(0, second.await().accepted)
        assertEquals(listOf(listOf("event")), fake.calls)
    }

    @Test fun cancellationDoesNotReleaseLockBeforeHttpAcknowledgement() = runTest {
        val store = Store().apply { add("event-1") }; val flight = QueueSingleFlight()
        val release = CompletableDeferred<Unit>()
        val fake = FakeApi { request -> release.await(); Response.success(SyncBatchResponseDto(accepted = request.events.map { it.eventId })) }
        val first = launch { manager(store, fake, flight).processQueue() }
        runCurrent(); first.cancel()
        val second = async { manager(store, fake, flight).processQueue() }
        runCurrent(); assertEquals(1, fake.calls.size); assertFalse(first.isCompleted)
        release.complete(Unit); advanceUntilIdle(); first.join()
        assertEquals(0, second.await().accepted); assertEquals(1, fake.calls.size)
        assertEquals("SYNCED", store.rows["event-1"]!!.syncStatus)
    }

    @Test fun repeatedSaveActivationCreatesOneLocalEvent() = runTest {
        val submission = ProfileSubmissionOnce(); val store = Store()
        val release = CompletableDeferred<Unit>()
        var transactions = 0
        suspend fun save() = submission.submit { transactions++; release.await(); store.add("stable-event"); "stable-entity" }
        val first = async { save() }; val second = async { save() }
        runCurrent(); assertEquals(1, transactions)
        release.complete(Unit); advanceUntilIdle()
        assertEquals(first.await(), second.await()); assertEquals(1, store.rows.size)
        save(); assertEquals(1, transactions)
    }

    @Test fun pendingAndAbandonedWorkResumeWithSameIdsAfterRecreation() = runTest {
        val store = Store().apply { add("pending"); add("abandoned", "SYNCING"); add("committed", "SYNCED") }
        val fake = FakeApi()
        manager(store, fake, QueueSingleFlight()).processQueue()
        manager(store, fake, QueueSingleFlight()).processQueue()
        assertEquals(listOf(listOf("pending", "abandoned")), fake.calls)
        assertTrue(store.rows.values.all { it.syncStatus == "SYNCED" })
    }

    @Test fun transportUncertaintyRetainsOriginalEventUntilBackoffThenReplays() = runTest {
        val store = Store().apply { add("stable-event") }; val records = mutableListOf<SyncDiagnostic>()
        val flight = QueueSingleFlight(); val fake = FakeApi { throw IOException("synthetic-private-secret") }
        assertEquals(1, manager(store, fake, flight, records).processQueue().failed)
        assertEquals("PENDING", store.rows["stable-event"]!!.syncStatus)
        assertEquals("TRANSPORT_UNCERTAIN", store.rows["stable-event"]!!.lastError)
        manager(store, fake, flight).processQueue(); assertEquals(1, fake.calls.size)
        store.now += 60_000
        fake.action = { Response.success(SyncBatchResponseDto(accepted = it.events.map { event -> event.eventId })) }
        manager(store, fake, flight).processQueue()
        assertEquals(listOf(listOf("stable-event"), listOf("stable-event")), fake.calls)
        assertFalse(records.toString().contains("synthetic-private-secret"))
    }

    @Test fun permanentFailureIsNotAcceptedOrAutomaticallyRearmed() = runTest {
        val store = Store().apply { add("rejected") }; val flight = QueueSingleFlight()
        val fake = FakeApi { Response.success(SyncBatchResponseDto(failed = listOf(
            SyncFailedDto("rejected", "MATERIALIZATION_FAILED", message = "synthetic-private-secret", retryable = false)))) }
        val records = mutableListOf<SyncDiagnostic>()
        val result = manager(store, fake, flight, records).processQueue()
        assertEquals(0, result.accepted); assertEquals(1, result.failed)
        assertEquals("FAILED", store.rows["rejected"]!!.syncStatus)
        manager(store, fake, flight).processScheduledQueue { }
        assertEquals(1, fake.calls.size)
        assertTrue(records.any { it.failure == SyncFailure.MATERIALIZATION_FAILED })
        assertFalse(store.rows["rejected"]!!.lastError!!.contains("synthetic-private-secret"))
        assertFalse(records.toString().contains("synthetic-private-secret"))
    }

    @Test fun distinctEventsDrainInOrderAndCommittedRowsStayCommitted() = runTest {
        val store = Store().apply { repeat(25) { add("event-$it") }; add("already-committed", "SYNCED") }
        val fake = FakeApi(); val flight = QueueSingleFlight()
        val result = manager(store, fake, flight).processQueue()
        assertEquals(25, result.accepted)
        assertEquals(listOf(10, 10, 5), fake.calls.map { it.size })
        assertEquals((0..24).map { "event-$it" }, fake.calls.flatten())
        manager(store, fake, flight).processQueue(); assertEquals(3, fake.calls.size)
    }

    @Test fun separateManagersUseTheDefaultSharedBoundary() = runTest {
        val store = Store().apply { add("event") }; val release = CompletableDeferred<Unit>()
        val fake = FakeApi { request -> release.await(); Response.success(SyncBatchResponseDto(accepted = request.events.map { it.eventId })) }
        fun instance() = SyncManager(store.dao, fake.api, diagnostic = {}, clock = { store.now }, retryJitter = { 0 })
        val a = async { instance().processQueue() }; runCurrent()
        val b = async { instance().processQueue() }; runCurrent()
        assertEquals(1, fake.calls.size)
        release.complete(Unit); advanceUntilIdle()
        assertEquals(1, a.await().accepted); assertEquals(0, b.await().accepted)
        assertEquals(1, fake.maximumActive)
    }

    @Test fun missingAcknowledgementRemainsRetryableNotSynced() = runTest {
        val store = Store().apply { add("unacknowledged") }
        val fake = FakeApi { Response.success(SyncBatchResponseDto()) }
        val result = manager(store, fake, QueueSingleFlight()).processQueue()
        assertEquals(0, result.accepted); assertEquals(1, result.failed)
        assertEquals("PENDING", store.rows["unacknowledged"]!!.syncStatus)
        assertEquals("RESPONSE_UNCERTAIN", store.rows["unacknowledged"]!!.lastError)
    }

    @Test fun failedLocalTransactionCanRetryButSuccessfulEnqueueCannotRepeat() = runTest {
        val submission = ProfileSubmissionOnce(); var attempts = 0
        try { submission.submit { attempts++; throw IOException("synthetic-private-secret") }; fail("Expected rollback") }
        catch (_: IOException) { }
        assertEquals("stable-entity", submission.submit { attempts++; "stable-entity" })
        assertEquals("stable-entity", submission.submit { error("Must not create another event") })
        assertEquals(2, attempts)
    }
}
