package com.agrios.app.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

enum class AuthStep { MOBILE_INPUT, OTP_INPUT, LOADING, ERROR }

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit,
    viewModel: AuthViewModel
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App title
        Text(
            text = "Agri-OS",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "कृषि संचालन मंच", // Hindi subtitle
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(48.dp))

        when (uiState.step) {
            AuthStep.MOBILE_INPUT -> MobileInputSection(
                mobile = uiState.mobileNumber,
                onMobileChange = { viewModel.updateMobile(it) },
                onSubmit = { viewModel.requestOtp() },
                isLoading = uiState.isLoading
            )
            AuthStep.OTP_INPUT -> OtpInputSection(
                otp = uiState.otpCode,
                mobile = uiState.mobileNumber,
                onOtpChange = { viewModel.updateOtp(it) },
                onVerify = { viewModel.verifyOtp(onAuthSuccess) },
                onBack = { viewModel.goBackToMobile() },
                isLoading = uiState.isLoading
            )
            AuthStep.LOADING -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Logging in...")
            }
            AuthStep.ERROR -> {
                Text(
                    text = uiState.errorMessage ?: "Something went wrong",
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { viewModel.goBackToMobile() }) {
                    Text("Try Again")
                }
            }
        }

        // Error message
        if (uiState.errorMessage != null && uiState.step != AuthStep.ERROR) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = uiState.errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun MobileInputSection(
    mobile: String,
    onMobileChange: (String) -> Unit,
    onSubmit: () -> Unit,
    isLoading: Boolean
) {
    OutlinedTextField(
        value = mobile,
        onValueChange = { if (it.length <= 13) onMobileChange(it) },
        label = { Text("Mobile Number / मोबाइल नंबर") },
        placeholder = { Text("+91XXXXXXXXXX") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(24.dp))

    Button(
        onClick = onSubmit,
        enabled = mobile.length >= 10 && !isLoading,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text("Send OTP / OTP भेजें")
        }
    }
}

@Composable
private fun OtpInputSection(
    otp: String,
    mobile: String,
    onOtpChange: (String) -> Unit,
    onVerify: () -> Unit,
    onBack: () -> Unit,
    isLoading: Boolean
) {
    Text(
        text = "OTP sent to $mobile",
        style = MaterialTheme.typography.bodyMedium
    )

    Spacer(modifier = Modifier.height(16.dp))

    OutlinedTextField(
        value = otp,
        onValueChange = { if (it.length <= 6) onOtpChange(it) },
        label = { Text("Enter OTP / OTP दर्ज करें") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(24.dp))

    Button(
        onClick = onVerify,
        enabled = otp.length == 6 && !isLoading,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text("Verify / सत्यापित करें")
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    TextButton(onClick = onBack) {
        Text("Change Number / नंबर बदलें")
    }
}
