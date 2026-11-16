package com.agrios.app.core.sync

import android.app.Application
import androidx.room.Room
import com.agrios.app.core.database.AppDatabase
import com.agrios.app.core.network.ApiConfig
import com.agrios.app.core.network.AuthInterceptor
import com.agrios.app.data.local.entity.AuthStateEntity
import com.agrios.app.data.remote.api.SelfProfileCacheApi
import com.agrios.app.data.repository.SelfProfileCacheRefresh
import com.agrios.app.data.repository.SelfProfileCacheStore
import com.agrios.app.data.repository.SelfProfileRefreshResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import retrofit2.Response
import retrofit2.Retrofit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class SelfProfileCacheRefreshHostTest {
    @Test fun protectedRetrofitRequestUsesCapturedLeaseAndValidResponseReachesCache() = exercise { db ->
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(db.authDao(), db.sessionExpiry))
            .addInterceptor { chain ->
                val request = chain.request()
                check(request.url.toString() == ApiConfig.BASE_URL + "farmers/me/profile" && request.method == "GET")
                val tag = checkNotNull(request.tag(QueueDispatchLease::class.java))
                check(tag.coordinator === db.sessionExpiry && tag.selected.state == session())
                check(request.header("X-Tenant-ID") == "fixture-tenant" && request.header("X-Actor-ID") == "fixture-user")
                check(request.header("Authorization") == "Bearer ${session().jwt}")
                calls.incrementAndGet()
                okhttp3.Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(valid().toResponseBody()).build()
            }.build()
        val api = Retrofit.Builder().baseUrl(ApiConfig.BASE_URL).client(client).build().create(SelfProfileCacheApi::class.java)
        check(SelfProfileCacheRefresh(db, api).refresh() == SelfProfileRefreshResult.APPLIED)
        val cached = checkNotNull(read(db))
        check(calls.get() == 1 && cached.farmer.displayName == "Remote profile" &&
            cached.parcels.single().reportedArea == 1.5 && cached.soil.single().ph == 6.5)
    }

    @Test fun rejectedIdentityAndMalformedChildrenNeverReachCache() = exercise { db ->
        val parcel = """{"id":"parcel","tenant_id":"fixture-tenant","farmer_id":"farmer","reported_area":1.5}"""
        val soil = """{"id":"soil","parcel_id":"parcel","farmer_id":"farmer","ph":6.5}"""
        val invalid = listOf(
            "ENVELOPE_TENANT" to valid().replaceFirst("\"tenant_id\":\"fixture-tenant\"", "\"tenant_id\":\"other\""),
            "FARMER_TENANT" to valid().replace(
                "\"id\":\"farmer\",\"tenant_id\":\"fixture-tenant\"", "\"id\":\"farmer\",\"tenant_id\":\"other\""),
            "FARMER_USER_NULL" to valid().replace("\"user_id\":\"fixture-user\"", "\"user_id\":null"),
            "FARMER_USER_MISMATCH" to valid().replace("\"user_id\":\"fixture-user\"", "\"user_id\":\"other\""),
            "DUPLICATE_DECODED_KEY" to valid().replace(
                "\"user_id\":\"fixture-user\"", "\"user_id\":\"fixture-user\",\"user\\u005fid\":\"fixture-user\""),
            "PARCEL_TENANT" to valid().replace(parcel, parcel.replace("fixture-tenant", "other")),
            "PARCEL_FARMER" to valid().replace(parcel, parcel.replace("\"farmer_id\":\"farmer\"", "\"farmer_id\":\"other\"")),
            "SOIL_FARMER" to valid().replace(soil, soil.replace("\"farmer_id\":\"farmer\"", "\"farmer_id\":\"other\"")),
            "SOIL_PARCEL" to valid().replace("\"parcel_id\":\"parcel\"", "\"parcel_id\":\"unreturned\""),
            "DUPLICATE_PARCEL_ID" to valid().replace(parcel, "$parcel,$parcel"),
            "DUPLICATE_SOIL_ID" to valid().replace(soil, "$soil,$soil"),
            "NUMERIC_STRING" to valid().replace("\"reported_area\":1.5", "\"reported_area\":\"1.5\""),
            "BOOLEAN_STRING" to valid().replace("\"profile_exists\":true", "\"profile_exists\":\"true\""),
            "SCHEMA_VERSION" to valid().replace("profile_hydration.v1", "profile_hydration.v2"),
            "TRAILING_DOCUMENT" to (valid() + "{}")
        )
        val before = seed(db)
        for ((label, body) in invalid) {
            check(body != valid()) { "INVALID_CASE_NOT_CONSTRUCTED_$label" }
            rejectRetaining(db, label, body.toResponseBody(), before)
        }
    }

    @Test fun rejectsOversizedMalformedEncodingAndOvercomplexBodiesWithoutWrites() = exercise { db ->
        val before = seed(db)
        val limit = 1_048_576
        val oversized = valid().padEnd(limit + 1).toByteArray(Charsets.UTF_8)
        check(oversized.size == limit + 1)
        rejectRetaining(db, "DECLARED_BODY_TOO_LARGE", oversized.toResponseBody(), before)
        val unknown = object : ResponseBody() {
            private val bytes = Buffer().write(oversized)
            override fun contentType(): okhttp3.MediaType? = null
            override fun contentLength() = -1L
            override fun source() = bytes
        }
        rejectRetaining(db, "STREAM_BODY_TOO_LARGE", unknown, before)
        val prefix = valid().dropLast(1) + ",\"ignored\":"
        val invalidUtf8 = (prefix + "\"").toByteArray(Charsets.UTF_8) +
            byteArrayOf(0xc3.toByte(), 0x28) + "\"}".toByteArray(Charsets.UTF_8)
        rejectRetaining(db, "INVALID_UTF8", invalidUtf8.toResponseBody(), before)
        rejectRetaining(db, "UTF8_BOM", ("\uFEFF" + valid()).toResponseBody(), before)
        val tooDeep = prefix + "[".repeat(33) + "0" + "]".repeat(33) + "}"
        rejectRetaining(db, "DEPTH_OVERFLOW", tooDeep.toResponseBody(), before)
        val tooManyNodes = prefix + "[" + "0,".repeat(32_768) + "0]}"
        check(tooManyNodes.toByteArray(Charsets.UTF_8).size < limit)
        rejectRetaining(db, "NODE_OVERFLOW", tooManyNodes.toResponseBody(), before)
    }

    @Test fun delayedResponseCannotCommitAcrossAccountSwitchOrSameOwnerReauthentication() = exercise { db ->
        val before = seed(db)
        for (replacement in listOf(session("replacement"), session())) {
            val result = SelfProfileCacheRefresh(db, object : SelfProfileCacheApi {
                override suspend fun get(lease: QueueDispatchLease): Response<okhttp3.ResponseBody> {
                    check(lease.selected.state == session())
                    authenticate(db, replacement)
                    return Response.success(valid().toResponseBody())
                }
            }).refresh()
            check(result == SelfProfileRefreshResult.SESSION_CHANGED && cacheSnapshot(db) == before)
            if (replacement != session()) check(read(db) == null)
            authenticate(db, session())
            checkNotNull(read(db))
        }
    }

    @Test fun missingCollectionsAnd404LeaveRetainedProjectionIntact() = exercise { db ->
        check(refresh(db, valid()) == SelfProfileRefreshResult.APPLIED)
        val before = checkNotNull(read(db))
        check(refresh(db, valid(children = false)) == SelfProfileRefreshResult.UNCHANGED)
        val result = SelfProfileCacheRefresh(db, object : SelfProfileCacheApi {
            override suspend fun get(lease: QueueDispatchLease): Response<okhttp3.ResponseBody> =
                Response.error(404, "".toResponseBody())
        }).refresh()
        check(result == SelfProfileRefreshResult.NO_PROFILE && read(db) == before)
    }

    @Test fun cancellationPropagatesWithoutWrites() = exercise { db ->
        val before = seed(db)
        val cancelled = try {
            SelfProfileCacheRefresh(db, object : SelfProfileCacheApi {
                override suspend fun get(lease: QueueDispatchLease): Response<okhttp3.ResponseBody> {
                    throw CancellationException("SYNTHETIC_REFRESH_CANCELLED")
                }
            }).refresh()
            false
        } catch (_: CancellationException) {
            true
        }
        check(cancelled && cacheSnapshot(db) == before)
    }

    private fun exercise(block: suspend (AppDatabase) -> Unit) = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        check(app.javaClass == Application::class.java)
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            authenticate(db, session())
            block(db)
        } finally {
            db.close()
        }
    }

    private suspend fun authenticate(db: AppDatabase, state: AuthStateEntity) {
        val coordinator = db.sessionExpiry
        check(coordinator.completeAuthentication(coordinator.beginAuthentication(), state))
    }

    private suspend fun read(db: AppDatabase) = db.sessionExpiry.let {
        SelfProfileCacheStore(db).read(QueueDispatchLease(it, checkNotNull(it.requestLease())), "farmer")
    }

    private suspend fun refresh(db: AppDatabase, body: String) =
        refresh(db, body.toResponseBody())

    private suspend fun refresh(db: AppDatabase, body: ResponseBody) =
        SelfProfileCacheRefresh(db, object : SelfProfileCacheApi {
            override suspend fun get(lease: QueueDispatchLease): Response<ResponseBody> =
                Response.success(body)
        }).refresh()

    private suspend fun seed(db: AppDatabase): List<List<List<String?>>> {
        check(refresh(db, valid()) == SelfProfileRefreshResult.APPLIED)
        checkNotNull(read(db))
        return cacheSnapshot(db)
    }

    private suspend fun rejectRetaining(db: AppDatabase, label: String, body: ResponseBody,
                                       before: List<List<List<String?>>>) {
        check(refresh(db, body) == SelfProfileRefreshResult.INVALID_RESPONSE) { "EXPECTED_REJECTION_$label" }
        check(cacheSnapshot(db) == before) { "REJECTED_RESPONSE_CHANGED_CACHE_$label" }
    }

    private fun cacheSnapshot(db: AppDatabase): List<List<List<String?>>> =
        listOf("farmer" to "farmer_id", "parcel" to "parcel_id", "soil" to "soil_profile_id").map { (table, id) ->
            db.openHelper.writableDatabase.query(
                "SELECT * FROM self_profile_cache_$table ORDER BY tenant_id, user_id, $id"
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(List(cursor.columnCount) { cursor.getString(it) }) }
            }
        }

    private fun session(user: String = "fixture-user") = AuthStateEntity(jwt = "synthetic-profile-session",
        deviceKey = null, userId = user, tenantId = "fixture-tenant", role = "FARMER", mobileNumber = null,
        isAuthenticated = true)

    private fun valid(children: Boolean = true): String {
        val childFields = if (children) """,
            "parcels":[{"id":"parcel","tenant_id":"fixture-tenant","farmer_id":"farmer","reported_area":1.5}],
            "soil_profiles":[{"id":"soil","parcel_id":"parcel","farmer_id":"farmer","ph":6.5}]""" else ""
        return """{"schema_version":"profile_hydration.v1","profile_exists":true,"tenant_id":"fixture-tenant",
            "farmer":{"id":"farmer","tenant_id":"fixture-tenant","user_id":"fixture-user","display_name":"Remote profile"}
            $childFields}"""
    }
}
