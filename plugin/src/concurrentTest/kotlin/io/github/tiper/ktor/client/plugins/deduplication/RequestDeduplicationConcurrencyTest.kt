package io.github.tiper.ktor.client.plugins.deduplication

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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
}
