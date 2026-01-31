package io.github.tiper.ktor.client.plugins.deduplication

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders.ContentType
import io.ktor.http.HttpStatusCode.Companion.OK
import io.ktor.http.headersOf
import kotlinx.coroutines.delay

internal suspend fun simulateLatency() = delay(100)

internal fun mockClient(
    config: RequestDeduplicationConfig.() -> Unit = {},
    responseProvider: suspend () -> String,
) = HttpClient(MockEngine) {
    engine {
        addHandler {
            simulateLatency()
            respond(
                content = responseProvider(),
                status = OK,
                headers = headersOf(ContentType, "text/plain"),
            )
        }
    }
    install(RequestDeduplication, config)
}
