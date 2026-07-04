package com.agrios.app.core.geo

import android.content.Context

object GeometryDraftStore {
    private const val PREFS_NAME = "geometry_drafts"

    fun save(context: Context, key: String, geoJson: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (geoJson.isNullOrBlank()) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, geoJson).apply()
        }
    }

    fun load(context: Context, key: String): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(key, null)
    }

    fun clear(context: Context, key: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(key).apply()
    }
}
