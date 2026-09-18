package it.danielebufarini.trenify.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpClientFactoryTest {
    @Test
    fun retriesIdempotentServerFailuresWithinConfiguredBound() = runTest {
        var attempts = 0
        val engine = MockEngine {
            attempts += 1
            respond(
                content = "{}",
                status = if (attempts == 1) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val client = createHttpClient(
            engine = engine,
            config = NetworkConfig(maximumGetRetries = 2),
        )

        val response = client.get("https://example.invalid/bootstrap")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, attempts)
        client.close()
    }
}
