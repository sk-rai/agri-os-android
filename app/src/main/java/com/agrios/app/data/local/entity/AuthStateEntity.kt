package com.agrios.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "auth_state")
data class AuthStateEntity(
    @PrimaryKey
    val id: Int = 1, // Singleton row
    @ColumnInfo(name = "jwt") val jwt: String?,
    @ColumnInfo(name = "device_key") val deviceKey: String?,
    @ColumnInfo(name = "user_id") val userId: String?,
    @ColumnInfo(name = "tenant_id") val tenantId: String?,
    @ColumnInfo(name = "role") val role: String?,
    @ColumnInfo(name = "mobile_number") val mobileNumber: String?,
    @ColumnInfo(name = "is_authenticated") val isAuthenticated: Boolean = false,
)
