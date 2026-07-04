package com.agrios.app.ui.geo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.agrios.app.core.geo.GeoJson
import com.agrios.app.core.geo.GeoPoint
import com.agrios.app.core.geo.GeometryDraftStore

@Composable
fun GpsPointCaptureWidget(
    label: String,
    value: String?,
    enabled: Boolean,
    draftKey: String?,
    onValueChange: (String?) -> Unit
) {
    val context = LocalContext.current
    val initial = remember(value, draftKey) {
        GeoJson.parsePoint(value) ?: draftKey?.let { GeoJson.parsePoint(GeometryDraftStore.load(context, it)) }
    }
    var latText by remember(initial) { mutableStateOf(initial?.lat?.toString() ?: "") }
    var lngText by remember(initial) { mutableStateOf(initial?.lng?.toString() ?: "") }
    var message by remember { mutableStateOf<String?>(null) }

    fun emitPoint(lat: Double, lng: Double) {
        val geoJson = GeoJson.point(GeoPoint(lat = lat, lng = lng))
        draftKey?.let { GeometryDraftStore.save(context, it, geoJson) }
        onValueChange(geoJson)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val location = getLastKnownLocation(context)
            if (location != null) {
                latText = location.latitude.toString()
                lngText = location.longitude.toString()
                emitPoint(location.latitude, location.longitude)
                message = "Captured last known GPS location"
            } else {
                message = "No last known GPS location available; enter coordinates manually"
            }
        } else {
            message = "Location permission denied; enter coordinates manually"
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = latText,
                onValueChange = { latText = it },
                label = { Text("Lat") },
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = lngText,
                onValueChange = { lngText = it },
                label = { Text("Lng") },
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = enabled, onClick = {
                val lat = latText.toDoubleOrNull()
                val lng = lngText.toDoubleOrNull()
                if (lat == null || lng == null) {
                    message = "Enter valid latitude and longitude"
                } else {
                    emitPoint(lat, lng)
                    message = "Point captured"
                }
            }) { Text("Save point") }
            OutlinedButton(enabled = enabled, onClick = {
                if (hasLocationPermission(context)) {
                    val location = getLastKnownLocation(context)
                    if (location != null) {
                        latText = location.latitude.toString()
                        lngText = location.longitude.toString()
                        emitPoint(location.latitude, location.longitude)
                        message = "Captured last known GPS location"
                    } else {
                        message = "No last known GPS location available; enter coordinates manually"
                    }
                } else {
                    permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }) { Text("Use current GPS") }
            OutlinedButton(enabled = enabled, onClick = {
                latText = ""
                lngText = ""
                draftKey?.let { GeometryDraftStore.clear(context, it) }
                onValueChange(null)
                message = "Point cleared"
            }) { Text("Clear") }
        }
        GeometryPreviewWidget(value)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun GpsPolygonWalkingWidget(
    label: String,
    value: String?,
    enabled: Boolean,
    draftKey: String?,
    onValueChange: (String?) -> Unit
) {
    val context = LocalContext.current
    val initialPoints = remember(value, draftKey) {
        val source = value ?: draftKey?.let { GeometryDraftStore.load(context, it) }
        GeoJson.parsePolygon(source)
    }
    var points by remember(initialPoints) { mutableStateOf(initialPoints) }
    var latText by remember { mutableStateOf("") }
    var lngText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    fun emitPolygon(updated: List<GeoPoint>) {
        points = updated
        val geoJson = GeoJson.polygon(updated)
        if (geoJson != null) {
            draftKey?.let { GeometryDraftStore.save(context, it, geoJson) }
            onValueChange(geoJson)
        } else {
            onValueChange(null)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val location = getLastKnownLocation(context)
            if (location != null) {
                emitPolygon(points + GeoPoint(location.latitude, location.longitude))
                message = "Added last known GPS vertex"
            } else {
                message = "No last known GPS location available; add vertex manually"
            }
        } else {
            message = "Location permission denied; add vertex manually"
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text("Capture at least 3 boundary points. Output is GeoJSON Polygon.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = latText,
                onValueChange = { latText = it },
                label = { Text("Lat") },
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = lngText,
                onValueChange = { lngText = it },
                label = { Text("Lng") },
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = enabled, onClick = {
                val lat = latText.toDoubleOrNull()
                val lng = lngText.toDoubleOrNull()
                if (lat == null || lng == null) {
                    message = "Enter valid latitude and longitude"
                } else {
                    emitPolygon(points + GeoPoint(lat, lng))
                    latText = ""
                    lngText = ""
                    message = "Vertex added"
                }
            }) { Text("Add vertex") }
            OutlinedButton(enabled = enabled, onClick = {
                if (hasLocationPermission(context)) {
                    val location = getLastKnownLocation(context)
                    if (location != null) {
                        emitPolygon(points + GeoPoint(location.latitude, location.longitude))
                        message = "Added last known GPS vertex"
                    } else {
                        message = "No last known GPS location available; add vertex manually"
                    }
                } else {
                    permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }) { Text("Add current GPS") }
            OutlinedButton(enabled = enabled && points.isNotEmpty(), onClick = {
                val updated = points.dropLast(1)
                emitPolygon(updated)
                message = "Last vertex removed"
            }) { Text("Undo") }
        }
        if (points.isNotEmpty()) {
            Text("Vertices (${points.size})", style = MaterialTheme.typography.labelMedium)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                points.forEachIndexed { index, point ->
                    Text(
                        "${index + 1}. ${"%.7f".format(point.lat)}, ${"%.7f".format(point.lng)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        GeometryPreviewWidget(value ?: GeoJson.polygon(points))
        OutlinedButton(enabled = enabled, onClick = {
            points = emptyList()
            draftKey?.let { GeometryDraftStore.clear(context, it) }
            onValueChange(null)
            message = "Polygon cleared"
        }) { Text("Clear boundary") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun GeometryPreviewWidget(geoJson: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Geometry preview", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Text(GeoJson.previewText(geoJson), style = MaterialTheme.typography.bodySmall)
            if (!geoJson.isNullOrBlank()) {
                Text(
                    "GeoJSON ready for sync",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun hasLocationPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}

@SuppressLint("MissingPermission")
private fun getLastKnownLocation(context: Context): Location? {
    if (!hasLocationPermission(context)) return null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = manager.getProviders(true)
    return providers.mapNotNull { provider -> manager.getLastKnownLocation(provider) }.maxByOrNull { it.time }
}
