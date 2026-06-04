package com.agrios.app.core.util

/**
 * Utility for village ID validation.
 * Backend requires either a valid UUID for village_id, or null + village_name_manual.
 * NEVER send fake IDs like "manual_saraimohan" — the backend rejects non-UUID strings.
 */
object VillageIdUtil {

    private val UUID_REGEX = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )

    /**
     * Returns true if the string is a valid UUID format.
     */
    fun isValidUuid(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        return UUID_REGEX.matches(id)
    }

    /**
     * Returns true if this is a manual/fake village ID (not a real UUID).
     */
    fun isManualVillageId(id: String?): Boolean {
        if (id.isNullOrBlank()) return true
        return !isValidUuid(id)
    }

    /**
     * Extract the village name from a manual village ID like "manual_saraimohan".
     * Returns null if the ID is a valid UUID.
     */
    fun extractManualVillageName(villageId: String?): String? {
        if (villageId == null) return null
        if (isValidUuid(villageId)) return null
        return villageId.removePrefix("manual_").takeIf { it.isNotBlank() }
    }

    /**
     * Returns the village_id to send to backend (null if manual).
     */
    fun getSyncVillageId(villageId: String?): String? {
        if (villageId == null) return null
        return if (isValidUuid(villageId)) villageId else null
    }

    /**
     * Returns the village_name_manual to send to backend (null if UUID).
     */
    fun getSyncVillageNameManual(villageId: String?, villageName: String?): String? {
        if (villageId != null && isValidUuid(villageId)) return null
        return villageName?.takeIf { it.isNotBlank() }
            ?: extractManualVillageName(villageId)
    }
}
