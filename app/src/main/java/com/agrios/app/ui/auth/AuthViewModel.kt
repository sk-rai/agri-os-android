package com.agrios.app.ui.auth

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agrios.app.AgriOsApp
import com.agrios.app.BuildConfig
import com.agrios.app.core.auth.AuthRepository
import com.agrios.app.core.network.ApiConfig
import com.agrios.app.core.network.authenticationHttpClient
import com.agrios.app.data.remote.api.AgriOsApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.atomic.AtomicBoolean

data class AuthUiState(
    val step: AuthStep = AuthStep.MOBILE_INPUT,
    val mobileNumber: String = "+91",
    val otpCode: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val reauthRequired: Boolean = false
) {
    // State may appear in a debugger or diagnostic message. Never include secrets.
    override fun toString(): String =
        "AuthUiState(step=$step, isLoading=$isLoading, hasError=${errorMessage != null})"
}

internal class AuthSingleFlightGate {
    private val claimed = AtomicBoolean(false)

    fun tryClaim(): Boolean = claimed.compareAndSet(false, true)
    fun release() = claimed.set(false)
}

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private var verificationJob: Job? = null
    private val requestGate = AuthSingleFlightGate()
    private val verificationGate = AuthSingleFlightGate()

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val db = AgriOsApp.instance.database
    private val authDao = db.authDao()

    private val okHttpClient = authenticationHttpClient()

    private val api = Retrofit.Builder()
        .baseUrl(ApiConfig.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(AgriOsApi::class.java)

    private val deviceId: String = Settings.Secure.getString(
        application.contentResolver,
        Settings.Secure.ANDROID_ID
    )

    private val authRepository = AuthRepository(
        api = api,
        authDao = authDao,
        deviceId = deviceId,
        deviceName = android.os.Build.MODEL,
        expiry = db.sessionExpiry
    )

    init {
        if (BuildConfig.DEBUG) android.util.Log.d("AuthFlow", "OTP_FLOW_REVISION_5")
        // Try silent auth with device key on launch
        tryDeviceKeyAuth()
    }

    private fun tryDeviceKeyAuth() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(step = AuthStep.LOADING)
            _uiState.value = _uiState.value.copy(reauthRequired = authRepository.getAuthState()?.reauthRequired == true)
            val result = authRepository.refreshWithDeviceKey()
            if (result.isSuccess) {
                // Already authenticated — will be handled by navigation
                _uiState.value = _uiState.value.copy(step = AuthStep.LOADING)
            } else {
                // Need fresh login
                _uiState.value = _uiState.value.copy(step = AuthStep.MOBILE_INPUT)
            }
        }
    }

    fun updateMobile(mobile: String) {
        _uiState.value = _uiState.value.copy(mobileNumber = mobile, errorMessage = null)
    }

    fun updateOtp(otp: String) {
        _uiState.value = _uiState.value.copy(otpCode = otp, errorMessage = null)
    }

    fun requestOtp() {
        if (_uiState.value.isLoading || !requestGate.tryClaim()) return
        val mobile = _uiState.value.mobileNumber
        if (mobile.length < 10) {
            _uiState.value = _uiState.value.copy(errorMessage = "Enter valid mobile number")
            requestGate.release()
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

        viewModelScope.launch {
            try {
                val result = authRepository.requestOtp(mobile)
                if (result.isSuccess) {
                    if (BuildConfig.DEBUG) android.util.Log.d("AuthFlow", "OTP_REQUEST_ACCEPTED")
                    // Debug-only convenience: auto-fill the OTP field with dev_otp from the
                    // OTP-request response. Strictly guarded by BuildConfig.DEBUG so release
                    // builds never pre-fill the OTP even if the backend returns dev_otp.
                    val devOtp = DevelopmentOtp.autofill(BuildConfig.DEBUG, result.getOrNull())
                    _uiState.value = _uiState.value.copy(
                        step = AuthStep.OTP_INPUT,
                        isLoading = false,
                        otpCode = devOtp ?: "",
                        errorMessage = null
                    )
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.w("AuthFlow", "OTP_REQUEST_REJECTED")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "Unable to send OTP. Please try again."
                    )
                }
            } finally {
                requestGate.release()
            }
        }
    }

    fun verifyOtp(onSuccess: () -> Unit) {
        if (_uiState.value.isLoading || _uiState.value.step != AuthStep.OTP_INPUT) return
        val mobile = _uiState.value.mobileNumber
        val otp = _uiState.value.otpCode
        if (!DevelopmentOtp.valid(otp)) {
            _uiState.value = _uiState.value.copy(errorMessage = "Enter the six-digit OTP.")
            return
        }
        if (!verificationGate.tryClaim()) return
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

        verificationJob = viewModelScope.launch {
            try {
                val result = authRepository.verifyOtp(mobile, otp)
                if (result.isSuccess) {
                    if (BuildConfig.DEBUG) android.util.Log.d("AuthFlow", "OTP_VERIFY_ACCEPTED")
                    _uiState.value = _uiState.value.copy(step = AuthStep.LOADING, isLoading = false, otpCode = "", errorMessage = null)
                    onSuccess()
                } else {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.w("AuthFlow", otpFailureDiagnostic(result.exceptionOrNull()))
                        recoveryComparisonDiagnostic(result.exceptionOrNull())?.let {
                            android.util.Log.d("AuthFlow", it)
                        }
                    }
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = otpFailureMessage(result.exceptionOrNull())
                    )
                }
            } finally {
                verificationGate.release()
            }
        }
    }

    fun goBackToMobile() {
        verificationJob?.cancel()
        viewModelScope.launch { authRepository.cancelAuthentication() }
        _uiState.value = AuthUiState(step = AuthStep.MOBILE_INPUT, mobileNumber = _uiState.value.mobileNumber,
            reauthRequired = _uiState.value.reauthRequired)
    }

    suspend fun isAuthenticated(): Boolean = authRepository.isAuthenticated()
}
