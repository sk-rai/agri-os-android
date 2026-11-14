package com.agrios.app.core.auth

internal enum class OtpFailureCategory {
    OWNER_UNAVAILABLE, OWNER_MISMATCH, TRANSPORT_UNAVAILABLE, INVALID_RESPONSE,
    STORAGE_UNAVAILABLE, UNEXPECTED_FAILURE
}

internal enum class OtpTransportCode { DNS, CONNECT, TIMEOUT, EOF, SOCKET, OTHER_IO }

/** Exception types only; never inspect a message, URL, header or response body. */
internal fun otpTransportCode(error: java.io.IOException): OtpTransportCode {
    var current: Throwable? = error
    repeat(6) {
        when (current) {
            is java.net.UnknownHostException -> return OtpTransportCode.DNS
            is java.net.SocketTimeoutException -> return OtpTransportCode.TIMEOUT
            is java.net.ConnectException -> return OtpTransportCode.CONNECT
            is java.io.EOFException -> return OtpTransportCode.EOF
            is java.net.SocketException -> return OtpTransportCode.SOCKET
        }
        current = current?.cause
    }
    return OtpTransportCode.OTHER_IO
}

internal class OtpVerificationUnavailable(val category: OtpFailureCategory,
    val transportCode: OtpTransportCode? = null) :
    IllegalStateException("OTP_${category.name}")

internal class OtpHttpRejected(val status: Int) : IllegalStateException("OTP_HTTP_REJECTED") {
    init { require(status in 100..599) }
}
