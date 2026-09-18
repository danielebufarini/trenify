package it.danielebufarini.trenify.data

import app.cash.sqldelight.db.SqlDriver
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.StrikeChangeKind
import it.danielebufarini.trenify.core.domain.StrikeRefresh
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Strike
import it.danielebufarini.trenify.core.model.StrikeStatus
import it.danielebufarini.trenify.core.provider.api.ProviderFailure
import it.danielebufarini.trenify.core.provider.api.ProviderResult
import it.danielebufarini.trenify.core.provider.api.ProviderStrike
import it.danielebufarini.trenify.core.provider.api.StrikeProvider
import it.danielebufarini.trenify.core.testing.FakeStrikeProvider
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testProviderStrike
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * T7.12-A strike cache coverage, completeness and revocation correctness on
 * the real SQLDelight driver: freshness is per requested interval, partial
 * snapshots never revoke by absence, and failures retain truthful state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrikeCoverageRevocationTest {
    private val intervalA = Instant.parse("2026-09-07T00:00:00Z") to Instant.parse("2026-09-08T00:00:00Z")
    private val intervalB = Instant.parse("2026-09-10T00:00:00Z") to Instant.parse("2026-09-11T00:00:00Z")
    private val containedC = Instant.parse("2026-09-07T18:00:00Z") to Instant.parse("2026-09-07T23:00:00Z")
    private val partialD = Instant.parse("2026-09-07T12:00:00Z") to Instant.parse("2026-09-09T00:00:00Z")

    private val secondStrike = testProviderStrike.copy(
        externalId = "8480",
        start = Instant.parse("2026-09-07T20:00:00Z"),
        end = Instant.parse("2026-09-08T10:00:00Z"),
        mode = "8 ore",
    )

    private suspend fun kotlinx.coroutines.test.TestScope.withRepository(
        block: suspend kotlinx.coroutines.test.TestScope.(
            SqlDriver,
            TrenifyDatabase,
            MutableClock,
            FakeStrikeProvider,
            SqlDelightStrikeRepository,
        ) -> Unit,
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
            block(driver, database, clock, provider, repository)
        } finally {
            driver.close()
        }
    }

    @Test
    fun disjointIntervalStaysUnknownAndTriggersTargetedRefresh() = runTest {
        withRepository { _, _, _, provider, repository ->
            val covered = assertIs<DataResult.Data<StrikeRefresh>>(
                repository.refresh(intervalA.first, intervalA.second),
            )
            assertEquals(intervalA.first, covered.value.coverage?.from)
            assertEquals(intervalA.second, covered.value.coverage?.to)
            assertEquals(1, provider.calls)

            // Disjoint B is unknown — not fresh-empty — however recent A's refresh was.
            val before = assertIs<DataResult.Data<List<Strike>>>(
                repository.observeStrikes(intervalB.first, intervalB.second).first(),
            )
            assertTrue(before.value.isEmpty())
            assertIs<DataFreshness.Unknown>(before.freshness)

            // The targeted refresh actually fetches B and certifies only B.
            val refreshedB = assertIs<DataResult.Data<StrikeRefresh>>(
                repository.refresh(intervalB.first, intervalB.second),
            )
            assertEquals(2, provider.calls)
            assertEquals(intervalB.first to intervalB.second, provider.requestedIntervals.last())
            assertEquals(intervalB.first, refreshedB.value.coverage?.from)
            assertIs<DataFreshness.Fresh>(
                assertIs<DataResult.Data<List<Strike>>>(
                    repository.observeStrikes(intervalB.first, intervalB.second).first(),
                ).freshness,
            )

            // B is now covered: an unforced refresh reuses it without fetching.
            repository.refresh(intervalB.first, intervalB.second)
            assertEquals(2, provider.calls)
        }
    }

    @Test
    fun containedCoverageIsFreshWhilePartialOverlapRefetches() = runTest {
        withRepository { _, _, _, provider, repository ->
            repository.refresh(intervalA.first, intervalA.second)
            assertEquals(1, provider.calls)

            val contained = assertIs<DataResult.Data<List<Strike>>>(
                repository.observeStrikes(containedC.first, containedC.second).first(),
            )
            assertIs<DataFreshness.Fresh>(contained.freshness)
            assertEquals(1, contained.value.size)
            repository.refresh(containedC.first, containedC.second)
            assertEquals(1, provider.calls)

            // D overlaps A but is not contained in it: no reuse of A's freshness.
            val overlapping = assertIs<DataResult.Data<List<Strike>>>(
                repository.observeStrikes(partialD.first, partialD.second).first(),
            )
            assertIs<DataFreshness.Unknown>(overlapping.freshness)
            repository.refresh(partialD.first, partialD.second)
            assertEquals(2, provider.calls)
            assertIs<DataFreshness.Fresh>(
                assertIs<DataResult.Data<List<Strike>>>(
                    repository.observeStrikes(partialD.first, partialD.second).first(),
                ).freshness,
            )
        }
    }

    @Test
    fun partialParseRetainsSkippedCachedStrikeWithoutFalseRevocation() = runTest {
        withRepository { _, _, clock, provider, repository ->
            provider.values = listOf(testProviderStrike, secondStrike)
            val seeded = assertIs<DataResult.Data<StrikeRefresh>>(
                repository.refresh(intervalA.first, intervalA.second),
            )
            assertEquals(2, seeded.value.strikes.size)
            repository.setNotificationsEnabled(true)

            // Partial snapshot after the seed coverage expired: the second
            // record was skipped while parsing.
            clock.instant += 46.minutes
            provider.values = listOf(testProviderStrike)
            provider.complete = false
            val partial = assertIs<DataResult.Data<StrikeRefresh>>(
                repository.refresh(intervalA.first, intervalA.second, force = true),
            )
            assertNull(partial.value.coverage)
            val retained = partial.value.strikes.single { it.externalId == "8480" }
            assertEquals(StrikeStatus.SCHEDULED, retained.status)
            // No false revocation notification for the skipped strike.
            assertTrue(repository.pendingNotifications().none {
                it.kind == StrikeChangeKind.REVOKED && it.strike.externalId == "8480"
            })
            // The interval is not certified either: stale, never fresh.
            assertIs<DataFreshness.Stale>(
                assertIs<DataResult.Data<List<Strike>>>(
                    repository.observeStrikes(intervalA.first, intervalA.second).first(),
                ).freshness,
            )
        }
    }

    @Test
    fun completeSnapshotPermitsAbsenceBasedRevocation() = runTest {
        withRepository { _, _, _, provider, repository ->
            provider.values = listOf(testProviderStrike, secondStrike)
            repository.refresh(intervalA.first, intervalA.second)
            repository.setNotificationsEnabled(true)

            provider.values = listOf(testProviderStrike)
            provider.complete = true
            val revoked = assertIs<DataResult.Data<StrikeRefresh>>(
                repository.refresh(intervalA.first, intervalA.second, force = true),
            )
            assertEquals(StrikeStatus.REVOKED, revoked.value.strikes.single { it.externalId == "8480" }.status)
            assertEquals(
                StrikeChangeKind.REVOKED,
                repository.pendingNotifications().single { it.strike.externalId == "8480" }.kind,
            )
            // A complete snapshot certifies its interval.
            assertEquals(intervalA.first, revoked.value.coverage?.from)
            assertIs<DataFreshness.Fresh>(revoked.freshness)
        }
    }

    @Test
    fun explicitRevocationAppliesEvenInsidePartialSnapshot() = runTest {
        withRepository { _, _, _, provider, repository ->
            provider.values = listOf(testProviderStrike, secondStrike)
            repository.refresh(intervalA.first, intervalA.second)
            repository.setNotificationsEnabled(true)

            // Explicit revocation evidence travels inside the partial response;
            // the omitted-but-unmentioned first strike must be retained.
            provider.values = listOf(secondStrike.copy(status = StrikeStatus.REVOKED))
            provider.complete = false
            val applied = assertIs<DataResult.Data<StrikeRefresh>>(
                repository.refresh(intervalA.first, intervalA.second, force = true),
            )
            assertNull(applied.value.coverage)
            assertEquals(StrikeStatus.REVOKED, applied.value.strikes.single { it.externalId == "8480" }.status)
            assertEquals(
                StrikeChangeKind.REVOKED,
                repository.pendingNotifications().single { it.strike.externalId == "8480" }.kind,
            )
            assertEquals(
                StrikeStatus.SCHEDULED,
                applied.value.strikes.single { it.externalId == "8479" }.status,
            )
        }
    }

    @Test
    fun refreshFailureRetainsCacheWithTruthfulFreshness() = runTest {
        withRepository { _, _, clock, provider, repository ->
            repository.refresh(intervalA.first, intervalA.second)

            clock.instant += 46.minutes
            provider.result = ProviderResult.Unavailable(true, ProviderFailure.TRANSPORT)
            val staleFallback = assertIs<DataResult.Data<StrikeRefresh>>(
                repository.refresh(intervalA.first, intervalA.second),
            )
            assertEquals(1, staleFallback.value.strikes.size)
            assertEquals(DomainFailure.TEMPORARY, staleFallback.warning)
            assertIs<DataFreshness.Stale>(staleFallback.freshness)

            // A never-covered interval keeps unknown (not fresh, not stale)
            // freshness with the failure attached, instead of certifying emptiness.
            val unknownFallback = assertIs<DataResult.Data<StrikeRefresh>>(
                repository.refresh(intervalB.first, intervalB.second),
            )
            assertTrue(unknownFallback.value.strikes.isEmpty())
            assertEquals(DomainFailure.TEMPORARY, unknownFallback.warning)
            assertIs<DataFreshness.Unknown>(unknownFallback.freshness)
        }
    }

    @Test
    fun strikeChangesPropagateReactivelyToObservers() = runTest {
        withRepository { _, _, _, provider, repository ->
            val emissions = mutableListOf<DataResult<List<Strike>>>()
            val observation = launch {
                repository.observeStrikes(intervalA.first, intervalA.second).collect { emissions += it }
            }
            runCurrent()
            repository.refresh(intervalA.first, intervalA.second)
            runCurrent()
            repository.setNotificationsEnabled(true)
            provider.values = listOf(testProviderStrike.copy(mode = "Ridotto a 8 ore"))
            repository.refresh(intervalA.first, intervalA.second, force = true)
            runCurrent()
            provider.values = emptyList()
            repository.refresh(intervalA.first, intervalA.second, force = true)
            runCurrent()
            observation.cancel()

            val data = emissions.filterIsInstance<DataResult.Data<List<Strike>>>()
            assertTrue(data.size >= 3)
            assertEquals(StrikeStatus.MODIFIED, data[data.size - 2].value.single().status)
            assertEquals(StrikeStatus.REVOKED, data.last().value.single().status)
            assertIs<DataFreshness.Fresh>(data.last().freshness)
        }
    }

    @Test
    fun concurrentEquivalentRefreshesStillShareOneFetch() = runTest {
        val driver = createRepositoryTestDriver()
        val database = TrenifyDatabase(driver)
        val clock = MutableClock()
        val gate = CompletableDeferred<Unit>()
        val delegate = FakeStrikeProvider(clock)
        val provider = object : StrikeProvider by delegate {
            override suspend fun getStrikes(
                from: Instant,
                to: Instant,
                includeRevoked: Boolean,
            ): ProviderResult<List<ProviderStrike>> {
                gate.await()
                return delegate.getStrikes(from, to, includeRevoked)
            }
        }
        val repository = SqlDelightStrikeRepository(
            database,
            provider,
            backgroundScope,
            clock = clock,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            val first = async { repository.refresh(intervalA.first, intervalA.second, force = true) }
            val second = async { repository.refresh(intervalA.first, intervalA.second, force = true) }
            runCurrent()
            assertEquals(0, delegate.calls)
            gate.complete(Unit)
            assertIs<DataResult.Data<StrikeRefresh>>(first.await())
            assertIs<DataResult.Data<StrikeRefresh>>(second.await())
            assertEquals(1, delegate.calls)
        } finally {
            driver.close()
        }
    }

    @Test
    fun migrationToVersion14CreatesCoverageWithoutTouchingStrikes() = runTest {
        val driver = createUnmanagedRepositoryTestDriver()
        try {
            driver.execute(
                null,
                "CREATE TABLE strike (id TEXT NOT NULL PRIMARY KEY, provider_id TEXT NOT NULL, external_id TEXT, " +
                    "start_epoch_ms INTEGER NOT NULL, end_epoch_ms INTEGER NOT NULL, status TEXT NOT NULL, " +
                    "sector TEXT NOT NULL, relevance TEXT NOT NULL, regions TEXT NOT NULL, provinces TEXT NOT NULL, " +
                    "unions TEXT NOT NULL, operators TEXT NOT NULL, workforce TEXT, mode TEXT NOT NULL, notes TEXT, " +
                    "source_label TEXT NOT NULL, source_url TEXT NOT NULL, source_updated_at_epoch_ms INTEGER, " +
                    "fetched_at_epoch_ms INTEGER NOT NULL, content_fingerprint TEXT NOT NULL)",
                0,
            )
            driver.execute(null, "INSERT INTO strike VALUES ('mit-strikes:8479', 'mit-strikes', '8479', 1, 2, " +
                "'SCHEDULED', 'Ferroviario', 'REGIONAL', '[]', '[]', '[]', '[]', NULL, '24 ore', NULL, " +
                "'MIT', 'https://scioperi.mit.gov.it/8479', NULL, 3, 'fp')", 0)
            driver.execute(
                null,
                "CREATE TABLE strike_sync_state (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1), " +
                    "fetched_at_epoch_ms INTEGER NOT NULL, source_updated_at_epoch_ms INTEGER)",
                0,
            )
            driver.execute(null, "INSERT INTO strike_sync_state VALUES (1, 3, NULL)", 0)

            TrenifyDatabase.Schema.migrate(driver, 13, 14)

            val database = TrenifyDatabase(driver)
            assertEquals(
                "mit-strikes:8479",
                database.realtimeQueries.strikeById("mit-strikes:8479").executeAsOne().id,
            )
            // Pre-existing databases start uncovered: the next relevant view
            // triggers a targeted refresh instead of trusting old emptiness.
            assertNull(
                database.realtimeQueries.coveringStrikeCoverage(
                    from_epoch_ms = intervalA.first.toEpochMilliseconds(),
                    to_epoch_ms = intervalA.second.toEpochMilliseconds(),
                ).executeAsOneOrNull(),
            )
            database.realtimeQueries.putStrikeCoverage(
                intervalA.first.toEpochMilliseconds(),
                intervalA.second.toEpochMilliseconds(),
                3,
                null,
            )
            assertEquals(
                3L,
                database.realtimeQueries.coveringStrikeCoverage(
                    from_epoch_ms = containedC.first.toEpochMilliseconds(),
                    to_epoch_ms = containedC.second.toEpochMilliseconds(),
                ).executeAsOne().fetched_at_epoch_ms,
            )
        } finally {
            driver.close()
        }
    }
}

/** T7.12 corrective pass, Blocker 1: inclusive boundary overlap end-to-end. */
@OptIn(ExperimentalCoroutinesApi::class)
class StrikeBoundaryOverlapTest {
    private val touchStart = Instant.parse("2026-09-10T00:00:00Z")
    private val touchEnd = Instant.parse("2026-09-10T02:00:00Z")

    private fun boundaryStrike() = testProviderStrike.copy(
        externalId = "8481",
        start = touchStart,
        end = touchEnd,
    )

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
    fun strikeEndingExactlyAtRequestStartIsObserved() = runTest {
        withRepository { _, provider, repository ->
            provider.values = listOf(boundaryStrike())
            repository.refresh(touchEnd, touchEnd + 2.hours)
            val observed = assertIs<DataResult.Data<List<Strike>>>(
                repository.observeStrikes(touchEnd, touchEnd + 2.hours).first(),
            )
            assertEquals(listOf("8481"), observed.value.map { it.externalId })
        }
    }

    @Test
    fun strikeStartingExactlyAtRequestEndIsObserved() = runTest {
        withRepository { _, provider, repository ->
            provider.values = listOf(boundaryStrike())
            repository.refresh(touchStart - 2.hours, touchStart)
            val observed = assertIs<DataResult.Data<List<Strike>>>(
                repository.observeStrikes(touchStart - 2.hours, touchStart).first(),
            )
            assertEquals(listOf("8481"), observed.value.map { it.externalId })
        }
    }

    @Test
    fun absenceRevocationAppliesToBoundaryTouchingCachedStrike() = runTest {
        withRepository { _, provider, repository ->
            provider.values = listOf(boundaryStrike())
            repository.refresh(touchStart, touchEnd)
            assertEquals(
                StrikeStatus.SCHEDULED,
                assertIs<DataResult.Data<List<Strike>>>(
                    repository.observeStrikes(touchStart, touchEnd).first(),
                ).value.single().status,
            )
            // The refresh window touches the cached strike at exactly its
            // end instant while the complete authoritative response omits
            // it: absence revokes, exactly like a proper overlap.
            provider.values = listOf(testProviderStrike.copy(externalId = "far-away"))
            val revoked = assertIs<DataResult.Data<StrikeRefresh>>(
                repository.refresh(touchEnd, touchEnd + 2.hours, force = true),
            )
            assertEquals(StrikeStatus.REVOKED, revoked.value.strikes.single { it.externalId == "8481" }.status)
        }
    }
}
