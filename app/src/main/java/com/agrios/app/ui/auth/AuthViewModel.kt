package com.agrios.app.ui.auth

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agrios.app.AgriOsApp
import com.agrios.app.core.auth.AuthRepository
import com.agrios.app.core.network.ApiConfig
import com.agrios.app.core.network.AuthInterceptor
import com.agrios.app.data.remote.api.AgriOsApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

data class AuthUiState(
    val step: AuthStep = AuthStep.MOBILE_INPUT,
    val mobileNumber: String = "+91",
    val otpCode: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val db = AgriOsApp.instance.database
    private val authDao = db.authDao()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(ApiConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(ApiConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

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
        deviceName = android.os.Build.MODEL
    )

    init {
        // Try silent auth with device key on launch
        tryDeviceKeyAuth()
    }

    private fun tryDeviceKeyAuth() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(step = AuthStep.LOADING)
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
        val mobile = _uiState.value.mobileNumber
        if (mobile.length < 10) {
            _uiState.value = _uiState.value.copy(errorMessage = "Enter valid mobile number")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val result = authRepository.requestOtp(mobile)
            if (result.isSuccess) {
                val devOtp = result.getOrNull()
                _uiState.value = _uiState.value.copy(
                    step = AuthStep.OTP_INPUT,
                    isLoading = false,
                    otpCode = devOtp ?: "", // Auto-fill OTP in dev builds
                    errorMessage = if (devOtp != null) "Dev OTP: $devOtp" else null
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = result.exceptionOrNull()?.message ?: "Failed to send OTP"
                )
            }
        }
    }

    fun verifyOtp(onSuccess: () -> Unit) {
        val mobile = _uiState.value.mobileNumber
        val otp = _uiState.value.otpCode

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val result = authRepository.verifyOtp(mobile, otp)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(isLoading = false)
                onSuccess()
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = result.exceptionOrNull()?.message ?: "Invalid OTP"
                )
            }
        }
    }

    fun goBackToMobile() {
        _uiState.value = AuthUiState(step = AuthStep.MOBILE_INPUT, mobileNumber = _uiState.value.mobileNumber)
    }

    suspend fun isAuthenticated(): Boolean = authRepository.isAuthenticated()
}
