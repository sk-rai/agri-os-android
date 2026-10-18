package com.agrios.app.ui.auth

import com.agrios.app.data.repository.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.*
import java.util.concurrent.CancellationException

class ReadDiagnosticTest {
    @Test fun exceptionCodesAreFixedAndOrderedNotRuntimeClassNames() {
        class PrivateNamedException : IOException("synthetic-private-token", java.io.EOFException("private-message"))
        assertEquals(listOf(ReadExceptionCode.IO, ReadExceptionCode.EOF), readExceptionCodes(PrivateNamedException()))
        assertEquals(listOf(ReadExceptionCode.SOCKET), readExceptionCodes(SocketException("private-message")))
        assertEquals(listOf(ReadExceptionCode.INTERRUPTED_IO), readExceptionCodes(java.io.InterruptedIOException()))
        assertEquals(listOf(ReadExceptionCode.TLS), readExceptionCodes(javax.net.ssl.SSLException("private-message")))
        assertEquals(listOf(ReadExceptionCode.UNKNOWN), readExceptionCodes(IllegalArgumentException("private-message")))
        val record = ReadDiagnostic("land_context", null, 19, ReadFailureCategory.OTHER_IO,
            exceptionCodes = readExceptionCodes(PrivateNamedException()), transportPhase = ReadPhase.READING,
            connectionReused = true).toJson()
        listOf("PrivateNamedException", "synthetic-private-token", "private-message").forEach { assertFalse(record.contains(it)) }
        val json = JsonParser.parseString(record).asJsonObject
        assertEquals("READING", json.get("transport_phase").asString)
        assertTrue(json.get("connection_reused").asBoolean)
        assertEquals("EOF", json.getAsJsonArray("exception_codes")[1].asString)
    }
    @Test fun connectionReuseRequiresAcquisitionAndNoConnectAttempt() {
        val fresh = ReadTransportTrace()
        assertNull(fresh.connectionReused)
        fresh.dnsStart()
        assertEquals(ReadPhase.DNS, fresh.phase)
        fresh.connectStart()
        fresh.tlsStart()
        assertEquals(ReadPhase.TLS, fresh.phase)
        fresh.connectEnd()
        fresh.connectionAcquired()
        assertEquals(false, fresh.connectionReused)
        fresh.writing()
        assertEquals(ReadPhase.WRITING, fresh.phase)
        fresh.reading()
        assertEquals(ReadPhase.READING, fresh.phase)
        val pooled = ReadTransportTrace()
        pooled.connectionAcquired()
        assertEquals(true, pooled.connectionReused)
        val failed = ReadTransportTrace()
        failed.connectStart()
        assertNull(failed.connectionReused)
    }
    @Test fun causeTraversalIsBoundedAndSuccessfulDiagnosticsDropFailureCodes() {
        val a = IOException("private-message")
        val b = IOException("private-message", a)
        a.initCause(b)
        assertEquals(2, readExceptionCodes(a).size)
        var deep: Throwable = IOException()
        repeat(30) { deep = IOException(deep) }
        assertEquals(12, readExceptionCodes(deep).size)
        val success = ReadDiagnostic("land_context", 200, 83, ReadFailureCategory.OTHER_IO, true,
            readExceptionCodes(a), ReadPhase.READING, false)
        assertTrue(success.exceptionCodes.isEmpty())
        assertNull(success.failureCategory)
    }
    @Test fun timeoutRequiresPhaseEvidence() {
        val error = SocketTimeoutException("synthetic-private-token")
        assertEquals(ReadFailureCategory.CONNECT_TIMEOUT, classifyReadFailure(error, ReadPhase.CONNECTING))
        assertEquals(ReadFailureCategory.READ_TIMEOUT, classifyReadFailure(error, ReadPhase.READING))
        assertEquals(ReadFailureCategory.OTHER_IO, classifyReadFailure(error, ReadPhase.UNKNOWN))
        assertEquals(ReadFailureCategory.OTHER_IO, classifyReadFailure(IOException("timeout"), ReadPhase.READING))
    }
    @Test fun safeCauseTypesAndSocketEvidence() {
        assertEquals(ReadFailureCategory.DNS, classifyReadFailure(IOException(UnknownHostException()), ReadPhase.UNKNOWN))
        assertEquals(ReadFailureCategory.CONNECTION_REFUSED, classifyReadFailure(ConnectException(), ReadPhase.CONNECTING, SocketEvidence.REFUSED))
        assertEquals(ReadFailureCategory.CONNECTION_RESET, classifyReadFailure(SocketException(), ReadPhase.READING, SocketEvidence.RESET))
        assertEquals(ReadFailureCategory.OTHER_IO, classifyReadFailure(ConnectException("refused"), ReadPhase.CONNECTING))
        assertEquals(ReadFailureCategory.PROTOCOL, classifyReadFailure(ProtocolException(), ReadPhase.UNKNOWN))
        assertEquals(ReadFailureCategory.CANCELLED, classifyReadFailure(CancellationException(), ReadPhase.UNKNOWN))
        assertEquals(ReadFailureCategory.OTHER_IO, classifyReadFailure(IOException(), ReadPhase.UNKNOWN))
        assertEquals(ReadFailureCategory.UNEXPECTED, classifyReadFailure(IllegalStateException(), ReadPhase.UNKNOWN))
    }
    @Test fun artifactFormatterCannotEchoUntrustedValues() {
        val sentinel = "Authorization Bearer access_token device_key OTP synthetic-secret"
        val category = classifyReadFailure(IOException(sentinel), ReadPhase.UNKNOWN)
        val record = ReadDiagnostic(sentinel, null, 1234, category).toJson()
        listOf(sentinel, "Authorization", "Bearer", "access_token", "device_key", "OTP").forEach { assertFalse(record.contains(it)) }
        assertEquals("unknown", JsonParser.parseString(record).asJsonObject.get("route").asString)
        val payload = JsonParser.parseString("{\"schema_version\":\"$sentinel\",\"private\":\"$sentinel\"}")
        assertFalse(ReadResult("land_context", 200, 12, payload).toString().contains(sentinel))
    }
    @Test fun successRetainsOnlyExpectedSchemaAndStatus() {
        val result = ReadResult("land_context", 200, 83, JsonParser.parseString("{\"schema_version\":\"land_intelligence_context.v1\"}"))
        val json = JsonParser.parseString(result.diagnostic.toJson()).asJsonObject
        assertEquals(200, json.get("http_status").asInt)
        assertEquals("land_intelligence_context.v1", json.get("schema").asString)
        assertTrue(json.get("response_received").asBoolean)
        assertTrue(json.get("failure_category").isJsonNull)
        assertTrue(json.get("schema_valid").asBoolean)
    }
}
