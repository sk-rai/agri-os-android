package com.agrios.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached form schema from backend.
 * Schemas are downloaded on login and cached locally for offline use.
 */
@Entity(tableName = "form_schemas")
data class FormSchemaEntity(
    @PrimaryKey
    @ColumnInfo(name = "form_id") val formId: String,
    @ColumnInfo(name = "version") val version: Int,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "schema_json") val schemaJson: String, // full JSON of FormSchemaDto
    @ColumnInfo(name = "cached_at") val cachedAt: Long = System.currentTimeMillis()
)
