package com.agrios.app.data.repository

import com.agrios.app.core.database.AppDatabase
import com.agrios.app.core.network.ApiConfig
import com.agrios.app.core.network.AuthInterceptor
import com.agrios.app.core.sync.QueueDispatchLease
import com.agrios.app.data.local.entity.SelfProfileCacheFarmerEntity
import com.agrios.app.data.local.entity.SelfProfileCacheParcelEntity
import com.agrios.app.data.local.entity.SelfProfileCacheSoilEntity
import com.agrios.app.data.remote.api.SelfProfileCacheApi
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.MalformedJsonException
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit

internal enum class SelfProfileRefreshResult {
    APPLIED, UNCHANGED, NO_PROFILE, INVALID_RESPONSE, CONFLICT, SESSION_CHANGED, REQUEST_FAILED
}

internal class SelfProfileRefreshReceipt(val origin: QueueDispatchLease, val farmerId: String)
internal class SelfProfileRefreshOutcome(
    val result: SelfProfileRefreshResult,
    val receipt: SelfProfileRefreshReceipt? = null
)

/** Local persistence errors and cancellation propagate. */
internal class SelfProfileCacheRefresh(private val db: AppDatabase, private val api: SelfProfileCacheApi) {
    suspend fun refresh(): SelfProfileRefreshResult {
        val coordinator = db.sessionExpiry
        val selected = coordinator.requestLease() ?: return SelfProfileRefreshResult.SESSION_CHANGED
        return refresh(QueueDispatchLease(coordinator, selected)).result
    }

    suspend fun refresh(origin: QueueDispatchLease): SelfProfileRefreshOutcome {
        val coordinator = db.sessionExpiry
        val selected = origin.selected
        if (origin.coordinator !== coordinator || !coordinator.isCurrentLease(selected)) {
            return SelfProfileRefreshOutcome(SelfProfileRefreshResult.SESSION_CHANGED)
        }
        val tenant = checkNotNull(selected.state.tenantId)
        val user = checkNotNull(selected.state.userId)
        suspend fun failure(result: SelfProfileRefreshResult) = SelfProfileRefreshOutcome(
            if (coordinator.isCurrentLease(selected)) result else SelfProfileRefreshResult.SESSION_CHANGED
        )
        val response = try {
            api.get(origin)
        } catch (_: IOException) {
            return failure(SelfProfileRefreshResult.REQUEST_FAILED)
        }
        val projection = try {
            response.errorBody()?.close()
            response.body().use { body ->
                if (!coordinator.isCurrentLease(selected)) return SelfProfileRefreshOutcome(SelfProfileRefreshResult.SESSION_CHANGED)
                when (response.code()) {
                    404 -> return failure(SelfProfileRefreshResult.NO_PROFILE)
                    200 -> { if (body == null) return failure(SelfProfileRefreshResult.INVALID_RESPONSE) }
                    else -> return failure(SelfProfileRefreshResult.REQUEST_FAILED)
                }
                withContext(Dispatchers.IO) { decodeSelfProfile(checkNotNull(body), tenant, user) }
            }
        } catch (_: InvalidProjection) {
            return failure(SelfProfileRefreshResult.INVALID_RESPONSE)
        } catch (_: IOException) {
            return failure(SelfProfileRefreshResult.REQUEST_FAILED)
        }
        val result = when (SelfProfileCacheStore(db).commit(origin, projection)) {
            CacheCommit.APPLIED -> SelfProfileRefreshResult.APPLIED
            CacheCommit.UNCHANGED -> SelfProfileRefreshResult.UNCHANGED
            CacheCommit.INVALID -> SelfProfileRefreshResult.INVALID_RESPONSE
            CacheCommit.CONFLICT -> SelfProfileRefreshResult.CONFLICT
            CacheCommit.SESSION_CHANGED -> SelfProfileRefreshResult.SESSION_CHANGED
        }
        val receipt = if (result == SelfProfileRefreshResult.APPLIED || result == SelfProfileRefreshResult.UNCHANGED)
            SelfProfileRefreshReceipt(origin, projection.farmer.farmerId) else null
        return SelfProfileRefreshOutcome(result, receipt)
    }

    companion object {
        fun create(db: AppDatabase): SelfProfileCacheRefresh {
            val client = OkHttpClient.Builder()
                .connectTimeout(ApiConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(ApiConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(ApiConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .addInterceptor(AuthInterceptor(db.authDao(), db.sessionExpiry))
                .build()
            val api = Retrofit.Builder().baseUrl(ApiConfig.BASE_URL).client(client).build()
                .create(SelfProfileCacheApi::class.java)
            return SelfProfileCacheRefresh(db, api)
        }
    }
}

private const val MAX_BODY = 1_048_576L
private const val MAX_DEPTH = 32
private const val MAX_NODES = 32_768
private class InvalidProjection : IllegalArgumentException("SELF_PROFILE_INVALID")
private fun verify(condition: Boolean) { if (!condition) throw InvalidProjection() }

private fun decodeSelfProfile(body: ResponseBody, tenant: String, user: String): SelfProfileProjection {
    val source = body.source()
    verify(body.contentLength() <= MAX_BODY && !source.request(MAX_BODY + 1))
    val envelope = try {
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(source.readByteArray())).toString()
        verify(text.firstOrNull() != '\uFEFF')
        JsonReader(text.reader()).use { reader ->
            reader.isLenient = false
            val tree = StrictProjectionReader(reader).value(0)
            verify(reader.peek() == JsonToken.END_DOCUMENT)
            tree.asObject()
        }
    } catch (_: MalformedJsonException) {
        throw InvalidProjection()
    } catch (_: EOFException) {
        throw InvalidProjection()
    } catch (_: CharacterCodingException) {
        throw InvalidProjection()
    } catch (_: IllegalStateException) {
        throw InvalidProjection()
    } catch (_: NumberFormatException) {
        throw InvalidProjection()
    }

    val exists = envelope.get("profile_exists") as? JsonPrimitive
    verify(exists?.isBoolean == true && exists.asBoolean &&
        envelope.identity("schema_version") == "profile_hydration.v1" && envelope.identity("tenant_id") == tenant)
    val farmer = envelope.get("farmer").asObject()
    verify(farmer.identity("tenant_id") == tenant && farmer.identity("user_id") == user)
    val farmerId = farmer.identity("id")
    val parcelIds = mutableSetOf<String>()
    val parcels = envelope.children("parcels").map {
        verify(it.identity("tenant_id") == tenant && it.identity("farmer_id") == farmerId)
        val id = it.identity("id")
        verify(parcelIds.add(id))
        SelfProfileCacheParcelEntity(tenant, user, farmerId, id, it.text("survey_number"),
            it.text("reported_area_unit"), it.real("reported_area"))
    }
    val soilIds = mutableSetOf<String>()
    val soil = envelope.children("soil_profiles").map {
        val parcelId = it.identity("parcel_id")
        val id = it.identity("id")
        // Soil has no explicit tenant field in v1; only same-response parcels establish the link.
        verify(it.identity("farmer_id") == farmerId && parcelId in parcelIds && soilIds.add(id))
        SelfProfileCacheSoilEntity(tenant, user, farmerId, parcelId, id, it.text("soil_type_code"), it.real("ph"))
    }
    return SelfProfileProjection(SelfProfileCacheFarmerEntity(tenant, user, farmerId,
        farmer.text("display_name"), farmer.text("village_name_manual")), parcels, soil)
}

private fun JsonElement?.asObject() = this as? JsonObject ?: throw InvalidProjection()
private fun JsonObject.field(name: String) = get(name)?.takeUnless { it.isJsonNull }
private fun JsonObject.identity(name: String) = text(name)?.takeIf { it.isNotBlank() } ?: throw InvalidProjection()
private fun JsonObject.text(name: String): String? = field(name)?.let {
    (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.asString ?: throw InvalidProjection()
}
private fun JsonObject.real(name: String): Double? = field(name)?.let {
    (it as? JsonPrimitive)?.takeIf { p -> p.isNumber }?.asDouble?.takeIf { n -> n.isFinite() }
        ?: throw InvalidProjection()
}
private fun JsonObject.children(name: String): List<JsonObject> = field(name)?.let {
    (it as? JsonArray)?.map { row -> row.asObject() } ?: throw InvalidProjection()
}.orEmpty()

/** Strict syntax, no duplicate object fields, bounded tree even in ignored sections. */
private class StrictProjectionReader(private val reader: JsonReader) {
    private var nodes = 0

    fun value(depth: Int): JsonElement {
        verify(depth <= MAX_DEPTH && ++nodes <= MAX_NODES)
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = scalarText(reader.nextName())
                    verify(!has(name))
                    add(name, value(depth + 1))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                reader.beginArray()
                while (reader.hasNext()) add(value(depth + 1))
                reader.endArray()
            }
            JsonToken.STRING -> JsonPrimitive(scalarText(reader.nextString()))
            JsonToken.NUMBER -> JsonPrimitive(reader.nextString().toBigDecimal())
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
            else -> throw InvalidProjection()
        }
    }

    private fun scalarText(text: String): String {
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (Character.isHighSurrogate(c)) {
                verify(i < text.length && Character.isLowSurrogate(text[i]))
                i++
            } else {
                verify(!Character.isLowSurrogate(c))
            }
        }
        return text
    }
}
