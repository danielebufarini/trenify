package it.danielebufarini.trenify.data

import app.cash.sqldelight.db.SqlDriver
import it.danielebufarini.trenify.core.domain.CurrentStrikeWindow
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.LoadStrikes
import it.danielebufarini.trenify.core.domain.StrikeChangeKind
import it.danielebufarini.trenify.core.domain.StrikePolicy
import it.danielebufarini.trenify.core.domain.StrikeRefresh
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Strike
import it.danielebufarini.trenify.core.model.StrikeStatus
import it.danielebufarini.trenify.core.provider.api.ProviderFailure
import it.danielebufarini.trenify.core.provider.api.ProviderResult
import it.danielebufarini.trenify.core.testing.FakeStrikeProvider
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.TickingClock
import it.danielebufarini.trenify.core.testing.testProviderStrike
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SqlDelightStrikeRepositoryTest {
    private val from = Instant.parse("2026-09-04T00:00:00Z")
    private val to = Instant.parse("2026-12-04T00:00:00Z")

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
    fun cacheRoundTripAndObservationAreReactive() = runTest {
        withRepository { _, database, clock, provider, repository ->
            val states = mutableListOf<DataResult<List<it.danielebufarini.trenify.core.model.Strike>>>()
            val observation = backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
                repository.observeStrikes(from, to).take(2).toList(states)
            }
            runCurrent()
            val refreshed = assertIs<DataResult.Data<it.danielebufarini.trenify.core.domain.StrikeRefresh>>(
                repository.refresh(from, to),
            )
            runCurrent()
            assertEquals("8479", refreshed.value.strikes.single().externalId)
            assertEquals(listOf(0, 1), states.map { (it as? DataResult.Data)?.value?.size ?: 0 })
            assertEquals(1, provider.calls)
            assertIs<DataFreshness.Fresh>(repository.observeStrikes(from, to).first().let { assertIs<DataResult.Data<*>>(it).freshness })

            val restarted = SqlDelightStrikeRepository(
                database,
                provider,
                backgroundScope,
                clock = clock,
                dispatcher = StandardTestDispatcher(testScheduler),
            )
            assertEquals(refreshed.value.strikes, assertIs<DataResult.Data<List<it.danielebufarini.trenify.core.model.Strike>>>(
                restarted.observeStrikes(from, to).first(),
            ).value)
            restarted.refresh(from, to)
            assertEquals(1, provider.calls)
            observation.cancel()
        }
    }

    @Test
    fun strikeOptInDefaultAndOverrideSurviveRestart() = runTest {
        withRepository { _, database, clock, provider, repository ->
            // Absent setting reads as disabled; enabling persists; a restart
            // keeps the override. Alerts and Settings share this one row.
            assertFalse(repository.observeNotificationsEnabled().first())
            repository.setNotificationsEnabled(true)
            assertTrue(repository.observeNotificationsEnabled().first())

            val restarted = SqlDelightStrikeRepository(
                database,
                provider,
                backgroundScope,
                clock = clock,
                dispatcher = StandardTestDispatcher(testScheduler),
            )
            assertTrue(restarted.observeNotificationsEnabled().first())
            restarted.setNotificationsEnabled(false)
            assertFalse(repository.observeNotificationsEnabled().first())
        }
    }

    @Test
    fun updatesAndMissingFutureEntriesReplacePriorStateAndCoalesceNotifications() = runTest {
        withRepository { _, _, _, provider, repository ->
            repository.refresh(from, to)
            repository.setNotificationsEnabled(true)

            provider.values = listOf(testProviderStrike.copy(mode = "Ridotto a 8 ore"))
            val modified = assertIs<DataResult.Data<it.danielebufarini.trenify.core.domain.StrikeRefresh>>(
                repository.refresh(from, to, force = true),
            )
            assertEquals(StrikeStatus.MODIFIED, modified.value.strikes.single().status)
            assertEquals(StrikeChangeKind.MODIFIED, repository.pendingNotifications().single().kind)

            repository.refresh(from, to, force = true)
            assertEquals(1, repository.pendingNotifications().size)

            provider.values = emptyList()
            val revoked = assertIs<DataResult.Data<it.danielebufarini.trenify.core.domain.StrikeRefresh>>(
                repository.refresh(from, to, force = true),
            ).value.strikes.single()
            assertEquals(StrikeStatus.REVOKED, revoked.status)
            val pending = repository.pendingNotifications().single()
            assertEquals(StrikeChangeKind.REVOKED, pending.kind)
            assertTrue(repository.claimNotification(pending, Instant.parse("2026-09-05T09:00:00Z")))
            assertFalse(repository.claimNotification(pending, Instant.parse("2026-09-05T09:01:00Z")))
            assertTrue(repository.pendingNotifications().isEmpty())
        }
    }

    @Test
    fun staleFailureRetainsCachedStrikesAndFingerprintIsDeterministic() = runTest {
        withRepository { _, _, clock, provider, repository ->
            val original = assertIs<DataResult.Data<it.danielebufarini.trenify.core.domain.StrikeRefresh>>(
                repository.refresh(from, to),
            ).value.strikes.single()
            val whitespaceVariant = normalize(
                testProviderStrike.copy(sector = "  Ferroviario   ", mode = "24 ore:   dalle 21.18 alle 21.00"),
                provider.id,
            )
            assertEquals(original.id, whitespaceVariant.id)
            assertEquals(original.contentFingerprint, whitespaceVariant.contentFingerprint)

            val changed = normalize(testProviderStrike.copy(mode = "8 ore"), provider.id)
            assertEquals(original.id, changed.id)
            assertNotEquals(original.contentFingerprint, changed.contentFingerprint)

            clock.instant += 46.minutes
            provider.result = ProviderResult.Unavailable(true, ProviderFailure.TRANSPORT)
            val fallback = assertIs<DataResult.Data<it.danielebufarini.trenify.core.domain.StrikeRefresh>>(
                repository.refresh(from, to),
            )
            assertEquals(listOf(original), fallback.value.strikes)
            assertEquals(DomainFailure.TEMPORARY, fallback.warning)
            assertIs<DataFreshness.Stale>(fallback.freshness)
        }
    }

    @Test
    fun overlappingIdenticalRefreshesShareOneFetch() = runTest {
        withGatedRepository { _, _, provider, repository ->
            val first = async { repository.refresh(from, to, force = true) }
            val second = async { repository.refresh(from, to, force = true) }
            runCurrent()
            // Both callers are parked on the single shared fetch.
            assertEquals(0, provider.calls)
            provider.gate.complete(Unit)
            val firstResult = assertIs<DataResult.Data<it.danielebufarini.trenify.core.domain.StrikeRefresh>>(first.await())
            val secondResult = assertIs<DataResult.Data<it.danielebufarini.trenify.core.domain.StrikeRefresh>>(second.await())
            assertEquals(1, firstResult.value.strikes.size)
            assertEquals(firstResult.value.strikes, secondResult.value.strikes)
            assertEquals(1, provider.calls)
        }
    }

    @Test
    fun differentRefreshWindowsFetchSeparately() = runTest {
        withGatedRepository { _, _, provider, repository ->
            val first = async { repository.refresh(from, to, force = true) }
            val second = async { repository.refresh(from, to.plus(1.minutes), force = true) }
            runCurrent()
            provider.gate.complete(Unit)
            assertIs<DataResult.Data<it.danielebufarini.trenify.core.domain.StrikeRefresh>>(first.await())
            assertIs<DataResult.Data<it.danielebufarini.trenify.core.domain.StrikeRefresh>>(second.await())
            assertEquals(2, provider.calls)
        }
    }

    @Test
    fun cancellingOneJoinerLeavesTheSharedFetchForTheOther() = runTest {
        withGatedRepository { _, _, provider, repository ->
            val first = async { repository.refresh(from, to, force = true) }
            val second = async { repository.refresh(from, to, force = true) }
            runCurrent()
            second.cancel()
            provider.gate.complete(Unit)
            assertIs<DataResult.Data<it.danielebufarini.trenify.core.domain.StrikeRefresh>>(first.await())
            assertTrue(second.isCancelled)
            assertEquals(1, provider.calls)
            // The shared entry is gone: a later forced call fetches again.
            assertIs<DataResult.Data<it.danielebufarini.trenify.core.domain.StrikeRefresh>>(
                repository.refresh(from, to, force = true),
            )
            assertEquals(2, provider.calls)
        }
    }

    @Test
    fun currentWindowRefreshesFromAnAdvancingClockShareOneFetch() = runTest {
        val ticking = TickingClock()
        withGatedRepository(ticking) { _, _, provider, repository ->
            val policy = StrikePolicy()
            val captured = mutableListOf<Instant>()
            // Each caller derives its window through the production shared
            // abstraction, reading the advancing clock exactly once, so no
            // two captured instants are identical here.
            suspend fun currentWindowRefresh() = policy.currentWindow(ticking).let { window ->
                captured += window.now
                repository.refresh(window, force = true)
            }
            val home = async { currentWindowRefresh() }
            val alerts = async { currentWindowRefresh() }
            val coordinator = async { currentWindowRefresh() }
            runCurrent()
            // All three callers are parked on the single shared fetch.
            assertEquals(3, captured.distinct().size)
            assertEquals(0, provider.calls)
            provider.gate.complete(Unit)
            listOf(home, alerts, coordinator).forEach {
                assertIs<DataResult.Data<it.danielebufarini.trenify.core.domain.StrikeRefresh>>(it.await())
            }
            assertEquals(1, provider.calls)
        }
    }

    @Test
    fun homeAlertsAndCoordinatorShareOneFetchThroughLoadStrikes() = runTest {
        val ticking = TickingClock()
        withGatedRepository(ticking) { _, _, provider, repository ->
            val policy = StrikePolicy()
            // Production topology: Home and Alerts each hold their own
            // LoadStrikes over one repository; the coordinator calls the
            // repository directly. All three use the shared window helper.
            val homeLoad = LoadStrikes(repository)
            val alertsLoad = LoadStrikes(repository)
            val windows = mutableListOf<CurrentStrikeWindow>()
            suspend fun sharedRefresh(window: CurrentStrikeWindow) = repository.refresh(window, force = true)
            val home = async {
                val window = policy.currentWindow(ticking).also { windows += it }
                assertIs<DataResult.Data<StrikeRefresh>>(homeLoad(window, force = true)) to window
            }
            val alerts = async {
                val window = policy.currentWindow(ticking).also { windows += it }
                assertIs<DataResult.Data<StrikeRefresh>>(alertsLoad(window, force = true)) to window
            }
            val coordinator = async {
                val window = policy.currentWindow(ticking).also { windows += it }
                assertIs<DataResult.Data<StrikeRefresh>>(sharedRefresh(window)) to window
            }
            runCurrent()
            assertEquals(0, provider.calls)
            provider.gate.complete(Unit)
            val pairs = listOf(home, alerts, coordinator).map { it.await() }
            assertEquals(1, provider.calls)
            // One fetch, but per-caller truth: every joined caller is Fresh
            // on its own requested interval with coverage expressed for
            // exactly that interval — never another caller's bounds.
            assertEquals(3, windows.distinct().size)
            pairs.forEach { (result, _) -> assertIs<DataFreshness.Fresh>(result.freshness) }
            val fetchedAt = pairs.map { (result, _) -> requireNotNull(result.value.coverage).fetchedAt }.distinct()
            assertEquals(1, fetchedAt.size)
            pairs.forEach { (result, window) ->
                val coverage = requireNotNull(result.value.coverage)
                assertEquals(window.from, coverage.from)
                assertEquals(window.to, coverage.to)
            }
        }
    }

    @Test
    fun sharedCurrentWindowFlightCoversEveryJoinedCallerTruthfully() = runTest {
        // Ticking one minute per read keeps the three callers in one
        // 45-minute refresh bucket while separating their exact bounds.
        val ticking = TickingClock(step = 1.minutes)
        withGatedRepository(ticking) { _, _, provider, repository ->
            val policy = StrikePolicy()
            val firstTo = ticking.instant + policy.futureWindow
            // Boundary fixture in the small delta between the first and
            // second advancing windows: outside the first caller's interval,
            // inside every later caller's. A fetch covering only the first
            // caller never returns it, so the second observer stays blind.
            val sliverStart = firstTo + 20.seconds
            provider.values = listOf(
                testProviderStrike,
                testProviderStrike.copy(externalId = "sliver", start = sliverStart, end = sliverStart + 20.seconds),
            )
            val windows = mutableListOf<CurrentStrikeWindow>()
            suspend fun currentWindowRefresh() = policy.currentWindow(ticking).also { windows += it }
                .let { assertIs<DataResult.Data<StrikeRefresh>>(repository.refresh(it, force = true)) to it }
            val home = async { currentWindowRefresh() }
            val alerts = async { currentWindowRefresh() }
            val coordinator = async { currentWindowRefresh() }
            runCurrent()
            assertEquals(3, windows.distinct().size)
            assertEquals(0, provider.calls)
            provider.gate.complete(Unit)
            val pairs = listOf(home, alerts, coordinator).map { it.await() }
            assertEquals(1, provider.calls)
            // The shared operation fetches the deterministic canonical
            // superset for the bucket — not the first caller's bounds.
            val bucket = policy.refreshBucket(windows[0].now)
            assertEquals(
                (bucket - policy.historyWindow) to (bucket + policy.cacheTtl + policy.futureWindow),
                provider.requestedIntervals.single(),
            )
            // Every joined caller is Fresh with coverage for its own
            // requested interval from the one shared operation.
            pairs.forEach { (result, window) ->
                assertIs<DataFreshness.Fresh>(result.freshness)
                val coverage = requireNotNull(result.value.coverage)
                assertEquals(window.from, coverage.from)
                assertEquals(window.to, coverage.to)
            }
            // Each observer sees exactly its own interval: the first caller
            // (earliest captured instant) never sees the sliver fixture,
            // later callers do.
            val ordered = windows.sortedBy { it.now }
            val first = assertIs<DataResult.Data<List<Strike>>>(
                repository.observeStrikes(ordered[0].from, ordered[0].to).first(),
            )
            assertTrue(first.value.any { it.externalId == "8479" })
            assertTrue(first.value.none { it.externalId == "sliver" })
            assertIs<DataFreshness.Fresh>(first.freshness)
            ordered.drop(1).forEach { window ->
                val view = assertIs<DataResult.Data<List<Strike>>>(
                    repository.observeStrikes(window.from, window.to).first(),
                )
                assertTrue(view.value.any { it.externalId == "8479" })
                assertTrue(view.value.any { it.externalId == "sliver" })
                assertIs<DataFreshness.Fresh>(view.freshness)
            }
        }
    }

    @Test
    fun sharedCurrentWindowFailureReachesEveryJoinedCaller() = runTest {
        val ticking = TickingClock()
        withGatedRepository(ticking) { _, _, provider, repository ->
            val policy = StrikePolicy()
            provider.result = ProviderResult.Unavailable(true, ProviderFailure.TRANSPORT)
            val windows = mutableListOf<CurrentStrikeWindow>()
            suspend fun currentWindowRefresh() = policy.currentWindow(ticking).also { windows += it }
                .let { repository.refresh(it, force = true) }
            val home = async { currentWindowRefresh() }
            val alerts = async { currentWindowRefresh() }
            val coordinator = async { currentWindowRefresh() }
            runCurrent()
            assertEquals(0, provider.calls)
            provider.gate.complete(Unit)
            // One shared fetch, one shared failure delivered to every
            // waiter — not only the first caller's result object.
            listOf(home, alerts, coordinator).forEach {
                assertEquals(DomainFailure.TEMPORARY, assertIs<DataResult.Failure>(it.await()).error)
            }
            assertEquals(1, provider.calls)
            runCurrent()
            // Every joined caller's own observer exposes the failure…
            windows.forEach { window ->
                assertEquals(
                    DomainFailure.TEMPORARY,
                    assertIs<DataResult.Failure>(repository.observeStrikes(window.from, window.to).first()).error,
                )
            }
            // …while a disjoint custom interval is not contaminated by the
            // current-window failure.
            assertEquals(
                DomainFailure.NOT_FOUND,
                assertIs<DataResult.Failure>(
                    repository.observeStrikes(
                        Instant.parse("2027-03-01T00:00:00Z"),
                        Instant.parse("2027-03-02T00:00:00Z"),
                    ).first(),
                ).error,
            )
            // Recovery through one new current-window refresh clears only
            // the recovered request: its own observer turns Fresh while
            // earlier joined intervals retain their stale warning.
            provider.result = null
            val recoveredWindow = policy.currentWindow(ticking)
            val recovered = assertIs<DataResult.Data<StrikeRefresh>>(
                repository.refresh(recoveredWindow, force = true),
            )
            assertIs<DataFreshness.Fresh>(recovered.freshness)
            assertEquals(2, provider.calls)
            runCurrent()
            assertIs<DataFreshness.Fresh>(
                assertIs<DataResult.Data<List<Strike>>>(
                    repository.observeStrikes(recoveredWindow.from, recoveredWindow.to).first(),
                ).freshness,
            )
            val retained = assertIs<DataResult.Data<List<Strike>>>(
                repository.observeStrikes(windows[0].from, windows[0].to).first(),
            )
            assertEquals(DomainFailure.TEMPORARY, retained.warning)
            assertIs<DataFreshness.Stale>(retained.freshness)
        }
    }

    @Test
    fun sharedCurrentWindowFailureWithCacheProjectsWarningPerCaller() = runTest {
        val ticking = TickingClock()
        withGatedRepository(ticking) { _, _, provider, repository ->
            val policy = StrikePolicy()
            // Prime the cache with one successful current-window refresh.
            val prime = async { repository.refresh(policy.currentWindow(ticking), force = true) }
            runCurrent()
            provider.gate.complete(Unit)
            assertIs<DataResult.Data<StrikeRefresh>>(prime.await())

            // One shared failure with cached strikes: every joined caller
            // must receive stale data carrying the warning — the projection
            // must never launder the shared outcome into a clean Fresh.
            provider.result = ProviderResult.Unavailable(true, ProviderFailure.TRANSPORT)
            suspend fun currentWindowRefresh() = policy.currentWindow(ticking)
                .let { assertIs<DataResult.Data<StrikeRefresh>>(repository.refresh(it, force = true)) to it }
            val home = async { currentWindowRefresh() }
            val alerts = async { currentWindowRefresh() }
            val coordinator = async { currentWindowRefresh() }
            runCurrent()
            provider.gate.complete(Unit)
            // Gate is single-use but already completed: the failure round
            // still issues exactly one provider call.
            val pairs = listOf(home, alerts, coordinator).map { it.await() }
            assertEquals(2, provider.calls)
            pairs.forEach { (result, window) ->
                assertEquals(DomainFailure.TEMPORARY, result.warning)
                assertIs<DataFreshness.Stale>(result.freshness)
                assertTrue(result.value.strikes.any { it.externalId == "8479" })
                val view = assertIs<DataResult.Data<List<Strike>>>(
                    repository.observeStrikes(window.from, window.to).first(),
                )
                assertEquals(DomainFailure.TEMPORARY, view.warning)
                assertIs<DataFreshness.Stale>(view.freshness)
            }
        }
    }

    @Test
    fun cancellingMiddleCurrentWindowJoinerKeepsOneSharedFetch() = runTest {
        val ticking = TickingClock()
        withGatedRepository(ticking) { _, _, provider, repository ->
            val policy = StrikePolicy()
            suspend fun currentWindowRefresh() = repository.refresh(policy.currentWindow(ticking), force = true)
            val home = async { currentWindowRefresh() }
            val alerts = async { currentWindowRefresh() }
            val coordinator = async { currentWindowRefresh() }
            runCurrent()
            // Cancelling the middle waiter while the shared fetch is still
            // running must disturb neither the fetch nor the other waiters.
            alerts.cancel()
            provider.gate.complete(Unit)
            assertTrue(alerts.isCancelled)
            assertFailsWith<CancellationException> { alerts.await() }
            assertIs<DataResult.Data<StrikeRefresh>>(home.await())
            assertIs<DataResult.Data<StrikeRefresh>>(coordinator.await())
            assertEquals(1, provider.calls)
        }
    }

    @Test
    fun temporallyDifferentCurrentWindowsFetchSeparately() = runTest {
        withGatedRepository { _, _, provider, repository ->
            val policy = StrikePolicy()
            // Same policy span, but centered a month apart: different logical
            // refresh units must not share a fetch merely on equal duration.
            val september = policy.currentWindow(Instant.parse("2026-09-07T08:00:00Z"))
            val october = policy.currentWindow(Instant.parse("2026-10-07T08:00:00Z"))
            val first = async { repository.refresh(september, force = true) }
            val second = async { repository.refresh(october, force = true) }
            runCurrent()
            provider.gate.complete(Unit)
            assertIs<DataResult.Data<it.danielebufarini.trenify.core.domain.StrikeRefresh>>(first.await())
            assertIs<DataResult.Data<it.danielebufarini.trenify.core.domain.StrikeRefresh>>(second.await())
            assertEquals(2, provider.calls)
        }
    }

    @Test
    fun explicitRangesNeverJoinACurrentWindowFlight() = runTest {
        withGatedRepository { _, _, provider, repository ->
            val policy = StrikePolicy()
            val current = policy.currentWindow(Instant.parse("2026-09-07T08:00:00Z"))
            // Same span as the policy window, but an explicit custom range.
            val sameSpan = current.from + 1.days to current.to + 1.days
            val inFlight = async { repository.refresh(current, force = true) }
            val customSpan = async { repository.refresh(sameSpan.first, sameSpan.second, force = true) }
            val identicalBounds = async { repository.refresh(current.from, current.to, force = true) }
            runCurrent()
            provider.gate.complete(Unit)
            assertIs<DataResult.Data<it.danielebufarini.trenify.core.domain.StrikeRefresh>>(inFlight.await())
            assertIs<DataResult.Data<it.danielebufarini.trenify.core.domain.StrikeRefresh>>(customSpan.await())
            assertIs<DataResult.Data<it.danielebufarini.trenify.core.domain.StrikeRefresh>>(identicalBounds.await())
            // Explicit and current namespaces never collide: the custom
            // ranges fetch on their own even with matching span or bounds.
            assertEquals(3, provider.calls)
        }
    }

    @Test
    fun cancellingFirstCurrentWindowWaiterKeepsTheSharedFetch() = runTest {
        val ticking = TickingClock()
        withGatedRepository(ticking) { _, _, provider, repository ->
            val policy = StrikePolicy()
            suspend fun currentWindowRefresh() = repository.refresh(policy.currentWindow(ticking), force = true)
            val first = async { currentWindowRefresh() }
            val second = async { currentWindowRefresh() }
            runCurrent()
            // Cancelling the first waiter while the shared fetch is still
            // running must not cancel the fetch for the remaining waiter.
            first.cancel()
            provider.gate.complete(Unit)
            assertTrue(first.isCancelled)
            assertFailsWith<CancellationException> { first.await() }
            assertIs<DataResult.Data<it.danielebufarini.trenify.core.domain.StrikeRefresh>>(second.await())
            assertEquals(1, provider.calls)
            // The completed operation is not retained as in-flight work.
            assertIs<DataResult.Data<it.danielebufarini.trenify.core.domain.StrikeRefresh>>(currentWindowRefresh())
            assertEquals(2, provider.calls)
        }
    }

    private class GatedStrikeProvider(
        private val clock: Clock,
    ) : it.danielebufarini.trenify.core.provider.api.StrikeProvider {
        val gate = CompletableDeferred<Unit>()
        private val delegate = FakeStrikeProvider(clock)
        val calls: Int get() = delegate.calls
        var result: ProviderResult<List<it.danielebufarini.trenify.core.provider.api.ProviderStrike>>?
            get() = delegate.result
            set(value) {
                delegate.result = value
            }
        var values: List<it.danielebufarini.trenify.core.provider.api.ProviderStrike>
            get() = delegate.values
            set(value) {
                delegate.values = value
            }
        val requestedIntervals: List<Pair<Instant, Instant>> get() = delegate.requestedIntervals

        override val id get() = delegate.id
        override val suppliesCompleteSnapshots get() = delegate.suppliesCompleteSnapshots
        override suspend fun getStrikes(
            from: Instant,
            to: Instant,
            includeRevoked: Boolean,
        ): ProviderResult<List<it.danielebufarini.trenify.core.provider.api.ProviderStrike>> {
            gate.await()
            return delegate.getStrikes(from, to, includeRevoked)
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.withGatedRepository(
        clock: Clock = MutableClock(),
        block: suspend kotlinx.coroutines.test.TestScope.(
            SqlDriver,
            TrenifyDatabase,
            GatedStrikeProvider,
            SqlDelightStrikeRepository,
        ) -> Unit,
    ) {
        val driver = createRepositoryTestDriver()
        val database = TrenifyDatabase(driver)
        val provider = GatedStrikeProvider(clock)
        val repository = SqlDelightStrikeRepository(
            database,
            provider,
            backgroundScope,
            clock = clock,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            block(driver, database, provider, repository)
        } finally {
            driver.close()
        }
    }
}
