package com.agrios.app.ui.auth

import org.junit.Assert.assertFalse
import org.junit.Test

class AuthUiStatePrivacyTest {
    @Test
    fun diagnosticsDoNotContainSecretOrServerMessage() {
        val secret = (1..6).joinToString("")
        val serverMessage = "private authentication detail"
        val state = AuthUiState(otpCode = secret, errorMessage = serverMessage)
        assertFalse("State diagnostics must redact the code", state.toString().contains(secret))
        assertFalse("State diagnostics must redact error payloads", state.toString().contains(serverMessage))
    }
}
