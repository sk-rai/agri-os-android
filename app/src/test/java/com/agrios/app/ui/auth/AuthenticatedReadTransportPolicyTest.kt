package com.agrios.app.ui.auth

import com.agrios.app.data.repository.authenticatedReadClientBuilder
import okhttp3.ConnectionPool
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** No sockets, backend, credentials or Android runtime. Pins the installed OkHttp 4.12 policy. */
class AuthenticatedReadTransportPolicyTest {
    @Test fun shortIdleRetentionPreservesTimeoutsAndNoRetries() {
        val client = authenticatedReadClientBuilder().build()
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(15_000, client.readTimeoutMillis)
        assertFalse(client.retryOnConnectionFailure)
        // OkHttp exposes no public retention getter. Read the pinned version's
        // configuration fields, without opening a connection or waiting for eviction.
        val delegateField = ConnectionPool::class.java.getDeclaredField("delegate").apply { isAccessible = true }
        val delegate = delegateField.get(client.connectionPool)
        val retention = delegate.javaClass.getDeclaredField("keepAliveDurationNs").apply { isAccessible = true }
        assertEquals(TimeUnit.SECONDS.toNanos(2), retention.getLong(delegate))
        val maxIdle = delegate.javaClass.getDeclaredField("maxIdleConnections").apply { isAccessible = true }
        assertEquals(5, maxIdle.getInt(delegate))
    }

    @Test fun readPoolsAreDedicatedAndDoNotAlterOtherClients() {
        val first = authenticatedReadClientBuilder().build()
        val second = authenticatedReadClientBuilder().build()
        assertNotSame(first.connectionPool, second.connectionPool)
        val unrelated = okhttp3.OkHttpClient()
        assertNotSame(first.connectionPool, unrelated.connectionPool)
        assertTrue(unrelated.retryOnConnectionFailure)
        assertEquals(10_000, unrelated.connectTimeoutMillis)
    }
}
