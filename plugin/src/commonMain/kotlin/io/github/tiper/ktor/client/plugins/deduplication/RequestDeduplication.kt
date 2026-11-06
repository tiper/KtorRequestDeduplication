package io.github.tiper.ktor.client.plugins.deduplication

import io.ktor.client.call.HttpClientCall
import io.ktor.client.call.save
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.utils.EmptyContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.OutgoingContent.ByteArrayContent
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Configuration for the request deduplication plugin.
 *
 * @property deduplicateMethods HTTP methods to deduplicate. Default: only GET
 * @property excludeHeaders Headers to exclude from cache key computation, matched regardless of case.
 *                         These are typically tracing/telemetry headers that vary per request
 *                         and should not affect deduplication.
 */
class RequestDeduplicationConfig {
    var deduplicateMethods: Set<HttpMethod> = setOf(Get)

    /**
     * Headers to EXCLUDE from cache key computation, matched regardless of case.
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
 * When multiple identical requests are made concurrently, only one actual
 * request is executed, and all callers receive the same response. Once that request
 * completes, the shared response is released and new requests
 * will trigger fresh HTTP calls.
 *
 * **Memory Optimization:** The response body is read once into a ByteArray and
 * shared across all concurrent callers. This means memory usage is
 * proportional to the response size, not the number of concurrent callers.
 * Example: 1MB response with 10 concurrent callers = 1MB memory (not 10MB).
 *
 * **Cancellation Behavior:**
 * - If one caller cancels, other concurrent callers still receive the response
 * - If the leader is cancelled, its HTTP call is aborted and surviving waiters retry as a new
 *   deduplicated group, which sends a second HTTP request
 * - If all callers cancel, the in-flight entry is removed and resources are freed
 *
 * **Plugin Installation Order**
 *
 * Most plugins change the request before it reaches the Send step, where deduplication happens.
 * Their changes are always part of the cache key, wherever they are installed. This covers
 * `DefaultRequest`, `Auth`, and any plugin using `onRequest`. To stop such a header from affecting
 * deduplication, add it to [RequestDeduplicationConfig.excludeHeaders].
 *
 * Order only matters for plugins that change the request in `on(Send)`:
 * - **Installed BEFORE RequestDeduplication:** their changes are part of the cache key.
 * - **Installed AFTER RequestDeduplication:** their changes come after deduplication, so they are
 *   not part of the cache key, and only run for the request that is actually sent.
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
 * **Finding headers that vary:**
 * If deduplication isn't working as expected, a header probably differs between requests.
 * You can temporarily log the headers to find it:
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
    val inFlight = mutableMapOf<CacheKey, CompletableDeferred<HttpClientCall>>()
    val excludedHeaders = config.excludeHeaders.mapTo(mutableSetOf(), String::lowercase)

    suspend fun Send.Sender.execute(request: HttpRequestBuilder): HttpClientCall = if (config.minWindow < 1) {
        proceed(request).save()
    } else {
        supervisorScope {
            val deferred = async { proceed(request).save() }
            delay(config.minWindow)
            deferred.await()
        }
    }

    suspend fun Send.Sender.deduplicate(request: HttpRequestBuilder, cacheKey: CacheKey): HttpClientCall {
        while (true) {
            val (isFirst, deferred) = mutex.withLock {
                val existing = inFlight[cacheKey]
                if (existing != null) false to existing
                else true to CompletableDeferred<HttpClientCall>().also { inFlight[cacheKey] = it }
            }

            if (isFirst) {
                val result = runCatching { execute(request) }
                // Dropping the entry first means a waiter can never observe an already-completed
                // deferred and spin on it. Completing outside the lock matters because an
                // unconfined waiter resumes right here, and would run caller code holding the lock.
                withContext(NonCancellable) {
                    mutex.withLock { inFlight.remove(cacheKey) }
                }
                result.fold(deferred::complete, deferred::completeExceptionally)
                return result.getOrThrow()
            }

            try {
                return deferred.await()
            } catch (_: CancellationException) {
                // Send.Sender is a CoroutineScope over the client's context, so a bare `isActive`
                // would check the client's job. currentCoroutineContext() checks this caller.
                currentCoroutineContext().ensureActive()
                // Leader was cancelled: retry as a new deduplicated group.
            }
        }
    }

    on(Send) { request ->
        if (request.method !in config.deduplicateMethods || SkipDeduplication in request.attributes) {
            return@on proceed(request)
        }
        val cacheKey = request.buildCacheKey(excludedHeaders) ?: return@on proceed(request)
        deduplicate(request, cacheKey)
    }
}

private val SkipDeduplication = AttributeKey<Unit>("SkipDeduplication")

/**
 * Sends this request on its own, without sharing it with concurrent identical requests.
 *
 * The plugin reads a shared response fully into memory before any caller sees it, so use this for
 * responses that must be streamed, such as large downloads:
 * ```kotlin
 * client.prepareGet(url) { skipDeduplication() }.execute { response ->
 *     response.bodyAsChannel().copyTo(file)
 * }
 * ```
 */
fun HttpRequestBuilder.skipDeduplication() {
    attributes.put(SkipDeduplication, Unit)
}

/**
 * Identifies requests that may share a response. Compares the real header values and body rather
 * than a hash of them: a collision here would hand one caller the response meant for another.
 *
 * Holds copies, because a key must not change while it is in the in-flight map: callers can reuse a
 * body buffer, and plugins that run after this one can append to a header.
 */
private data class CacheKey(
    val method: HttpMethod,
    val url: String,
    val headers: Map<String, List<String>>,
    val contentHeaders: Map<String, List<String>>,
    val contentType: String?,
    val body: List<Byte>,
)

/**
 * Returns `null` for a request that cannot be deduplicated. Only bodies that can be compared qualify:
 * a streamed body can be read only once, and WebSocket and SSE requests open a live connection
 * that cannot be shared, so all of them must go through on their own.
 */
private fun HttpRequestBuilder.buildCacheKey(excludedHeaders: Set<String>): CacheKey? {
    val content = body as? OutgoingContent ?: return null
    val bytes = when (content) {
        is EmptyContent -> emptyList()
        is ByteArrayContent -> content.bytes().copyOf().asList()
        else -> return null
    }
    return CacheKey(
        method = method,
        url = url.buildString(),
        headers = headers.entries().toKeyHeaders(excludedHeaders),
        contentHeaders = content.headers.entries().toKeyHeaders(excludedHeaders),
        // The default transformers move Content-Type out of the request headers and into the body.
        contentType = content.contentType?.toString().takeUnless { HttpHeaders.ContentType.lowercase() in excludedHeaders },
        body = bytes,
    )
}

private fun Set<Map.Entry<String, List<String>>>.toKeyHeaders(excludedHeaders: Set<String>) = associate { (name, values) ->
    name.lowercase() to values.toList()
} - excludedHeaders
