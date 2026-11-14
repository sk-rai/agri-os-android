package com.agrios.app.ui.auth

import com.agrios.app.core.auth.AuthenticationCompletion
import com.agrios.app.core.auth.AuthenticationRejected
import com.agrios.app.core.auth.OtpHttpRejected
import com.agrios.app.core.auth.OtpVerificationUnavailable
import com.agrios.app.core.auth.OtpFailureCategory

/** Closed local reasons only; never render a server response or exception message. */
internal fun otpFailureMessage(failure: Throwable?): String {
    if (failure is OtpHttpRejected) return when (failure.status) {
        401 -> "The OTP was rejected. It may be incorrect, expired or already used. Request a new OTP and verify once."
        429 -> "Too many sign-in attempts. Wait before requesting another OTP."
        in 500..599 -> "The sign-in server is unavailable. Please try again later."
        else -> "The server rejected sign-in. Please contact support."
    }
    if (failure is OtpVerificationUnavailable) return when (failure.category) {
        OtpFailureCategory.TRANSPORT_UNAVAILABLE -> "Could not complete the sign-in request. Check the backend connection before trying again."
        OtpFailureCategory.INVALID_RESPONSE -> "The sign-in server returned an incomplete or invalid response. Please contact support."
        OtpFailureCategory.STORAGE_UNAVAILABLE -> "Local sign-in storage is unavailable. Your local work has not been cleared."
        OtpFailureCategory.OWNER_UNAVAILABLE, OtpFailureCategory.OWNER_MISMATCH -> "Sign-in initialization failed. Restart the app without clearing its data."
        else -> "Unable to verify OTP. Please try again."
    }
    return when ((failure as? AuthenticationRejected)?.reason) {
    AuthenticationCompletion.RECOVERY_IDENTITY_MISMATCH ->
        "This sign-in does not match the account, workspace or role of the expired session. Sign in with the original account. Your local work has not been cleared."
    AuthenticationCompletion.STALE_ATTEMPT ->
        "This sign-in attempt is no longer current. Request a new OTP and try again."
    AuthenticationCompletion.STORAGE_BLOCKED ->
        "Sign-in is blocked because local session storage is unavailable. Your local work has not been cleared."
    else -> "Unable to verify OTP. Please try again."
    }
}

internal fun otpFailureDiagnostic(failure: Throwable?): String = when (failure) {
    is AuthenticationRejected -> "OTP_VERIFY_${failure.reason.name}"
    is OtpHttpRejected -> "OTP_VERIFY_HTTP_${failure.status}"
    is OtpVerificationUnavailable -> if (failure.category == OtpFailureCategory.TRANSPORT_UNAVAILABLE && failure.transportCode != null)
        "OTP_VERIFY_TRANSPORT_${failure.transportCode.name}" else "OTP_VERIFY_${failure.category.name}"
    else -> "OTP_VERIFY_REJECTED"
}

/** The caller must keep emission debug-only. This string has no identity values. */
internal fun recoveryComparisonDiagnostic(failure: Throwable?): String? {
    val rejected = failure as? AuthenticationRejected ?: return null
    if (rejected.reason != AuthenticationCompletion.RECOVERY_IDENTITY_MISMATCH) return null
    val comparison = rejected.comparison ?: return null
    return "user_matches=${comparison.userMatches} tenant_matches=${comparison.tenantMatches} role_matches=${comparison.roleMatches}"
}
