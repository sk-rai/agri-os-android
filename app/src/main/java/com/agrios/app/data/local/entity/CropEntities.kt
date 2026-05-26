package com.agrios.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "crop_categories")
data class CropCategoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
)

@Entity(
    tableName = "crops",
    indices = [
        Index(value = ["crop_code"], unique = true),
        Index(value = ["category_id"])
    ]
)
data class CropEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "crop_code") val cropCode: String,
    @ColumnInfo(name = "canonical_name") val canonicalName: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "season") val season: String? = null, // KHARIF, RABI, ZAID
    @ColumnInfo(name = "aliases") val aliases: String? = null, // JSON array
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
)
