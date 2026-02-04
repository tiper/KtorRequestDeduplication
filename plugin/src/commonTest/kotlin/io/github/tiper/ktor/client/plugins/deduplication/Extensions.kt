package io.github.tiper.ktor.client.plugins.deduplication

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders.ContentType
import io.ktor.http.HttpStatusCode.Companion.OK
import io.ktor.http.headersOf

internal fun mockClient(
    config: RequestDeduplicationConfig.() -> Unit = { minWindow = 100 },
    responseProvider: suspend () -> String,
) = HttpClient(MockEngine) {
    engine {
        addHandler {
            respond(
                content = responseProvider(),
                status = OK,
                headers = headersOf(ContentType, "text/plain"),
            )
        }
    }
    install(RequestDeduplication, config)
}
