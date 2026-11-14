package com.agrios.app.core.sync

/** Selected business subject only; backend authentication/authorization remains authoritative. */
data class AssistedCycleSubject(val farmerId: String, val parcelId: String, val projectId: String) {
    init {
        require(listOf(farmerId, parcelId, projectId).all { it.isNotBlank() }) { "Incomplete selected crop context" }
    }

    fun bind(payload: Map<String, Any?>): Map<String, Any?> {
        val subject = mapOf("farmer_id" to farmerId, "parcel_id" to parcelId, "project_id" to projectId)
        subject.forEach { (key, value) ->
            require(payload[key] == null || payload[key] == value) { "Selected crop context changed" }
        }
        return payload + subject
    }
}
