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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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

    /**
     * Minimum deduplication window (in milliseconds).
     *
     * Adds an artificial delay to ensure fast responses (like errors or cached responses)
     * wait long enough for concurrent requests to join the deduplication window.
     *
     * **Default:** 0 (no delay)
     *
     * **When to use:**
     * - Fast error responses that complete before other concurrent requests arrive
     * - Cached responses that return almost instantly
     * - You want to maximize deduplication effectiveness for rapid requests
     *
     * **Trade-off:** Higher values increase deduplication effectiveness but primarily add latency
     * to fast responses (and errors) that would otherwise complete sooner than [minWindow], without
     * adding extra delay to already-slow requests.
     *
     * **Example:**
     * ```kotlin
     * install(RequestDeduplication) {
     *     minWindow = 50 // Wait at least 50ms before completing
     * }
     * ```
     *
     * **Recommended:** Start with 50-100ms if you have very fast responses and measure the impact.
     */
    var minWindow: Long = 0
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
 * Plugin order matters! The order affects what gets included in the deduplication cache key.
 *
 * **Plugins BEFORE RequestDeduplication:** Their modifications (headers, auth tokens, etc.)
 * are included in the cache key. Use this for plugins you want to affect deduplication behavior.
 *
 * **Plugins AFTER RequestDeduplication:** Their modifications happen after deduplication,
 * so they don't affect the cache key. Use this for plugins that shouldn't interfere.
 *
 * **Example:**
 * ```kotlin
 * val client = HttpClient {
 *     // Before: modifications included in cache key
 *     install(DefaultRequest) { ... }    // Headers affect cache key
 *     install(Auth) { ... }              // Token affects cache key
 *
 *     install(RequestDeduplication)      // Deduplication based on above
 *
 *     // After: don't affect cache key
 *     install(OtherAuth) { ... }         // Token that doesn't affect cache key
 *     install(Logging) { ... }           // Only logs, no effect on cache
 *     install(HttpTimeout) { ... }       // Applied after dedup
 * }
 * ```
 *
 * Consider your requirements and test to ensure the order matches your expected behavior.
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
 *
 *         // Optional: Add minimum deduplication window for fast responses
 *         // Useful when error responses or cached data return very quickly
 *         minWindow = 50 // milliseconds (default: 0)
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
                    async { proceed(request).save() }.also {
                        delay(config.minWindow)
                    }.await().also(entry.deferred::complete)
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
