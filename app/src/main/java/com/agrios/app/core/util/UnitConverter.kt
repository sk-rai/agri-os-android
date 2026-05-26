package com.agrios.app.core.util

/**
 * Converts local area units to canonical hectares.
 * Backend stores hectare; mobile collects in local units.
 * Conversion factors are approximate and region-configurable.
 */
object UnitConverter {

    private val TO_HECTARE = mapOf(
        "BIGHA" to 0.2529,    // UP bigha ≈ 0.2529 ha (varies by state)
        "BISWA" to 0.01265,   // 1/20 of a bigha
        "ACRE" to 0.4047,
        "HECTARE" to 1.0,
        "KATHA" to 0.0669,    // Bihar/UP katha
        "GUNTHA" to 0.0101,   // Maharashtra guntha
    )

    fun toHectares(value: Double, unit: String): Double {
        val factor = TO_HECTARE[unit.uppercase()] ?: 1.0
        return value * factor
    }

    fun getDisplayUnits(): List<String> = listOf(
        "BIGHA", "BISWA", "ACRE", "HECTARE", "KATHA", "GUNTHA"
    )
}
