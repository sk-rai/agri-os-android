package com.agrios.app.data.remote.dto

import com.google.gson.annotations.SerializedName

data class OtpRequestDto(
    @SerializedName("mobile_number") val mobileNumber: String
)

data class OtpResponseDto(
    @SerializedName("request_id") val requestId: String? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("expires_in") val expiresIn: Int? = null,
    @SerializedName("dev_otp") val devOtp: String? = null
)

data class OtpVerifyDto(
    @SerializedName("mobile_number") val mobileNumber: String,
    @SerializedName("otp_code") val otpCode: String,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_name") val deviceName: String
)

data class DeviceAuthDto(
    @SerializedName("device_key") val deviceKey: String,
    @SerializedName("device_id") val deviceId: String
)

data class AuthResponseDto(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("device_key") val deviceKey: String? = null,
    @SerializedName("user_id") val userId: String,
    @SerializedName("tenant_id") val tenantId: String? = null,
    @SerializedName("role") val role: String
)
