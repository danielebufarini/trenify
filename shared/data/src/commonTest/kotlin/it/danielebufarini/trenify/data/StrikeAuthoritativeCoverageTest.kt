package it.danielebufarini.trenify.data

import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.StrikeRefresh
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Strike
import it.danielebufarini.trenify.core.model.StrikeStatus
import it.danielebufarini.trenify.core.provider.api.ProviderResult
import it.danielebufarini.trenify.core.testing.FakeStrikeProvider
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testProviderStrike
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * T7.12 corrective pass, Blocker 3: a coverage row that certifies an
 * interval as fresh/empty is established only when the response is
 * parse-complete AND the provider contract says absence is authoritative.
 * Presence is still upserted either way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrikeAuthoritativeCoverageTest {
    private val intervalE = Instant.parse("2026-09-07T12:00:00Z") to Instant.parse("2026-09-09T00:00:00Z")

    private suspend fun kotlinx.coroutines.test.TestScope.withRepository(
        block: suspend kotlinx.coroutines.test.TestScope.(MutableClock, FakeStrikeProvider, SqlDelightStrikeRepository) -> Unit,
    ) {
        val driver = createRepositoryTestDriver()
        val database = TrenifyDatabase(driver)
        val clock = MutableClock()
        val provider = FakeStrikeProvider(clock)
        val repository = SqlDelightStrikeRepository(
            database,
            provider,
            backgroundScope,
            clock = clock,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            block(clock, provider, repository)
        } finally {
            driver.close()
        }
    }

    @Test
    fun nonAuthoritativeEmptySuccessEstablishesNoCoverage() = runTest {
        withRepository { _, provider, repository ->
            provider.suppliesCompleteSnapshots = false
            provider.values = emptyList()
            val refreshed = assertIs<DataResult.Data<StrikeRefresh>>(
                repository.refresh(intervalE.first, intervalE.second),
            )
            assertNull(refreshed.value.coverage)

            // The interval stays uncovered/unknown: an ordinary non-forced
            // load is still allowed to fetch it.
            val observed = assertIs<DataResult.Data<List<Strike>>>(
                repository.observeStrikes(intervalE.first, intervalE.second).first(),
            )
            assertTrue(observed.value.isEmpty())
            assertIs<DataFreshness.Unknown>(observed.freshness)
            repository.refresh(intervalE.first, intervalE.second)
            assertEquals(2, provider.calls)
        }
    }

    @Test
    fun nonAuthoritativeSuccessStillUpsertsPresenceWithoutCertifying() = runTest {
        withRepository { _, provider, repository ->
            provider.suppliesCompleteSnapshots = false
            provider.values = listOf(testProviderStrike)
            val refreshed = assertIs<DataResult.Data<StrikeRefresh>>(
                repository.refresh(intervalE.first, intervalE.second),
            )
            assertNull(refreshed.value.coverage)
            val observed = assertIs<DataResult.Data<List<Strike>>>(
                repository.observeStrikes(intervalE.first, intervalE.second).first(),
            )
            assertEquals(listOf("8479"), observed.value.map { it.externalId })
            assertIs<DataFreshness.Unknown>(observed.freshness)
        }
    }

    @Test
    fun nonAuthoritativeResponseDoesNotRevokeCachedStrikes() = runTest {
        withRepository { _, provider, repository ->
            provider.values = listOf(testProviderStrike)
            repository.refresh(intervalE.first, intervalE.second)
            repository.setNotificationsEnabled(true)

            provider.suppliesCompleteSnapshots = false
            provider.values = emptyList()
            val refreshed = assertIs<DataResult.Data<StrikeRefresh>>(
                repository.refresh(intervalE.first, intervalE.second, force = true),
            )
            assertNull(refreshed.value.coverage)
            assertEquals(
                StrikeStatus.SCHEDULED,
                refreshed.value.strikes.single { it.externalId == "8479" }.status,
            )
            assertTrue(repository.pendingNotifications().isEmpty())
        }
    }

    @Test
    fun nonAuthoritativeNotFoundEstablishesNoCoverage() = runTest {
        withRepository { _, provider, repository ->
            provider.suppliesCompleteSnapshots = false
            provider.result = ProviderResult.NotFound
            val refreshed = assertIs<DataResult.Data<StrikeRefresh>>(
                repository.refresh(intervalE.first, intervalE.second),
            )
            assertNull(refreshed.value.coverage)
            assertIs<DataFreshness.Unknown>(
                assertIs<DataResult.Data<List<Strike>>>(
                    repository.observeStrikes(intervalE.first, intervalE.second).first(),
                ).freshness,
            )
            repository.refresh(intervalE.first, intervalE.second)
            assertEquals(2, provider.calls)
        }
    }

    @Test
    fun authoritativeEmptySuccessRetainsCoverageBehavior() = runTest {
        withRepository { _, provider, repository ->
            provider.values = emptyList()
            val refreshed = assertIs<DataResult.Data<StrikeRefresh>>(
                repository.refresh(intervalE.first, intervalE.second),
            )
            assertEquals(intervalE.first, refreshed.value.coverage?.from)
            assertIs<DataFreshness.Fresh>(
                assertIs<DataResult.Data<List<Strike>>>(
                    repository.observeStrikes(intervalE.first, intervalE.second).first(),
                ).freshness,
            )
            repository.refresh(intervalE.first, intervalE.second)
            assertEquals(1, provider.calls)
        }
    }

    @Test
    fun authoritativeNotFoundRetainsCoverageBehavior() = runTest {
        withRepository { _, provider, repository ->
            provider.result = ProviderResult.NotFound
            val refreshed = assertIs<DataResult.Data<StrikeRefresh>>(
                repository.refresh(intervalE.first, intervalE.second),
            )
            assertEquals(intervalE.first, refreshed.value.coverage?.from)
            assertIs<DataFreshness.Fresh>(refreshed.freshness)
            // Absence from an authoritative provider revokes overlapping cache.
            assertTrue(refreshed.value.strikes.isEmpty())
        }
    }
}
