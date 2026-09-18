package it.danielebufarini.trenify.core.network

import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/**
 * Exactly one diagnostic URL path (T8 corrective): query strings and
 * provider operation ids must reach no instrumentation event in any phase.
 */
class RequestInstrumentationSanitizationTest {
    private val operationUrl = "https://api-biglietti.italotreno.com/api/v1/booking/status/abc123?foo=bar"
    private val sanitizedOperationUrl = "https://api-biglietti.italotreno.com/api/v1/booking/status/{operation}"

    @Test fun canonicalSanitizerRemovesQueryAndOperationId() {
        assertEquals(sanitizedOperationUrl, diagnosticUrl(operationUrl))
    }

    @Test fun ordinaryUrlsKeepPathWithoutQuery() {
        assertEquals(
            "https://www.lefrecce.it/x/locations/search",
            diagnosticUrl("https://www.lefrecce.it/x/locations/search?name=Monza&limit=30"),
        )
    }

    @Test fun allRequestLifecycleEventsAreSanitized() = runTest {
        val seen = mutableListOf<LatencyEvent>()
        createHttpClient(
            MockEngine { respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) },
            instrumentation = RecordingRequestInstrumentation(seen::add),
        ).use { client ->
            client.get(operationUrl)
        }
        assertEquals(setOf("start", "response", "finish"), seen.map { it.phase }.toSet())
        val urls = seen.mapNotNull { it.attributes["url"] }
        assertTrue(urls.isNotEmpty())
        assertTrue(urls.none { "abc123" in it || "foo=bar" in it },
            "unsanitized URL in events: $urls")
        assertTrue(urls.all { it == sanitizedOperationUrl })
    }

    @Test fun failureEventsAreSanitized() = runTest {
        val seen = mutableListOf<LatencyEvent>()
        createHttpClient(
            MockEngine { throw IllegalStateException("wire down") },
            instrumentation = RecordingRequestInstrumentation(seen::add),
        ).use { client ->
            assertFailsWith<IllegalStateException> { client.get(operationUrl) }
        }
        assertEquals(setOf("start", "response", "finish"), seen.map { it.phase }.toSet())
        val urls = seen.mapNotNull { it.attributes["url"] }
        assertTrue(urls.isNotEmpty())
        assertTrue(urls.none { "abc123" in it || "foo=bar" in it },
            "unsanitized URL in failure events: $urls")
        assertTrue(urls.all { it == sanitizedOperationUrl })
    }
}
