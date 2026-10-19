package com.agrios.app.core.sync

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class CropCycleCreateOnceTest {
    @Test fun concurrentAndRepeatedActivationsSendOnce() = runTest {
        val once = CropCycleCreateOnce<String>()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val first = async { once.submit { calls++; gate.await(); Response.success("cycle") } }
        runCurrent()
        val second = async { once.submit { calls++; Response.success("duplicate") } }
        runCurrent()
        assertEquals(1, calls)
        gate.complete(Unit)
        assertSame(first.await(), second.await())
        assertEquals("cycle", once.submit { error("must not repeat") }.value)
        assertEquals(1, calls)
    }

    @Test fun transportUncertaintyNeverReplaysOrReportsSuccess() = runTest {
        val once = CropCycleCreateOnce<String>()
        var calls = 0
        val result = once.submit { calls++; throw IOException("SYNTHETIC_PRIVATE_SENTINEL") }
        assertEquals(CycleCreateOutcome.UNCERTAIN, result.outcome)
        assertNull(result.httpStatus)
        assertNull(result.value)
        assertSame(result, once.submit { calls++; Response.success("duplicate") })
        assertEquals(1, calls)
        assertFalse(result.diagnostic().contains("SYNTHETIC_PRIVATE_SENTINEL"))
    }

    @Test fun rejectionRetainsStatusButNotBodyAndIsNotRetried() = runTest {
        val once = CropCycleCreateOnce<String>()
        val result = once.submit { Response.error(403, "SYNTHETIC_PRIVATE_SENTINEL".toResponseBody()) }
        assertEquals(CycleCreateOutcome.HTTP_REJECTED, result.outcome)
        assertEquals(403, result.httpStatus)
        assertNull(result.value)
        assertSame(result, once.submit { error("must not repeat") })
        assertFalse(result.diagnostic().contains("SYNTHETIC_PRIVATE_SENTINEL"))
    }

    @Test fun missingSuccessBodyRemainsUncertain() = runTest {
        val once = CropCycleCreateOnce<String>()
        val result = once.submit { Response.success<String>(null) }
        assertEquals(CycleCreateOutcome.UNCERTAIN, result.outcome)
        assertEquals(200, result.httpStatus)
        assertSame(result, once.submit { error("must not repeat") })
    }

    @Test fun cancellationDoesNotReleaseInFlightAttemptForReplay() = runTest {
        val once = CropCycleCreateOnce<String>()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val first = async { once.submit { calls++; gate.await(); Response.success("cycle") } }
        runCurrent()
        first.cancel()
        val next = async { once.submit { calls++; Response.success("duplicate") } }
        runCurrent()
        assertEquals(1, calls)
        gate.complete(Unit)
        assertEquals("cycle", next.await().value)
        assertEquals(1, calls)
    }

    @Test fun unexpectedFailureAndSuccessDiagnosticsNeverEchoInputs() = runTest {
        val sentinel = "SYNTHETIC_PRIVATE_SENTINEL"
        val unknown = CropCycleCreateOnce<String>().submit { throw IllegalStateException(sentinel) }
        val success = CropCycleCreateOnce<String>().submit { Response.success(sentinel) }
        assertEquals(CycleCreateOutcome.UNCERTAIN, unknown.outcome)
        assertEquals(CycleCreateOutcome.ACCEPTED, success.outcome)
        assertEquals("CycleCreate(outcome=ACCEPTED, httpStatus=200)", success.diagnostic())
        assertFalse(unknown.diagnostic().contains(sentinel))
        assertFalse(success.diagnostic().contains(sentinel))
    }

    @Test fun createClientPreservesAuthInterceptorAndTimeoutsWithoutAutomaticReplay() {
        val interceptor = okhttp3.Interceptor { error("no real requests in tests") }
        val client = cropCycleCreateClient(OkHttpClient.Builder().addInterceptor(interceptor)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS))
        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertSame(interceptor, client.interceptors.single())
        assertEquals(15000, client.connectTimeoutMillis)
        assertEquals(15000, client.readTimeoutMillis)
    }
}
