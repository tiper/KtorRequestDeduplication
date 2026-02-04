package io.github.tiper.ktor.client.plugins.deduplication

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders.ContentType
import io.ktor.http.HttpStatusCode.Companion.OK
import io.ktor.http.headersOf
import kotlinx.coroutines.delay

internal suspend fun simulateLatency(timeMillis: Long = 100) = delay(timeMillis)

internal fun mockClient(
    timeMillis: Long = 100,
    config: RequestDeduplicationConfig.() -> Unit = {},
    responseProvider: suspend () -> String,
) = HttpClient(MockEngine) {
    engine {
        addHandler {
            simulateLatency(timeMillis)
            respond(
                content = responseProvider(),
                status = OK,
                headers = headersOf(ContentType, "text/plain"),
            )
        }
    }
    install(RequestDeduplication, config)
}
