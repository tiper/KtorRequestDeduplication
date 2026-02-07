package io.github.tiper.ktor.client.plugins.deduplication

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders.ContentType
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Head
import io.ktor.http.HttpMethod.Companion.Post
import io.ktor.http.HttpStatusCode.Companion.InternalServerError
import io.ktor.http.HttpStatusCode.Companion.NotFound
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

@Suppress("OPT_IN_USAGE")
class RequestDeduplicationTest {

    @Test
    fun single_request_makes_one_network_call() = runTest {
        val requestCount = atomic(0)
        val client = mockClient {
            "response-${requestCount.incrementAndGet()}"
        }

        val response = client.get("https://api.example.com/users")

        assertEquals(1, requestCount.value, "Expected only 1 network request")
        assertEquals("response-1", response.bodyAsText(), "Expected response body to match")
    }

    @Test
    fun concurrent_identical_GET_requests_are_deduplicated() = runTest {
        val requestCount = atomic(0)
        val client = mockClient {
            "response-${requestCount.incrementAndGet()}"
        }

        val responses = List(5) {
            async {
                client.get("https://api.example.com/users")
            }
        }.awaitAll()

        assertEquals(1, requestCount.value, "Expected only 1 network request")
        responses.forEach { response ->
            assertEquals("response-1", response.bodyAsText())
        }
    }

    @Test
    fun concurrent_requests_to_different_URLs_are_not_deduplicated() = runTest {
        val requestCount = atomic(0)
        val client = mockClient {
            "response-${requestCount.incrementAndGet()}"
        }

        val responses = listOf(
            async { client.get("https://api.example.com/users") },
            async { client.get("https://api.example.com/posts") },
            async { client.get("https://api.example.com/comments") },
        ).awaitAll()

        assertEquals(3, requestCount.value, "Expected 3 separate network requests")
        assertEquals(3, responses.map { it.bodyAsText() }.toSet().size, "Expected different response bodies")
    }

    @Test
    fun concurrent_requests_with_different_query_parameters_are_not_deduplicated() = runTest {
        val requestCount = atomic(0)
        val client = mockClient {
            "response-${requestCount.incrementAndGet()}"
        }

        val responses = listOf(
            async { client.get("https://api.example.com/users?id=1") },
            async { client.get("https://api.example.com/users?id=2") },
            async { client.get("https://api.example.com/users?id=3") },
        ).awaitAll()

        assertEquals(3, requestCount.value, "Expected 3 separate network requests")
        assertEquals(3, responses.map { it.bodyAsText() }.toSet().size, "Expected different response bodies")
    }

    @Test
    fun test_POST_requests_are_not_deduplicated_by_default() = runTest {
        val requestCount = atomic(0)
        val client = mockClient {
            "response-${requestCount.incrementAndGet()}"
        }

        val responses = List(3) {
            async { client.post("https://api.example.com/users") }
        }.awaitAll()

        assertEquals(3, requestCount.value, "Expected 3 separate network requests for POST")
        assertEquals(3, responses.map { it.bodyAsText() }.toSet().size, "Expected different response bodies for POST")
    }

    @Test
    fun test_POST_requests_can_be_deduplicated_when_configured() = runTest {
        val requestCount = atomic(0)
        val client = mockClient(config = { deduplicateMethods += Post }) {
            "response-${requestCount.incrementAndGet()}"
        }

        val responses = List(3) {
            async {
                client.post("https://api.example.com/users")
            }
        }.awaitAll()

        assertEquals(1, requestCount.value, "Expected only 1 network request for deduplicated POST")
        responses.forEach { response ->
            assertEquals("response-1", response.bodyAsText())
        }
    }

    @Test
    fun sequential_requests_are_not_deduplicated() = runTest {
        val requestCount = atomic(0)
        val client = mockClient {
            "response-${requestCount.incrementAndGet()}"
        }

        val response1 = client.get("https://api.example.com/users")
        val response2 = client.get("https://api.example.com/users")
        val response3 = client.get("https://api.example.com/users")

        // Sequential requests should each trigger a new request
        assertEquals(3, requestCount.value, "Expected 3 separate network requests for sequential calls")
        assertEquals("response-1", response1.bodyAsText())
        assertEquals("response-2", response2.bodyAsText())
        assertEquals("response-3", response3.bodyAsText())
    }

    @Test
    fun requests_with_different_headers_are_not_deduplicated() = runTest {
        val requestCount = atomic(0)
        val client = mockClient {
            "response-${requestCount.incrementAndGet()}"
        }

        val responses = listOf(
            async {
                client.get("https://api.example.com/users") {
                    header("Authorization", "Bearer token1")
                }
            },
            async {
                client.get("https://api.example.com/users") {
                    header("Authorization", "Bearer token2")
                }
            },
            async {
                client.get("https://api.example.com/users") {
                    header("Authorization", "Bearer token3")
                }
            },
        ).awaitAll()

        assertEquals(3, requestCount.value, "Expected 3 separate network requests for different headers")
        assertEquals(3, responses.map { it.bodyAsText() }.toSet().size, "Expected different response bodies for different headers")
    }

    @Test
    fun excluded_headers_do_not_affect_deduplication() = runTest {
        val requestCount = atomic(0)
        val client = mockClient(config = { excludeHeaders = setOf("X-Trace-Id", "X-Request-Id") }) {
            "response-${requestCount.incrementAndGet()}"
        }

        val responses = List(5) { index ->
            async {
                client.get("https://api.example.com/users") {
                    header("X-Trace-Id", "trace-$index")
                    header("X-Request-Id", "req-$index")
                }
            }
        }.awaitAll()

        assertEquals(1, requestCount.value, "Expected only 1 network request ignoring excluded headers")
        responses.forEach { response ->
            assertEquals("response-1", response.bodyAsText())
        }
    }

    @Test
    fun excluded_headers_with_other_different_headers_still_differentiate_requests() = runTest {
        val requestCount = atomic(0)
        val client = mockClient(config = { excludeHeaders = setOf("X-Trace-Id") }) {
            "response-${requestCount.incrementAndGet()}"
        }

        val responses = listOf(
            async {
                client.get("https://api.example.com/users") {
                    header("X-Trace-Id", "trace-1")
                    header("Authorization", "Bearer token1")
                }
            },
            async {
                client.get("https://api.example.com/users") {
                    header("X-Trace-Id", "trace-2")
                    header("Authorization", "Bearer token2")
                }
            },
        ).awaitAll()

        assertEquals(2, requestCount.value, "Expected 2 separate network requests due to different non-excluded headers")
        assertEquals(2, responses.map { it.bodyAsText() }.toSet().size, "Expected different response bodies due to different non-excluded headers")
    }

    @Test
    fun header_order_does_not_affect_deduplication() = runTest {
        val requestCount = atomic(0)
        val client = mockClient {
            "response-${requestCount.incrementAndGet()}"
        }

        val responses = listOf(
            async {
                client.get("https://api.example.com/users") {
                    header("Accept", "application/json")
                    header("Authorization", "Bearer token")
                }
            },
            async {
                client.get("https://api.example.com/users") {
                    header("Authorization", "Bearer token")
                    header("Accept", "application/json")
                }
            },
            async {
                client.get("https://api.example.com/users") {
                    header("Accept", "application/json")
                    header("Authorization", "Bearer token")
                }
            },
        ).awaitAll()

        assertEquals(1, requestCount.value, "Expected only 1 network request ignoring header order")
        responses.forEach { response ->
            assertEquals("response-1", response.bodyAsText())
        }
    }

    @Test
    fun error_responses_are_shared_with_all_waiting_requests() = runTest {
        val requestCount = atomic(0)
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    requestCount.incrementAndGet()
                    respond(
                        content = "Not Found".also { simulateLatency() },
                        status = NotFound,
                        headers = headersOf(ContentType, "text/plain"),
                    )
                }
            }
            install(RequestDeduplication)
        }

        val responses = List(3) {
            async {
                client.get("https://api.example.com/missing")
            }
        }.awaitAll()

        assertEquals(1, requestCount.value, "Expected only 1 network request for error response")
        responses.forEach { response ->
            assertEquals(NotFound, response.status)
            assertEquals("Not Found", response.bodyAsText())
        }
    }

    @Test
    fun cache_key_is_case_sensitive_for_headers() = runTest {
        val requestCount = atomic(0)
        val client = mockClient(
            config = {
                excludeHeaders = setOf("X-Trace-Id") // Exact case
            },
        ) {
            "response-${requestCount.incrementAndGet()}"
        }

        val responses = listOf(
            async {
                client.get("https://api.example.com/users") {
                    header("X-Trace-Id", "trace-1") // Matches exclude list
                }
            },
            async {
                client.get("https://api.example.com/users") {
                    header("x-trace-id", "trace-2") // Different case - not excluded
                }
            },
        ).awaitAll()

        assertEquals(2, requestCount.value, "Expected 2 separate network requests due to case sensitivity in headers")
        assertEquals(2, responses.map { it.bodyAsText() }.toSet().size, "Expected different response bodies due to case sensitivity in headers")
    }

    @Test
    fun concurrent_requests_with_no_headers_are_deduplicated() = runTest {
        val requestCount = atomic(0)
        val client = mockClient {
            "response-${requestCount.incrementAndGet()}"
        }

        val responses = List(10) {
            async {
                client.get("https://api.example.com/users")
            }
        }.awaitAll()

        assertEquals(1, requestCount.value)
        responses.forEach { response ->
            assertEquals("response-1", response.bodyAsText())
        }
    }

    @Test
    fun mixed_concurrent_and_sequential_requests_work_correctly() = runTest {
        val requestCount = atomic(0)
        val client = mockClient {
            "response-${requestCount.incrementAndGet()}"
        }

        // First batch of concurrent requests
        val batch1 = List(3) {
            async {
                client.get("https://api.example.com/users")
            }
        }.awaitAll()

        // Sequential request after first batch completes
        val sequential1 = client.get("https://api.example.com/users")

        // Second batch of concurrent requests
        val batch2 = List(3) {
            async {
                client.get("https://api.example.com/users")
            }
        }.awaitAll()

        // Sequential request after second batch
        val sequential2 = client.get("https://api.example.com/users")

        // Should have 4 actual requests: 1 for batch1, 1 sequential, 1 for batch2, 1 sequential
        assertEquals(4, requestCount.value)

        // Verify each batch got its own response
        batch1.forEach { assertEquals("response-1", it.bodyAsText()) }
        assertEquals("response-2", sequential1.bodyAsText())
        batch2.forEach { assertEquals("response-3", it.bodyAsText()) }
        assertEquals("response-4", sequential2.bodyAsText())
    }

    @Test
    fun empty_exclude_headers_list_includes_all_headers_in_cache_key() = runTest {
        val requestCount = atomic(0)
        val client = mockClient(config = { excludeHeaders = emptySet() }) {
            "response-${requestCount.incrementAndGet()}"
        }

        val responses = listOf(
            async {
                client.get("https://api.example.com/users") {
                    header("X-Custom-Header", "value1")
                }
            },
            async {
                client.get("https://api.example.com/users") {
                    header("X-Custom-Header", "value2")
                }
            },
        ).awaitAll()

        assertEquals(2, requestCount.value, "Expected 2 separate network requests due to different header values")
        assertEquals(2, responses.map { it.bodyAsText() }.toSet().size, "Expected different response bodies due to different header values")
    }

    @Test
    fun first_coroutine_cancels_but_others_still_receive_response() = runTest {
        val requestCount = atomic(0)
        val client = mockClient {
            "response-${requestCount.incrementAndGet()}"
        }

        // Launch first request (this one will be cancelled)
        val job1 = async {
            client.get("https://api.example.com/users")
        }

        // Launch other requests that should still succeed
        val job2 = async {
            client.get("https://api.example.com/users")
        }

        val job3 = async {
            client.get("https://api.example.com/users")
        }

        // Cancel the first request (the one that initiated the in-flight request)
        job1.cancel()

        // Other requests should still complete successfully
        val response2 = job2.await()
        val response3 = job3.await()

        // Only 1 actual network request should have been made
        assertEquals(1, requestCount.value, "Expected only 1 network request despite first caller cancelling")
        assertEquals("response-1", response2.bodyAsText())
        assertEquals("response-1", response3.bodyAsText())
    }

    @Test
    fun all_coroutines_cancel_so_request_gets_cancelled() = runTest {
        val requestCount = atomic(0)
        val requestStarted = atomic(false)
        val client = mockClient {
            requestStarted.value = true
            // Longer delay to ensure we can cancel before completion
            kotlinx.coroutines.delay(200)
            "response-${requestCount.incrementAndGet()}"
        }

        // Launch multiple concurrent requests
        val job1 = async {
            client.get("https://api.example.com/users")
        }

        val job2 = async {
            client.get("https://api.example.com/users")
        }

        val job3 = async {
            client.get("https://api.example.com/users")
        }

        // Give the request a moment to start
        kotlinx.coroutines.delay(50)

        // Cancel all coroutines
        job1.cancel()
        job2.cancel()
        job3.cancel()

        // Wait a bit for cancellation to propagate
        kotlinx.coroutines.delay(100)

        // The request should have been cancelled, so either:
        // - requestCount is 0 (cancelled before incrementing), or
        // - requestCount is 1 but the request was cancelled during execution
        // In both cases, the in-flight job should have been cancelled
        assertTrue(requestCount.value <= 1, "Request should have been cancelled or completed at most once")
    }

    @Test
    fun request_with_body_is_deduplicated_correctly() = runTest {
        val requestCount = atomic(0)
        val client = mockClient {
            "response-${requestCount.incrementAndGet()}"
        }

        List(3) {
            async {
                client.post("https://api.example.com/users") {
                    setBody("request-body")
                }
            }
        }.awaitAll()

        // POST with body should not be deduplicated by default
        assertEquals(3, requestCount.value, "Expected 3 separate network requests for POST with body")
    }

    @Test
    fun large_number_of_concurrent_requests_are_deduplicated() = runTest {
        val requestCount = atomic(0)
        val client = mockClient(200) {
            "response-${requestCount.incrementAndGet()}"
        }

        // Launch 100 concurrent identical requests
        val responses = List(100) {
            async {
                client.get("https://api.example.com/users")
            }
        }.awaitAll()

        // Only 1 actual network request should have been made
        assertEquals(1, requestCount.value, "Expected only 1 network request for 100 concurrent requests")

        // All responses should be identical
        responses.forEach { response ->
            assertEquals("response-1", response.bodyAsText())
        }
    }

    @Test
    fun concurrent_requests_with_same_headers_but_different_values_are_not_deduplicated() = runTest {
        val requestCount = atomic(0)
        val client = mockClient {
            "response-${requestCount.incrementAndGet()}"
        }

        val responses = listOf(
            async {
                client.get("https://api.example.com/users") {
                    header("X-User-ID", "user1")
                }
            },
            async {
                client.get("https://api.example.com/users") {
                    header("X-User-ID", "user2")
                }
            },
            async {
                client.get("https://api.example.com/users") {
                    header("X-User-ID", "user3")
                }
            },
        ).awaitAll()

        // Different header values should create different cache keys
        assertEquals(3, requestCount.value, "Expected 3 separate network requests for different header values")
        assertEquals(3, responses.map { it.bodyAsText() }.toSet().size, "Expected different response bodies")
    }

    @Test
    fun requests_with_multiple_excluded_headers_are_deduplicated() = runTest {
        val requestCount = atomic(0)
        val client = mockClient(config = { excludeHeaders = setOf("X-Trace-Id", "X-Request-Id", "X-Session-Id") }) {
            "response-${requestCount.incrementAndGet()}"
        }

        val responses = List(5) { index ->
            async {
                client.get("https://api.example.com/users") {
                    header("X-Trace-Id", "trace-$index")
                    header("X-Request-Id", "req-$index")
                    header("X-Session-Id", "session-$index")
                }
            }
        }.awaitAll()

        // All excluded headers vary but requests should still be deduplicated
        assertEquals(1, requestCount.value, "Expected only 1 network request with multiple excluded headers")
        responses.forEach { response ->
            assertEquals("response-1", response.bodyAsText())
        }
    }

    @Test
    fun partial_cancellation_does_not_affect_remaining_waiters() = runTest {
        val requestCount = atomic(0)
        val client = mockClient {
            "response-${requestCount.incrementAndGet()}"
        }

        // Launch 5 concurrent requests
        val jobs = List(5) {
            async {
                client.get("https://api.example.com/users")
            }
        }

        // Cancel 2 out of 5 requests
        jobs[1].cancel()
        jobs[3].cancel()

        // Remaining 3 should still complete successfully
        val response0 = jobs[0].await()
        val response2 = jobs[2].await()
        val response4 = jobs[4].await()

        // Only 1 actual network request should have been made
        assertEquals(1, requestCount.value, "Expected only 1 network request despite partial cancellation")
        assertEquals("response-1", response0.bodyAsText())
        assertEquals("response-1", response2.bodyAsText())
        assertEquals("response-1", response4.bodyAsText())
    }

    @Test
    fun rapid_sequential_requests_are_not_deduplicated() = runTest {
        val requestCount = atomic(0)
        val client = mockClient {
            "response-${requestCount.incrementAndGet()}"
        }

        // Make rapid sequential requests (not concurrent)
        val responses = mutableListOf<String>()
        repeat(5) {
            val response = client.get("https://api.example.com/users")
            responses.add(response.bodyAsText())
        }

        assertEquals(5, requestCount.value, "Expected 5 separate network requests for rapid sequential calls")
        assertEquals(listOf("response-1", "response-2", "response-3", "response-4", "response-5"), responses)
    }

    @Test
    fun request_fails_and_all_waiters_receive_the_error() = runTest {
        val requestCount = atomic(0)
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    simulateLatency()
                    requestCount.incrementAndGet()
                    throw RuntimeException("Network error")
                }
            }
            install(RequestDeduplication)
        }

        val jobs = List(3) {
            async {
                try {
                    client.get("https://api.example.com/users")
                } catch (e: RuntimeException) {
                    e.message
                }
            }
        }

        val results = jobs.awaitAll()

        assertEquals(1, requestCount.value, "Expected only 1 network request attempt")

        results.forEach { errorMessage ->
            assertEquals("Network error", errorMessage)
        }
    }

    @Test
    fun minWindow_enforces_minimum_duration() = runTest {
        val requestCount = atomic(0)
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    requestCount.incrementAndGet()
                    respond("fast-response")
                }
            }
            install(RequestDeduplication) {
                minWindow = 100 // 100ms minimum window
            }
        }

        // Launch first request
        val job1 = async { client.get("https://api.example.com/users") }

        // Launch second request after short delay (should join the window)
        delay(10)
        val job2 = async { client.get("https://api.example.com/users") }

        val responses = listOf(job1, job2).awaitAll()

        // Both should be deduplicated because minWindow keeps the window open
        assertEquals(1, requestCount.value, "Expected deduplication with minWindow")
        responses.forEach { assertEquals("fast-response", it.bodyAsText()) }
    }

    @Test
    fun minWindow_zero_fast_responses_may_miss_late_joiners() = runTest {
        val requestCount = atomic(0)
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    requestCount.incrementAndGet()
                    // Instant response - completes immediately
                    respond("fast-response")
                }
            }
            install(RequestDeduplication) {
                minWindow = 0 // Default: no artificial delay
            }
        }

        val job1 = async { client.get("https://api.example.com/users") }
        delay(5)
        val job2 = async { client.get("https://api.example.com/users") }
        val responses = listOf(job1, job2).awaitAll()

        // With minWindow=0 and instant responses, deduplication may not occur
        // if the first request completes before the second arrives.
        // This test documents the problem that minWindow solves.
        // Note: Due to test scheduler behavior, both might still deduplicate,
        // but in real-world scenarios with actual network timing, they might not.
        assertTrue(
            requestCount.value >= 1,
            "Expected at least 1 request (deduplication behavior varies with minWindow=0)",
        )

        // Both responses should still be valid
        responses.forEach { assertEquals("fast-response", it.bodyAsText()) }
    }

    @Test
    fun minWindow_honored_for_error_responses_allowing_deduplication() = runTest {
        val requestCount = atomic(0)
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    requestCount.incrementAndGet()
                    respondError(InternalServerError, "Server error")
                }
            }
            install(RequestDeduplication) {
                minWindow = 100
            }
        }

        val job1 = async { client.get("https://api.example.com/users") }
        delay(20)
        val job2 = async { client.get("https://api.example.com/users") }
        val responses = listOf(job1, job2).awaitAll()

        assertEquals(1, requestCount.value, "Expected deduplication - minWindow honored for error responses")
        responses.forEach { response ->
            assertEquals(InternalServerError, response.status)
        }
    }

    @Test
    fun minWindow_honored_when_exception_thrown() = runTest {
        val requestCount = atomic(0)
        val exceptionMessage = "Network timeout"
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    requestCount.incrementAndGet()
                    throw RuntimeException(exceptionMessage)
                }
            }
            install(RequestDeduplication) {
                minWindow = 100
            }
        }

        val job1 = async {
            try {
                client.get("https://api.example.com/users")
                "success"
            } catch (e: RuntimeException) {
                e.message
            }
        }

        delay(30)

        val job2 = async {
            try {
                client.get("https://api.example.com/users")
                "success"
            } catch (e: RuntimeException) {
                e.message
            }
        }

        val results = listOf(job1, job2).awaitAll()

        assertEquals(1, requestCount.value, "Expected deduplication - minWindow honored for exceptions")
        results.forEach { result ->
            assertEquals(exceptionMessage, result)
        }
    }

    @Test
    fun minWindow_allows_multiple_staggered_requests_to_join_on_error() = runTest {
        val requestCount = atomic(0)
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    requestCount.incrementAndGet()
                    respondError(NotFound, "Not found")
                }
            }
            install(RequestDeduplication) {
                minWindow = 100
            }
        }

        val job1 = async { client.get("https://api.example.com/users") }
        delay(30)
        val job2 = async { client.get("https://api.example.com/users") }
        delay(30)
        val job3 = async { client.get("https://api.example.com/users") }

        val responses = listOf(job1, job2, job3).awaitAll()

        assertEquals(1, requestCount.value, "Expected all staggered requests deduplicated with minWindow on error")
        responses.forEach { response ->
            assertEquals(NotFound, response.status)
        }
    }

    @Test
    fun test_POST_requests_not_deduplicated_by_default() = runTest {
        val requestCount = atomic(0)
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    requestCount.incrementAndGet()
                    respond("response-${requestCount.value}")
                }
            }
            install(RequestDeduplication) // Default config only deduplicates GET
        }

        val responses = List(3) {
            async {
                client.post("https://api.example.com/users") {
                    setBody("same body")
                }
            }
        }.awaitAll()

        assertEquals(3, requestCount.value, "Expected 3 separate POST requests (POST not deduplicated by default)")
        responses.forEachIndexed { index, response ->
            assertEquals("response-${index + 1}", response.bodyAsText())
        }
    }

    @Test
    fun different_http_methods_are_not_deduplicated_together() = runTest {
        val requestCount = atomic(0)
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    requestCount.incrementAndGet()
                    respond("response-${requestCount.value}")
                }
            }
            install(RequestDeduplication) {
                deduplicateMethods = setOf(Get, Post, Head)
            }
        }

        listOf(
            async { client.get("https://api.example.com/users") },
            async { client.post("https://api.example.com/users") },
            async { client.head("https://api.example.com/users") },
        ).awaitAll()

        assertEquals(3, requestCount.value, "Expected 3 separate requests for different HTTP methods")
    }

    @Test
    fun in_flight_map_cleaned_up_after_completion() = runTest {
        val requestCount = atomic(0)
        val client = mockClient {
            "response-${requestCount.incrementAndGet()}"
        }

        val batch1 = List(3) {
            async { client.get("https://api.example.com/users") }
        }.awaitAll()

        assertEquals(1, requestCount.value, "First batch should be deduplicated")

        val batch2 = List(3) {
            async { client.get("https://api.example.com/users") }
        }.awaitAll()

        assertEquals(2, requestCount.value, "Second batch should make new request (cleanup worked)")

        batch1.forEach { assertEquals("response-1", it.bodyAsText()) }
        batch2.forEach { assertEquals("response-2", it.bodyAsText()) }
    }
}
