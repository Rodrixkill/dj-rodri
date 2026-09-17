package com.rvillarroel.djrodri.data.http

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class HttpResult(val code: Int, val body: String?)

/**
 * GET with the manners free community services deserve: an honest User-Agent, backing off on 429 and 5xx,
 * and honouring Retry-After. [sleep] is injectable so tests do not wait for real.
 */
class PoliteHttp(
    private val client: OkHttpClient,
    private val userAgent: String = USER_AGENT,
    private val maxAttempts: Int = 3,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun get(url: String): HttpResult {
        var attempt = 0
        while (true) {
            attempt++
            val result = try {
                execute(Request.Builder().url(url).header("User-Agent", userAgent).build())
            } catch (e: IOException) {
                if (attempt >= maxAttempts) throw e
                sleep(backoffMs(attempt))
                continue
            }
            val retryable = result.response.code == 429 || result.response.code >= 500
            if (!retryable || attempt >= maxAttempts) return result.toHttpResult()
            val retryAfterMs = result.response.header("Retry-After")?.toLongOrNull()?.times(1_000)
            sleep((retryAfterMs ?: backoffMs(attempt)).coerceAtMost(MAX_WAIT_MS))
        }
    }

    private fun backoffMs(attempt: Int) = 1_000L shl (attempt - 1)

    private class Received(val response: Response, val body: String?) {
        fun toHttpResult() = HttpResult(response.code, body)
    }

    private suspend fun execute(request: Request): Received = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.use { it.body?.string() }
                continuation.resume(Received(response, body))
            }
        })
    }

    companion object {
        const val USER_AGENT = "DJRodri/0.1 (app personal; github.com/Rodrixkill)"
        private const val MAX_WAIT_MS = 30_000L
    }
}
