package com.agrios.app.core.sync

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Response

enum class CycleCreateOutcome { ACCEPTED, HTTP_REJECTED, UNCERTAIN }

class CycleCreateResult<T>(val outcome: CycleCreateOutcome, val httpStatus: Int?, val value: T?) {
    // Deliberately excludes the body, request, exception and arbitrary strings.
    fun diagnostic(): String = "CycleCreate(outcome=$outcome, httpStatus=$httpStatus)"
}

/** One attempt per form intent, including rejected/uncertain attempts. No offline replay.
 * This is not durable server idempotency: a new form/process must reconcile via reads.
 */
class CropCycleCreateOnce<T> {
    private val mutex = Mutex()
    private var result: CycleCreateResult<T>? = null

    suspend fun submit(request: suspend () -> Response<T>): CycleCreateResult<T> = mutex.withLock {
        result ?: withContext(NonCancellable) {
            val outcome = try {
                val response = request()
                if (!response.isSuccessful) {
                    response.errorBody()?.close()
                    CycleCreateResult<T>(CycleCreateOutcome.HTTP_REJECTED, response.code(), null)
                } else {
                    val body = response.body()
                    CycleCreateResult(if (body == null) CycleCreateOutcome.UNCERTAIN else CycleCreateOutcome.ACCEPTED,
                        response.code(), body)
                }
            } catch (_: Exception) {
                // The server may have committed. Never create another operation or log the cause.
                CycleCreateResult<T>(CycleCreateOutcome.UNCERTAIN, null, null)
            }
            outcome.also { result = it }
        }
    }
}

/** Preserve the supplied AuthInterceptor and timeouts; disable transparent replay. */
fun cropCycleCreateClient(builder: OkHttpClient.Builder): OkHttpClient =
    builder.retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
