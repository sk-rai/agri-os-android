package com.agrios.app.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agrios.app.AgriOsApp
import com.agrios.app.core.network.ApiConfig
import com.agrios.app.core.network.AuthInterceptor
import com.agrios.app.core.util.LanguageManager
import com.agrios.app.data.remote.api.AgriOsApi
import com.agrios.app.data.repository.MasterDataRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

@Composable
fun MasterDataLoadingScreen(onComplete: () -> Unit) {
    val db = AgriOsApp.instance.database
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(LanguageManager.localize("Checking...", "जाँच हो रही है...")) }
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }

    fun buildRepo(): MasterDataRepository {
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(db.authDao()))
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val api = Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AgriOsApi::class.java)
        return MasterDataRepository(api, db.geographyCacheDao())
    }

    LaunchedEffect(Unit) {
        val stateCount = db.geographyCacheDao().getStateCount()
        if (stateCount > 0) {
            onComplete()
            return@LaunchedEffect
        }

        status = LanguageManager.localize("Downloading states & districts...", "राज्य और जिले डाउनलोड हो रहे हैं...")
        val repo = buildRepo()
        val success = repo.downloadAll()

        if (success) {
            status = LanguageManager.localize("Download complete ✅", "डाउनलोड पूर्ण ✅")
            isLoading = false
            delay(1000)
            onComplete()
        } else {
            status = LanguageManager.localize("Download failed. Check internet.", "डाउनलोड विफल। इंटरनेट जाँचें।")
            isLoading = false
            hasError = true
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator()
            Spacer(Modifier.height(24.dp))
        }
        Text(status, style = MaterialTheme.typography.bodyLarge)

        if (hasError) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                hasError = false; isLoading = true
                scope.launch {
                    status = LanguageManager.localize("Retrying...", "पुनः प्रयास...")
                    val repo = buildRepo()
                    if (repo.downloadAll()) onComplete()
                    else { status = LanguageManager.localize("Still failing.", "अभी भी विफल।"); isLoading = false; hasError = true }
                }
            }) { Text(LanguageManager.localize("Retry", "पुनः प्रयास")) }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onComplete) {
                Text(LanguageManager.localize("Skip", "छोड़ें"))
            }
        }
    }
}
