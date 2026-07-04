package com.agrios.app.core.geo

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class GeoPoint(
    val lat: Double,
    val lng: Double
)

object GeoJson {
    fun point(point: GeoPoint): String {
        return String.format(
            Locale.US,
            "{\"type\":\"Point\",\"coordinates\":[%.7f,%.7f]}",
            point.lng,
            point.lat
        )
    }

    fun polygon(points: List<GeoPoint>): String? {
        if (points.size < 3) return null
        val closed = if (points.first() == points.last()) points else points + points.first()
        val coordinates = closed.joinToString(",") { point ->
            String.format(Locale.US, "[%.7f,%.7f]", point.lng, point.lat)
        }
        return "{\"type\":\"Polygon\",\"coordinates\":[[$coordinates]]}"
    }

    fun parsePoint(geoJson: String?): GeoPoint? {
        if (geoJson.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(geoJson)
            if (!obj.optString("type").equals("Point", ignoreCase = true)) return null
            val coordinates = obj.getJSONArray("coordinates")
            GeoPoint(lat = coordinates.getDouble(1), lng = coordinates.getDouble(0))
        } catch (_: Exception) {
            null
        }
    }

    fun parsePolygon(geoJson: String?): List<GeoPoint> {
        if (geoJson.isNullOrBlank()) return emptyList()
        return try {
            val obj = JSONObject(geoJson)
            if (!obj.optString("type").equals("Polygon", ignoreCase = true)) return emptyList()
            val ring = obj.getJSONArray("coordinates").getJSONArray(0)
            val points = mutableListOf<GeoPoint>()
            for (i in 0 until ring.length()) {
                val coordinate = ring.getJSONArray(i)
                points += GeoPoint(lat = coordinate.getDouble(1), lng = coordinate.getDouble(0))
            }
            if (points.size > 1 && points.first() == points.last()) points.dropLast(1) else points
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun isGeoJson(value: Any?): Boolean {
        val text = value as? String ?: return false
        return try {
            val type = JSONObject(text).optString("type")
            type.equals("Point", ignoreCase = true) || type.equals("Polygon", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    fun asJsonObject(value: String): JSONObject = JSONObject(value)

    fun previewText(geoJson: String?): String {
        if (geoJson.isNullOrBlank()) return "No geometry captured"
        return try {
            val obj = JSONObject(geoJson)
            when (obj.optString("type")) {
                "Point" -> {
                    val coordinates = obj.getJSONArray("coordinates")
                    "Point: lat ${coordinates.getDouble(1)}, lng ${coordinates.getDouble(0)}"
                }
                "Polygon" -> {
                    val ring = obj.getJSONArray("coordinates").getJSONArray(0)
                    "Polygon: ${ring.length()} coordinates"
                }
                else -> "Unknown geometry"
            }
        } catch (_: Exception) {
            "Invalid GeoJSON"
        }
    }
}
