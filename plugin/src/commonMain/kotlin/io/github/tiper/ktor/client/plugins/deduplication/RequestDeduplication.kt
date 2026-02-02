package io.github.tiper.ktor.client.plugins.deduplication

import io.ktor.client.call.HttpClientCall
import io.ktor.client.call.save
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpMethod
import io.ktor.http.HttpMethod.Companion.Get
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Configuration for the request deduplication plugin.
 *
 * @property deduplicateMethods HTTP methods to deduplicate. Default: only GET
 * @property excludeHeaders Headers to exclude from cache key computation.
 *                         These are typically tracing/telemetry headers that vary per request
 *                         and should not affect deduplication.
 *                         **Note:** Header matching is case-sensitive. Ensure the casing matches
 *                         exactly how your HTTP client/SDK sends the headers.
 */
class RequestDeduplicationConfig {
    var deduplicateMethods: Set<HttpMethod> = setOf(Get)

    /**
     * Headers to EXCLUDE from cache key computation (case-sensitive).
     * Empty by default - all headers are included in the cache key.
     *
     * Add tracing/telemetry headers that vary per request and should not affect deduplication.
     *
     * **Common headers to consider excluding:**
     * - Distributed tracing: `X-Trace-Id`, `X-Request-Id`, `X-Correlation-Id`
     * - Zipkin/B3: `X-B3-TraceId`, `X-B3-SpanId`, `X-B3-ParentSpanId`, `X-B3-Sampled`
     * - W3C Trace Context: `traceparent`, `tracestate`
     * - Firebase: `X-Firebase-Locale`, `X-Firebase-Auth-Time`
     * - AWS: `X-Amzn-Trace-Id`
     * - Google Cloud: `X-Cloud-Trace-Context`
     *
     * **Important:** Header names must match exactly how they appear in the request.
     * If your SDK uses different casing (e.g., "x-trace-id" vs "X-Trace-Id"),
     * add both variants or check your SDK's documentation for the correct casing.
     */
    var excludeHeaders: Set<String> = emptySet()
}

/**
 * Ktor client plugin that deduplicates concurrent HTTP requests.
 *
 * When multiple requests are made to the same URL concurrently, only one actual
 * request is executed, and all callers receive the same response. After the last
 * concurrent caller completes, the shared response is released and new requests
 * will trigger fresh HTTP calls.
 *
 * **Memory Optimization:** The response body is read once into a ByteArray and
 * shared across all concurrent callers. This means memory usage is
 * proportional to the response size, not the number of concurrent callers.
 * Example: 1MB response with 10 concurrent callers = 1MB memory (not 10MB).
 *
 * **Cancellation Behavior:**
 * - If one caller cancels, other concurrent callers still receive the response
 * - If all callers cancel, the in-flight request is cancelled to save resources
 * - The first caller runs in a supervisor scope to prevent cascading cancellations
 *
 * **⚠️ CRITICAL: Plugin Installation Order**
 *
 * This plugin **MUST be installed LAST** (or at least after any plugins that modify requests).
 * Plugins like Auth, DefaultRequest, and custom header plugins must be installed BEFORE
 * RequestDeduplication to ensure their modifications (tokens, headers, etc.) are included
 * in the deduplicated request.
 *
 * **Correct installation order:**
 * ```kotlin
 * val client = HttpClient {
 *     install(Auth) { ... }              // Auth adds tokens FIRST
 *     install(DefaultRequest) { ... }    // Default headers added
 *     install(Logging) { ... }           // Logging added
 *     install(RequestDeduplication)      // Deduplication LAST ✅
 * }
 * ```
 *
 * **Use case:** Optimize scenarios where multiple components might request the same data simultaneously
 *
 * **Basic Installation:**
 * ```kotlin
 * val client = HttpClient {
 *     install(RequestDeduplication)
 * }
 * ```
 *
 * **With custom configuration:**
 * ```kotlin
 * val client = HttpClient {
 *     install(RequestDeduplication) {
 *         deduplicateMethods = setOf(Get, Head)
 *
 *         // Exclude tracing/telemetry headers that vary per request
 *         excludeHeaders = setOf(
 *             "X-Trace-Id",
 *             "X-Request-Id",
 *             "X-Correlation-Id",
 *             "traceparent",
 *             "tracestate"
 *         )
 *     }
 * }
 * ```
 *
 * **Finding the correct header casing:**
 * If deduplication isn't working as expected, check the actual header names sent by your SDK.
 * You can temporarily log the headers to verify the exact casing:
 * ```kotlin
 * install(Logging) {
 *     level = LogLevel.HEADERS
 * }
 * ```
 */
val RequestDeduplication: ClientPlugin<RequestDeduplicationConfig> = createClientPlugin(
    "RequestDeduplication",
    ::RequestDeduplicationConfig,
) {
    val config = pluginConfig
    val mutex = Mutex()
    val inFlight = mutableMapOf<String, InFlightEntry>()
    // SupervisorJob must be rightmost so it becomes the effective Job element
    val scope = CoroutineScope(client.coroutineContext + SupervisorJob(client.coroutineContext[Job]))

    on(Send) { request ->
        if (request.method !in config.deduplicateMethods) return@on proceed(request)

        val cacheKey = request.buildCacheKey(config)

        val (isFirst, entry) = mutex.withLock {
            val existing = inFlight[cacheKey]
            if (existing != null) false to existing.also { it.waiters += 1 }
            else true to InFlightEntry().also { inFlight[cacheKey] = it }
        }

        if (isFirst) {
            entry.job = scope.launch {
                try {
                    proceed(request).save().also(entry.deferred::complete)
                } catch (e: Throwable) {
                    throw e.also(entry.deferred::completeExceptionally)
                } finally {
                    mutex.withLock { inFlight.remove(cacheKey) }
                }
            }
        }

        try {
            entry.deferred.await()
        } finally {
            mutex.withLock {
                entry.waiters -= 1
                if (entry.waiters == 0 && !entry.deferred.isCompleted) entry.job?.cancel()
            }
        }
    }
}

private fun HttpRequestBuilder.buildCacheKey(config: RequestDeduplicationConfig): String {
    val headerHash = headers.entries()
        .filter { (name, _) -> name !in config.excludeHeaders }
        .sortedBy { (name, _) -> name }
        .fold(0) { acc, (name, values) ->
            // Polynomial rolling hash
            31 * acc + "$name=${values.joinToString(",")}".hashCode()
        }
    return "${method.value}:${url.buildString()}|h=$headerHash"
}

private data class InFlightEntry(
    val deferred: CompletableDeferred<HttpClientCall> = CompletableDeferred(),
    var waiters: Int = 1,
    var job: Job? = null,
)
