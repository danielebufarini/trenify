package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.LoadStrikes
import it.danielebufarini.trenify.core.domain.ObserveStrikeNotifications
import it.danielebufarini.trenify.core.domain.SetStrikeNotifications
import it.danielebufarini.trenify.core.domain.StrikePolicy
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.StrikeStatus
import it.danielebufarini.trenify.core.provider.api.ProviderFailure
import it.danielebufarini.trenify.core.provider.api.ProviderResult
import it.danielebufarini.trenify.core.testing.FakeStrikeProvider
import it.danielebufarini.trenify.core.testing.TickingClock
import it.danielebufarini.trenify.core.testing.testProviderStrike
import it.danielebufarini.trenify.data.SqlDelightStrikeRepository
import it.danielebufarini.trenify.database.TrenifyDatabase
import it.danielebufarini.trenify.feature.strikes.DefaultAlertsTabComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * T7.12 final pass, Blocker 2: Alerts advances its observation window
 * together with every refresh, against a real [SqlDelightStrikeRepository]
 * on an advancing clock. Settles with [runCurrent]/bounded advancement so
 * the per-observer expiry ticker stays alive across clock jumps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrikeAlertsWindowAdvancementTest {
    private val policy = StrikePolicy()

    private suspend fun kotlinx.coroutines.test.TestScope.realAlerts(
        block: suspend kotlinx.coroutines.test.TestScope.(
            TickingClock,
            FakeStrikeProvider,
            SqlDelightStrikeRepository,
            DefaultAlertsTabComponent,
        ) -> Unit,
    ) {
        val driver = createOrderingTestDriver()
        val database = TrenifyDatabase(driver)
        val clock = TickingClock()
        val provider = FakeStrikeProvider(clock)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = SqlDelightStrikeRepository(database, provider, backgroundScope, clock = clock, dispatcher = dispatcher)
        val component = DefaultAlertsTabComponent(
            DefaultComponentContext(LifecycleRegistry()),
            LoadStrikes(repository),
            ObserveStrikeNotifications(repository),
            SetStrikeNotifications(repository),
            requestNotificationPermission = { true },
            clock = clock,
            policy = policy,
            dispatcher = dispatcher,
        )
        try {
            block(clock, provider, repository, component)
        } finally {
            driver.close()
        }
    }

    @Test
    fun alertsInitialRefreshLeavesFreshAlignedState() = runTest {
        realAlerts { _, provider, _, component ->
            provider.values = listOf(testProviderStrike)
            runCurrent()
            val state = component.state.value.realtime
            // Construction-time window and immediate initial refresh are
            // captured milliseconds apart; both describe the same logical
            // current refresh, so Alerts must not be left Unknown/Stale.
            assertFalse(state.loading)
            assertFalse(state.failed)
            assertFalse(state.stale)
            assertIs<DataFreshness.Fresh>(state.freshness)
            assertEquals(listOf("8479"), requireNotNull(state.data).map { it.externalId })
            assertEquals(1, provider.calls)
        }
    }

    @Test
    fun alertsManualRefreshAdvancesObservationToNewWindow() = runTest {
        realAlerts { clock, provider, _, component ->
            val firstTo = clock.instant + policy.futureWindow
            provider.values = listOf(testProviderStrike)
            runCurrent()
            assertEquals(1, provider.calls)
            // Move past the refresh bucket so the manual refresh below is a
            // new logical window, then introduce a strike outside the old
            // future boundary but inside the new one.
            clock.instant += 2.hours
            val edgeStart = firstTo + 30.minutes
            provider.values = listOf(
                testProviderStrike,
                testProviderStrike.copy(externalId = "edge", start = edgeStart, end = edgeStart + 2.hours),
            )
            component.refresh()
            runCurrent()
            val state = component.state.value.realtime
            val ids = requireNotNull(state.data).map { it.externalId }
            assertTrue(ids.contains("edge"), "edge strike fetched by the new window must be observed: $ids")
            assertIs<DataFreshness.Fresh>(state.freshness)
            assertFalse(state.failed)
            // One targeted refresh: observation stays read-only, so no
            // per-row/card provider call is introduced.
            assertEquals(2, provider.calls)
            // Local expiry still reacts on the advanced window without any
            // further provider contact: after the edge ends, Alerts shows it
            // completed. A permanently construction-pinned observation would
            // never re-read the edge interval and would leave it scheduled
            // (or drop it) instead.
            clock.instant = edgeStart + 3.hours
            advanceTimeBy(95.days)
            runCurrent()
            val expired = requireNotNull(component.state.value.realtime.data)
            assertEquals(StrikeStatus.COMPLETED, expired.single { it.externalId == "edge" }.status)
            assertEquals(2, provider.calls)
        }
    }

    @Test
    fun alertsFailedRefreshRetainsStaleWarningOnActiveInterval() = runTest {
        realAlerts { _, provider, _, component ->
            provider.values = listOf(testProviderStrike)
            runCurrent()
            assertIs<DataFreshness.Fresh>(component.state.value.realtime.freshness)

            provider.result = ProviderResult.Unavailable(true, ProviderFailure.TRANSPORT)
            component.refresh()
            runCurrent()
            val state = component.state.value.realtime
            assertFalse(state.loading)
            assertTrue(state.failed)
            assertTrue(state.stale)
            assertEquals(listOf("8479"), requireNotNull(state.data).map { it.externalId })
            assertEquals(2, provider.calls)
        }
    }

    @Test
    fun alertsDisjointFailureDoesNotAffectActiveWindow() = runTest {
        realAlerts { _, provider, repository, component ->
            provider.values = listOf(testProviderStrike)
            runCurrent()
            assertIs<DataFreshness.Fresh>(component.state.value.realtime.freshness)
            val callsAfterInitial = provider.calls

            // A failure on a disjoint custom interval is recorded against
            // that interval only: with prior sync state it surfaces as
            // stale-or-unknown data with the warning, while the active
            // Alerts window stays Fresh.
            provider.result = ProviderResult.Unavailable(true, ProviderFailure.TRANSPORT)
            val disjoint = assertIs<DataResult.Data<List<it.danielebufarini.trenify.core.model.Strike>>>(
                LoadStrikes(repository)(
                    Instant.parse("2027-03-01T00:00:00Z"),
                    Instant.parse("2027-03-02T00:00:00Z"),
                ),
            )
            assertEquals(DomainFailure.TEMPORARY, disjoint.warning)
            assertIs<DataFreshness.Unknown>(disjoint.freshness)
            runCurrent()
            val state = component.state.value.realtime
            assertIs<DataFreshness.Fresh>(state.freshness)
            assertFalse(state.failed)
            assertEquals(listOf("8479"), requireNotNull(state.data).map { it.externalId })
            assertEquals(callsAfterInitial + 1, provider.calls)
        }
    }

    @Test
    fun alertsLocalExpiryReactsWithoutProviderCalls() = runTest {
        realAlerts { clock, provider, _, component ->
            val start = clock.instant - 1.hours
            provider.values = listOf(
                testProviderStrike.copy(externalId = "exp", start = start, end = start + 90.minutes),
            )
            runCurrent()
            assertEquals(StrikeStatus.SCHEDULED, requireNotNull(component.state.value.realtime.data).single().status)
            assertEquals(1, provider.calls)

            clock.instant += 2.hours
            advanceTimeBy(2.hours)
            runCurrent()
            assertEquals(StrikeStatus.COMPLETED, requireNotNull(component.state.value.realtime.data).single().status)
            assertEquals(1, provider.calls)
        }
    }
}
