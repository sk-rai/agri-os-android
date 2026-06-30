package com.agrios.app.core.cache

import android.content.Context
import com.agrios.app.data.remote.dto.CropCycleResponseDto
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object CropCycleCache {
    private const val PREFS_NAME = "crop_cycle_cache"
    private const val KEY_CYCLES = "cycles"
    private val gson = Gson()
    private val listType = object : TypeToken<List<CropCycleResponseDto>>() {}.type

    fun upsert(context: Context, cycle: CropCycleResponseDto) {
        val cycles = getAll(context).toMutableList()
        val index = cycles.indexOfFirst { it.id == cycle.id }
        if (index >= 0) {
            cycles[index] = cycle
        } else {
            cycles.add(0, cycle)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CYCLES, gson.toJson(cycles))
            .apply()
    }

    fun getAll(context: Context): List<CropCycleResponseDto> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CYCLES, null)
            ?: return emptyList()
        return runCatching { gson.fromJson<List<CropCycleResponseDto>>(json, listType) }
            .getOrDefault(emptyList())
    }

    fun getUnavailableParcelIds(context: Context): Set<String> {
        return getAll(context)
            .filter { it.status.equals("ACTIVE", ignoreCase = true) || it.status.equals("COMPLETED", ignoreCase = true) }
            .mapNotNull { it.parcelId }
            .toSet()
    }
}
