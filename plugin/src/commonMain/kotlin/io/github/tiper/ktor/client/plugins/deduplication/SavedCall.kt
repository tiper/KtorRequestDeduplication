package io.github.tiper.ktor.client.plugins.deduplication

import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.request.HttpRequest
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readBytes
import io.ktor.http.Headers
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.util.InternalAPI
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import kotlin.coroutines.CoroutineContext

internal class SavedHttpCall(
    client: HttpClient,
    request: HttpRequest,
    response: HttpResponse,
    private val responseBody: ByteArray,
) : HttpClientCall(client) {

    init {
        this.request = SavedHttpRequest(this, request)
        this.response = SavedHttpResponse(this, responseBody, response)
    }

    /**
     * Returns a channel with [responseBody] data.
     */
    override suspend fun getResponseContent(): ByteReadChannel = ByteReadChannel(responseBody)

    override val allowDoubleReceive: Boolean = true
}

internal class SavedHttpRequest(
    override val call: SavedHttpCall,
    origin: HttpRequest,
) : HttpRequest by origin

internal class SavedHttpResponse(
    override val call: SavedHttpCall,
    private val body: ByteArray,
    origin: HttpResponse,
) : HttpResponse() {
    override val status: HttpStatusCode = origin.status

    override val version: HttpProtocolVersion = origin.version

    override val requestTime: GMTDate = origin.requestTime

    override val responseTime: GMTDate = origin.responseTime

    override val headers: Headers = origin.headers

    override val coroutineContext: CoroutineContext = origin.coroutineContext

    @OptIn(InternalAPI::class)
    override val content: ByteReadChannel get() = ByteReadChannel(body)
}

internal suspend fun HttpClientCall.save(): HttpClientCall = SavedHttpCall(client, request, response, response.readBytes())
