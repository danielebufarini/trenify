package it.danielebufarini.trenify.data

import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Strike
import it.danielebufarini.trenify.core.model.StrikeStatus
import it.danielebufarini.trenify.core.testing.FakeStrikeProvider
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testProviderStrike
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * T7.12 corrective pass, Blocker 4: expiry is derived locally from the
 * injected clock. One open observer reacts when `strike.end` passes with
 * zero provider calls, zero writes and zero notification events.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrikeLocalExpiryTest {
    private val window = Instant.parse("2026-09-05T08:00:00Z") to Instant.parse("2026-09-05T14:00:00Z")
    private val expiring = testProviderStrike.copy(
        externalId = "expiry",
        start = Instant.parse("2026-09-05T09:00:00Z"),
        end = Instant.parse("2026-09-05T11:00:00Z"),
    )

    @Test
    fun observerReactsToLocalExpiryWithZeroProviderCalls() = runTest {
        val driver = createRepositoryTestDriver()
        val database = TrenifyDatabase(driver)
        val clock = MutableClock()
        val provider = FakeStrikeProvider(clock).apply { values = listOf(expiring) }
        val repository = SqlDelightStrikeRepository(
            database,
            provider,
            backgroundScope,
            clock = clock,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            repository.setNotificationsEnabled(true)
            val emissions = mutableListOf<DataResult<List<Strike>>>()
            val job = launch { repository.observeStrikes(window.first, window.second).collect { emissions += it } }
            runCurrent()
            repository.refresh(window.first, window.second)
            runCurrent()
            assertEquals(
                StrikeStatus.SCHEDULED,
                assertIs<DataResult.Data<List<Strike>>>(emissions.last()).value.single().status,
            )
            // The initial snapshot legitimately claims the newly discovered
            // strike; local expiry must add nothing on top.
            val eventsAfterRefresh = repository.pendingNotifications().size

            // Time passes beyond strike.end while the observer stays open:
            // the next emission derives COMPLETED locally.
            clock.instant = Instant.parse("2026-09-05T12:00:00Z")
            advanceTimeBy(3.hours)
            runCurrent()
            job.cancel()

            val expired = assertIs<DataResult.Data<List<Strike>>>(emissions.last())
            assertEquals(StrikeStatus.COMPLETED, expired.value.single().status)
            assertEquals(1, provider.calls)
            assertEquals(eventsAfterRefresh, repository.pendingNotifications().size)
        } finally {
            driver.close()
        }
    }

    @Test
    fun revokedStrikeIsNeverDerivedCompleted() = runTest {
        val driver = createRepositoryTestDriver()
        val database = TrenifyDatabase(driver)
        val clock = MutableClock()
        val provider = FakeStrikeProvider(clock).apply {
            values = listOf(expiring.copy(status = StrikeStatus.REVOKED))
        }
        val repository = SqlDelightStrikeRepository(
            database,
            provider,
            backgroundScope,
            clock = clock,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            repository.refresh(window.first, window.second)
            clock.instant = Instant.parse("2026-09-05T12:00:00Z")
            advanceTimeBy(3.hours)
            runCurrent()
            val observed = assertIs<DataResult.Data<List<Strike>>>(
                repository.observeStrikes(window.first, window.second, includeRevoked = true).first(),
            )
            assertEquals(StrikeStatus.REVOKED, observed.value.single().status)
            // Coverage itself expired (TTL 45m) while revocation stands.
            assertIs<DataFreshness.Stale>(observed.freshness)
        } finally {
            driver.close()
        }
    }
}
