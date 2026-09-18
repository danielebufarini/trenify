package it.danielebufarini.trenify.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.plugin
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

fun createHttpClient(
    engine: HttpClientEngine,
    config: NetworkConfig = NetworkConfig(),
    instrumentation: RequestInstrumentation = RequestInstrumentation.None,
): HttpClient {
    val client = HttpClient(engine) {
        expectSuccess = false

        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                },
            )
        }
        install(HttpTimeout) {
            connectTimeoutMillis = config.connectTimeoutMillis
            requestTimeoutMillis = config.requestTimeoutMillis
            socketTimeoutMillis = config.socketTimeoutMillis
        }
        install(HttpRequestRetry) {
            retryIf(maxRetries = config.maximumGetRetries) { request, response ->
                request.method == HttpMethod.Get && response.status.value >= 500
            }
            retryOnExceptionIf(maxRetries = config.maximumGetRetries) { request, _ ->
                request.method == HttpMethod.Get
            }
            exponentialDelay(randomizationMs = 250)
        }
        defaultRequest {
            accept(ContentType.Application.Json)
            header(HttpHeaders.UserAgent, config.userAgent)
        }
    }

    client.plugin(HttpSend).intercept { request ->
        val method = request.method.value
        val url = request.url.buildString()
        val mark = TimeSource.Monotonic.markNow()
        instrumentation.onRequest(method, url)
        try {
            execute(request).also { call ->
                instrumentation.onResponse(method, url, call.response.status.value, null)
                instrumentation.spanFinished("http.request", mark.elapsedNow(), mapOf(
                    "method" to method,
                    "url" to diagnosticUrl(url),
                    "status" to call.response.status.value.toString(),
                    "outcome" to "success",
                ))
            }
        } catch (failure: Throwable) {
            instrumentation.onResponse(method, url, null, failure)
            instrumentation.spanFinished("http.request", mark.elapsedNow(), mapOf(
                "method" to method,
                "url" to diagnosticUrl(url),
                "failure" to (failure::class.simpleName ?: "Throwable"),
                "outcome" to "failed",
            ))
            throw failure
        }
    }

    return client
}

expect fun createPlatformHttpClientEngine(): HttpClientEngine
