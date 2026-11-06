package io.github.tiper.ktor.client.plugins.deduplication

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode.Companion.OK
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeStringUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private const val URL = "https://api.example.com/users"

/**
 * `runTest` queues every resumption on a single thread, so a waiter can never resume *inside* the
 * leader's completion call. Reproducing that requires real threads, hence this test lives in
 * concurrentTest (JVM and native) rather than in commonTest.
 */
class RequestDeduplicationConcurrencyTest {

    @Test
    fun waiter_resumed_inline_by_cancelled_leader_retries_without_recursing() = runBlocking {
        val requestCount = atomic(0)
        val leaderInFlight = CompletableDeferred<Unit>()
        // The leader parks in the engine until it is cancelled, so no amount of scheduling delay on
        // a loaded machine can let it finish early. The retry answers straight away.
        val client = mockClient(timeMillis = 0) {
            val attempt = requestCount.incrementAndGet()
            if (attempt == 1) {
                leaderInFlight.complete(Unit)
                awaitCancellation()
            }
            "response-$attempt"
        }
        val scope = CoroutineScope(SupervisorJob())

        // The leader runs on a worker thread while the waiter is unconfined, so cancelling the
        // leader resumes the waiter synchronously, on the leader's own stack, before the leader
        // gets a chance to drop its in-flight entry. The waiter must retry by looping; retrying by
        // recursion never suspends here and overflows the stack.
        val leader = scope.async(Dispatchers.Default) { client.get(URL).bodyAsText() }
        leaderInFlight.await()
        val waiter = scope.async(Dispatchers.Unconfined) { client.get(URL).bodyAsText() }
        delay(50)

        // A waiter that had not joined would already have hit the engine as its own leader. Assert
        // it here so the test fails loudly instead of quietly stopping to reproduce the crash.
        assertEquals(1, requestCount.value, "Waiter must be parked on the leader's request")

        leader.cancel()

        val response = withTimeout(10_000) { waiter.await() }

        assertEquals("response-2", response, "Waiter should retry the cancelled leader's request")
        assertEquals(2, requestCount.value, "Retry should issue exactly one new request")

        client.close()
        scope.cancel()
    }

    @Test
    fun skipDeduplication_keeps_the_response_streaming() = runBlocking {
        val firstChunkRead = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    val channel = ByteChannel(autoFlush = true)
                    // The stream only ends once the caller has read from it, so a plugin that
                    // buffers the whole body first never gets past this point.
                    launch {
                        channel.writeStringUtf8("chunk")
                        firstChunkRead.await()
                        channel.close()
                    }
                    respond(channel, OK)
                }
            }
            install(RequestDeduplication)
        }

        withTimeout(5_000) {
            client.prepareGet(URL) { skipDeduplication() }.execute { response ->
                val chunk = ByteArray(5)
                response.bodyAsChannel().readAvailable(chunk)
                firstChunkRead.complete(Unit)
                assertEquals("chunk", chunk.decodeToString())
            }
        }

        client.close()
    }
}
