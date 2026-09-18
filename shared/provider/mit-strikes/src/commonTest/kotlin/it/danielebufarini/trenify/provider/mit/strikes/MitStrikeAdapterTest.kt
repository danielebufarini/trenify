package it.danielebufarini.trenify.provider.mit.strikes

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import it.danielebufarini.trenify.core.model.StrikeRelevance
import it.danielebufarini.trenify.core.model.StrikeStatus
import it.danielebufarini.trenify.core.network.NetworkConfig
import it.danielebufarini.trenify.core.network.createHttpClient
import it.danielebufarini.trenify.core.provider.api.ProviderFailure
import it.danielebufarini.trenify.core.provider.api.ProviderResult
import it.danielebufarini.trenify.core.provider.api.ProviderStrike
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class MitStrikeAdapterTest {
    @Test
    fun officialRssFixturePreservesIdentityAndNormalizesRailwayEntries() = runTest {
        withAdapter(FIXTURE) { adapter, engine ->
            val result = assertIs<ProviderResult.Success<List<ProviderStrike>>>(
                adapter.getStrikes(
                    Instant.parse("2026-09-01T00:00:00Z"),
                    Instant.parse("2026-10-01T00:00:00Z"),
                ),
            )
            assertEquals(2, result.value.size)
            val strike = result.value.first()
            assertEquals("8479", strike.externalId)
            assertEquals(Instant.parse("2026-09-07T19:18:00Z"), strike.start)
            assertEquals(Instant.parse("2026-09-08T19:00:00Z"), strike.end)
            assertEquals(StrikeRelevance.REGIONAL, strike.relevance)
            assertEquals(listOf("Piemonte"), strike.regions)
            assertEquals(listOf("Trenitalia"), strike.operators.map { it.name })
            assertEquals("application/rss+xml, application/xml;q=0.9", engine.requestHistory.single().headers[HttpHeaders.Accept])
            assertEquals(MitStrikeAdapter.RSS_URL, engine.requestHistory.single().url.toString())
        }
    }

    @Test
    fun rangeRevocationAndNonRailwayFilteringAreDeterministic() = runTest {
        withAdapter(FIXTURE) { adapter, _ ->
            val september7 = assertIs<ProviderResult.Success<List<ProviderStrike>>>(
                adapter.getStrikes(
                    Instant.parse("2026-09-07T00:00:00Z"),
                    Instant.parse("2026-09-09T00:00:00Z"),
                    includeRevoked = false,
                ),
            )
            assertEquals(listOf("8479"), september7.value.map { it.externalId })
            val includingRevoked = assertIs<ProviderResult.Success<List<ProviderStrike>>>(
                adapter.getStrikes(
                    Instant.parse("2026-09-09T00:00:00Z"),
                    Instant.parse("2026-09-11T00:00:00Z"),
                    includeRevoked = true,
                ),
            )
            assertEquals(StrikeStatus.REVOKED, includingRevoked.value.single().status)
        }
    }

    @Test
    fun malformedPayloadAndHttpFailuresAreControlled() = runTest {
        withAdapter("<html>unavailable</html>") { adapter, _ ->
            val result = assertIs<ProviderResult.Unavailable>(
                adapter.getStrikes(Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z")),
            )
            assertEquals(ProviderFailure.PARSING, result.cause)
        }
        withAdapter("", HttpStatusCode.ServiceUnavailable) { adapter, _ ->
            assertEquals(
                ProviderFailure.UNAVAILABLE,
                assertIs<ProviderResult.Unavailable>(
                    adapter.getStrikes(Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z")),
                ).cause,
            )
        }
        val timeoutClient = HttpClient(MockEngine { throw HttpRequestTimeoutException("https://test", 1) })
        try {
            assertEquals(
                ProviderFailure.TIMEOUT,
                assertIs<ProviderResult.Unavailable>(
                    MitStrikeAdapter(timeoutClient).getStrikes(
                        Instant.parse("2026-09-01T00:00:00Z"),
                        Instant.parse("2026-10-01T00:00:00Z"),
                    ),
                ).cause,
            )
        } finally {
            timeoutClient.close()
        }
        val cancelledClient = HttpClient(MockEngine { throw CancellationException("cancelled") })
        try {
            kotlin.test.assertFailsWith<CancellationException> {
                MitStrikeAdapter(cancelledClient).getStrikes(
                    Instant.parse("2026-09-01T00:00:00Z"),
                    Instant.parse("2026-10-01T00:00:00Z"),
                )
            }
        } finally {
            cancelledClient.close()
        }
    }

    @Test
    fun exactBoundaryStrikesAreReturnedForRequestedInterval() = runTest {
        // T7.12 inclusive overlap: touching boundaries count. Fixture strike
        // 8479 spans 2026-09-07T19:18Z..2026-09-08T19:00Z.
        val strikeStart = Instant.parse("2026-09-07T19:18:00Z")
        val strikeEnd = Instant.parse("2026-09-08T19:00:00Z")
        withAdapter(FIXTURE) { adapter, _ ->
            val endingAtStart = assertIs<ProviderResult.Success<List<ProviderStrike>>>(
                adapter.getStrikes(strikeEnd, strikeEnd + 1.hours),
            )
            assertEquals(listOf("8479"), endingAtStart.value.map { it.externalId })
            val startingAtEnd = assertIs<ProviderResult.Success<List<ProviderStrike>>>(
                adapter.getStrikes(strikeStart - 1.hours, strikeStart),
            )
            assertEquals(listOf("8479"), startingAtEnd.value.map { it.externalId })
        }
    }

    @Test
    fun completeSnapshotReportsComplete() = runTest {
        withAdapter(FIXTURE) { adapter, _ ->
            val result = assertIs<ProviderResult.Success<List<ProviderStrike>>>(
                adapter.getStrikes(Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z")),
            )
            assertTrue(result.complete)
        }
    }

    @Test
    fun partialSnapshotReportsIncompleteWhileKeepingDecodableRecords() = runTest {
        val partial = FIXTURE.replace(
            "</channel>",
            """<item><title>Undecodable entry</title><description>no dates here</description>
                |<guid>http://scioperi.mit.gov.it/0000</guid></item></channel>""".trimMargin(),
        )
        withAdapter(partial) { adapter, _ ->
            val result = assertIs<ProviderResult.Success<List<ProviderStrike>>>(
                adapter.getStrikes(Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z")),
            )
            // Decodable records still surface, but the snapshot is not
            // complete: repositories must not revoke by absence on it.
            assertEquals(listOf("8479", "8500"), result.value.map { it.externalId })
            assertEquals(false, result.complete)
        }
    }

    private suspend fun withAdapter(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        block: suspend (MitStrikeAdapter, MockEngine) -> Unit,
    ) {
        val engine = MockEngine {
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/rss+xml; charset=UTF-8"))
        }
        val client = createHttpClient(engine, NetworkConfig(maximumGetRetries = 0))
        try {
            block(MitStrikeAdapter(client), engine)
        } finally {
            client.close()
        }
    }
}

private val FIXTURE = """
    <?xml version="1.0" encoding="UTF-8"?>
    <rss version="2.0"><channel>
      <title>MIT strikes</title>
      <item>
        <title>Data inizio: 07/09/2026 - Settore: Ferroviario - Rilevanza: Regionale - Regione: Piemonte - Provincia: Tutte</title>
        <description><![CDATA[modalità: 24 ORE: DALLE 21.18 DEL 7/9 ALLE 21.00 DELL'8/9<br/>Data fine: 08/09/2026<br/>Settore: Ferroviario<br/>Rilevanza: Regionale<br/>Regione: Piemonte<br/>Provincia: Tutte<br/>Sindacati: ORSA FERROVIE<br/>Categoria interessata: PERSONALE SOC. TRENITALIA]]></description>
        <guid>http://scioperi.mit.gov.it/8479</guid>
      </item>
      <item>
        <title>Data inizio: 10/09/2026 - Settore: Ferroviario - Rilevanza: Nazionale - Regione: Italia - Provincia: Tutte</title>
        <description><![CDATA[modalità: 4 ORE DALLE 09.00 ALLE 13.00<br/>Data fine: 10/09/2026<br/>Settore: Ferroviario<br/>Rilevanza: Nazionale<br/>Regione: Italia<br/>Provincia: Tutte<br/>Categoria interessata: PERSONALE FERROVIARIO<br/>Note: SCIOPERO REVOCATO]]></description>
        <guid>http://scioperi.mit.gov.it/8500</guid>
      </item>
      <item>
        <title>Data inizio: 11/09/2026 - Settore: Aereo - Rilevanza: Nazionale - Regione: Italia - Provincia: Tutte</title>
        <description><![CDATA[modalità: DALLE 09.00 ALLE 13.00<br/>Data fine: 11/09/2026<br/>Settore: Aereo<br/>Categoria interessata: PILOTI]]></description>
        <guid>http://scioperi.mit.gov.it/9999</guid>
      </item>
    </channel></rss>
""".trimIndent()
