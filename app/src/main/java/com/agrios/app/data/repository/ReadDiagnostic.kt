package com.agrios.app.data.repository

import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

enum class ReadFailureCategory {
    CONNECT_TIMEOUT, READ_TIMEOUT, CONNECTION_REFUSED, CONNECTION_RESET,
    DNS, PROTOCOL, CANCELLED, OTHER_IO, UNEXPECTED
}

enum class ReadPhase { UNKNOWN, DNS, CONNECTING, TLS, CONNECTED, WRITING, READING }
enum class SocketEvidence { NONE, REFUSED, RESET }

enum class ReadExceptionCode {
    EOF, SOCKET_TIMEOUT, CONNECT, SOCKET, DNS, PROTOCOL, TLS,
    INTERRUPTED_IO, CANCELLED, INTERRUPTED, IO, UNKNOWN
}

/** Fixed type codes only: never expose runtime class names, messages or stack frames. */
fun readExceptionCodes(error: Throwable): List<ReadExceptionCode> {
    val seen = ArrayList<Throwable>()
    var current: Throwable? = error
    while (current != null && seen.size < 12 && seen.none { it === current }) {
        seen.add(current)
        current = current.cause
    }
    return seen.map {
        when (it) {
            is CancellationException -> ReadExceptionCode.CANCELLED
            is InterruptedException, is ClosedByInterruptException -> ReadExceptionCode.INTERRUPTED
            is java.io.EOFException -> ReadExceptionCode.EOF
            is SocketTimeoutException -> ReadExceptionCode.SOCKET_TIMEOUT
            is UnknownHostException, is java.nio.channels.UnresolvedAddressException -> ReadExceptionCode.DNS
            is java.net.ConnectException -> ReadExceptionCode.CONNECT
            is ProtocolException -> ReadExceptionCode.PROTOCOL
            is SSLException -> ReadExceptionCode.TLS
            is java.net.SocketException -> ReadExceptionCode.SOCKET
            is java.io.InterruptedIOException -> ReadExceptionCode.INTERRUPTED_IO
            is IOException -> ReadExceptionCode.IO
            else -> ReadExceptionCode.UNKNOWN
        }
    }
}

/** Per-call callback evidence only. Never retains a connection, address or request. */
class ReadTransportTrace {
    @Volatile var phase = ReadPhase.UNKNOWN
        private set
    @Volatile private var connectStarted = false
    @Volatile var connectionReused: Boolean? = null
        private set
    fun dnsStart() { phase = ReadPhase.DNS }
    fun connectStart() { connectStarted = true; phase = ReadPhase.CONNECTING }
    fun tlsStart() { phase = ReadPhase.TLS }
    fun connectEnd() { phase = ReadPhase.CONNECTED }
    fun connectionAcquired() { connectionReused = !connectStarted; phase = ReadPhase.CONNECTED }
    fun writing() { phase = ReadPhase.WRITING }
    fun reading() { phase = ReadPhase.READING }
}

/** Never examines exception messages. Ambiguous socket failures remain OTHER_IO. */
fun classifyReadFailure(error: Throwable, phase: ReadPhase, socket: SocketEvidence = SocketEvidence.NONE): ReadFailureCategory {
    val causes = ArrayList<Throwable>()
    var current: Throwable? = error
    while (current != null && causes.size < 12 && causes.none { it === current }) {
        causes.add(current)
        current = current.cause
    }
    return when {
        causes.any { it is CancellationException || it is InterruptedException || it is ClosedByInterruptException } -> ReadFailureCategory.CANCELLED
        causes.any { it is UnknownHostException || it is java.nio.channels.UnresolvedAddressException } -> ReadFailureCategory.DNS
        socket == SocketEvidence.REFUSED -> ReadFailureCategory.CONNECTION_REFUSED
        socket == SocketEvidence.RESET -> ReadFailureCategory.CONNECTION_RESET
        causes.any { it is ProtocolException || it is SSLException } -> ReadFailureCategory.PROTOCOL
        causes.any { it is SocketTimeoutException } -> when (phase) {
            ReadPhase.CONNECTING -> ReadFailureCategory.CONNECT_TIMEOUT
            ReadPhase.READING -> ReadFailureCategory.READ_TIMEOUT
            else -> ReadFailureCategory.OTHER_IO
        }
        causes.any { it is IOException } -> ReadFailureCategory.OTHER_IO
        else -> ReadFailureCategory.UNEXPECTED
    }
}

/** Artifact boundary: only allowlisted constants and scalar measurements can leave this model. */
class ReadDiagnostic(route: String, status: Int?, elapsedMs: Long, failure: ReadFailureCategory?, schemaMatches: Boolean? = null,
                     exceptionCodes: List<ReadExceptionCode> = emptyList(), val transportPhase: ReadPhase = ReadPhase.UNKNOWN,
                     val connectionReused: Boolean? = null) {
    val route = route.takeIf { it in ROUTES } ?: "unknown"
    val httpStatus = status?.takeIf { it in 100..599 }
    val elapsedMs = elapsedMs.coerceAtLeast(0)
    val responseReceived = httpStatus != null
    val failureCategory = if (responseReceived) null else failure ?: ReadFailureCategory.UNEXPECTED
    val schemaValid = if (httpStatus == 200) schemaMatches else null
    val schema = EXPECTED_SCHEMAS[this.route]?.takeIf { schemaValid == true }
    val exceptionCodes = if (responseReceived) emptyList() else exceptionCodes.take(12).toList()

    fun toJson(): String = "{\"route\":\"$route\",\"http_status\":${httpStatus ?: "null"},\"schema\":${schema?.let { "\"$it\"" } ?: "null"},\"elapsed_ms\":$elapsedMs,\"failure_category\":${failureCategory?.let { "\"${it.name}\"" } ?: "null"},\"response_received\":$responseReceived,\"schema_valid\":${schemaValid ?: "null"},\"exception_codes\":${exceptionCodes.joinToString(",", "[", "]") { "\"${it.name}\"" }},\"transport_phase\":\"${transportPhase.name}\",\"connection_reused\":${connectionReused ?: "null"}}"
    override fun toString() = toJson()

    companion object {
        private val ROUTES = setOf("profile", "soil_profiles", "land_context", "land_summary", "eligible_parcels", "assigned_work", "assigned_farmer")
        val EXPECTED_SCHEMAS = mapOf("land_context" to "land_intelligence_context.v1", "land_summary" to "land_intelligence_summary.v1", "assigned_work" to "field_agent_worklist.v1")
    }
}
