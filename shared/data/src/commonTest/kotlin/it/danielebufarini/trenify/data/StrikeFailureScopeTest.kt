package it.danielebufarini.trenify.data

import app.cash.sqldelight.db.SqlDriver
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.StrikeRefresh
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Strike
import it.danielebufarini.trenify.core.provider.api.ProviderFailure
import it.danielebufarini.trenify.core.provider.api.ProviderResult
import it.danielebufarini.trenify.core.testing.FakeStrikeProvider
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testProviderStrike
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * T7.12 corrective pass, Blocker 2: refresh failures are scoped to the
 * exact requested interval. A failure on B never contaminates A, a later
 * success on A never clears B, and only an applicable success on B clears
 * B. Overlapping but non-identical requests follow exact-key semantics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrikeFailureScopeTest {
    private val intervalA = Instant.parse("2026-09-07T00:00:00Z") to Instant.parse("2026-09-08T00:00:00Z")
    private val intervalB = Instant.parse("2026-09-10T00:00:00Z") to Instant.parse("2026-09-11T00:00:00Z")
    // D overlaps A but is contained in no coverage row of A: exact-key
    // semantics give it an independent failure slot.
    private val overlappingD = Instant.parse("2026-09-07T12:00:00Z") to Instant.parse("2026-09-09T00:00:00Z")

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

    private fun fail(provider: FakeStrikeProvider) {
        provider.result = ProviderResult.Unavailable(true, ProviderFailure.TRANSPORT)
    }

    @Test
    fun disjointFailureDoesNotContaminateFreshInterval() = runTest {
        withRepository { _, provider, repository ->
            repository.refresh(intervalA.first, intervalA.second)
            fail(provider)
            val failedB = assertIs<DataResult.Data<StrikeRefresh>>(
                repository.refresh(intervalB.first, intervalB.second),
            )
            assertTrue(failedB.value.strikes.isEmpty())
            assertIs<DataFreshness.Unknown>(failedB.freshness)
            assertEquals(DomainFailure.TEMPORARY, failedB.warning)

            // A stays Fresh with no warning; B exposes its own failure.
            val freshA = assertIs<DataResult.Data<List<Strike>>>(
                repository.observeStrikes(intervalA.first, intervalA.second).first(),
            )
            assertEquals(1, freshA.value.size)
            assertIs<DataFreshness.Fresh>(freshA.freshness)
            assertNull(freshA.warning)
            val unknownB = assertIs<DataResult.Data<List<Strike>>>(
                repository.observeStrikes(intervalB.first, intervalB.second).first(),
            )
            assertEquals(DomainFailure.TEMPORARY, unknownB.warning)
        }
    }

    @Test
    fun simultaneousObserversSeeIsolatedStates() = runTest {
        withRepository { _, provider, repository ->
            val emissionsA = mutableListOf<DataResult<List<Strike>>>()
            val emissionsB = mutableListOf<DataResult<List<Strike>>>()
            val jobA = launch { repository.observeStrikes(intervalA.first, intervalA.second).collect { emissionsA += it } }
            val jobB = launch { repository.observeStrikes(intervalB.first, intervalB.second).collect { emissionsB += it } }
            runCurrent()
            repository.refresh(intervalA.first, intervalA.second)
            runCurrent()
            fail(provider)
            repository.refresh(intervalB.first, intervalB.second)
            runCurrent()
            jobA.cancel()
            jobB.cancel()

            val lastA = assertIs<DataResult.Data<List<Strike>>>(emissionsA.last())
            assertIs<DataFreshness.Fresh>(lastA.freshness)
            assertNull(lastA.warning)
            val lastB = assertIs<DataResult.Data<List<Strike>>>(emissionsB.last())
            assertIs<DataFreshness.Unknown>(lastB.freshness)
            assertEquals(DomainFailure.TEMPORARY, lastB.warning)
        }
    }

    @Test
    fun successOnADoesNotClearBFailureButApplicableSuccessOnBClearsIt() = runTest {
        withRepository { _, provider, repository ->
            repository.refresh(intervalA.first, intervalA.second)
            fail(provider)
            repository.refresh(intervalB.first, intervalB.second)

            // A later success on A leaves B's still-relevant failure alone.
            repository.refresh(intervalA.first, intervalA.second, force = true)
            assertEquals(
                DomainFailure.TEMPORARY,
                assertIs<DataResult.Data<List<Strike>>>(
                    repository.observeStrikes(intervalB.first, intervalB.second).first(),
                ).warning,
            )

            // An applicable success on B clears B's failure.
            provider.result = null
            val recoveredB = assertIs<DataResult.Data<StrikeRefresh>>(
                repository.refresh(intervalB.first, intervalB.second, force = true),
            )
            assertNull(recoveredB.warning)
            val observedB = assertIs<DataResult.Data<List<Strike>>>(
                repository.observeStrikes(intervalB.first, intervalB.second).first(),
            )
            assertNull(observedB.warning)
            assertIs<DataFreshness.Fresh>(observedB.freshness)
        }
    }

    @Test
    fun failureOnCoveredIntervalRetainsStaleCacheWithoutTouchingOtherIntervals() = runTest {
        withRepository { clock, provider, repository ->
            repository.refresh(intervalA.first, intervalA.second)
            clock.instant += 46.minutes
            fail(provider)
            val staleA = assertIs<DataResult.Data<StrikeRefresh>>(
                repository.refresh(intervalA.first, intervalA.second),
            )
            assertEquals(1, staleA.value.strikes.size)
            assertIs<DataFreshness.Stale>(staleA.freshness)
            assertEquals(DomainFailure.TEMPORARY, staleA.warning)

            // A disjoint observer sees neither A's data nor A's failure.
            val unknownB = assertIs<DataResult.Data<List<Strike>>>(
                repository.observeStrikes(intervalB.first, intervalB.second).first(),
            )
            assertTrue(unknownB.value.isEmpty())
            assertNull(unknownB.warning)
            assertIs<DataFreshness.Unknown>(unknownB.freshness)
        }
    }

    @Test
    fun overlappingButNonIdenticalRequestsFollowExactKeySemantics() = runTest {
        withRepository { _, provider, repository ->
            repository.refresh(intervalA.first, intervalA.second)
            fail(provider)
            repository.refresh(intervalA.first, intervalA.second, force = true)

            // Overlapping interval D never inherited A's exact-key failure.
            val overlapping = assertIs<DataResult.Data<List<Strike>>>(
                repository.observeStrikes(overlappingD.first, overlappingD.second).first(),
            )
            assertNull(overlapping.warning)

            // D is uncovered, so its own targeted fetch runs against the
            // still-failing provider: D records its own failure while A's
            // failure stays put.
            repository.refresh(overlappingD.first, overlappingD.second)
            assertEquals(
                DomainFailure.TEMPORARY,
                assertIs<DataResult.Data<List<Strike>>>(
                    repository.observeStrikes(overlappingD.first, overlappingD.second).first(),
                ).warning,
            )
            assertEquals(
                DomainFailure.TEMPORARY,
                assertIs<DataResult.Data<List<Strike>>>(
                    repository.observeStrikes(intervalA.first, intervalA.second).first(),
                ).warning,
            )

            // Recovering D clears only D.
            provider.result = null
            repository.refresh(overlappingD.first, overlappingD.second, force = true)
            assertNull(
                assertIs<DataResult.Data<List<Strike>>>(
                    repository.observeStrikes(overlappingD.first, overlappingD.second).first(),
                ).warning,
            )
            assertEquals(
                DomainFailure.TEMPORARY,
                assertIs<DataResult.Data<List<Strike>>>(
                    repository.observeStrikes(intervalA.first, intervalA.second).first(),
                ).warning,
            )
        }
    }
}
