package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.LoadStrikes
import it.danielebufarini.trenify.core.domain.ObserveStrikeNotifications
import it.danielebufarini.trenify.core.domain.SetStrikeNotifications
import it.danielebufarini.trenify.core.domain.StrikePolicy
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Strike
import it.danielebufarini.trenify.core.model.StrikeStatus
import it.danielebufarini.trenify.core.testing.FakeStrikeProvider
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.data.SqlDelightStrikeRepository
import it.danielebufarini.trenify.database.TrenifyDatabase
import it.danielebufarini.trenify.feature.strikes.DefaultAlertsTabComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * T7.12-A through the existing production Alerts flow on a real database:
 * revocation/expiry propagate reactively to Alerts, and disjoint intervals
 * never reuse current-window freshness as evidence of no strikes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrikeCoverageAlertsFlowTest {
    private val policy = StrikePolicy()

    @Test
    fun revocationReachesAlertsReactivelyThroughProductionWiring() = runTest {
        val driver = createOrderingTestDriver()
        val database = TrenifyDatabase(driver)
        val clock = MutableClock()
        val provider = FakeStrikeProvider(clock)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = SqlDelightStrikeRepository(database, provider, backgroundScope, clock = clock, dispatcher = dispatcher)
        try {
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
            runCurrent()
            val scheduled = requireNotNull(component.state.value.realtime.data)
            assertEquals(1, scheduled.size)
            assertEquals(StrikeStatus.SCHEDULED, scheduled.single().status)

            // Complete empty snapshot revokes by absence; Alerts observes it
            // through the same production flow (revoked rows are filtered
            // from the visible list by status, as the screen does).
            provider.values = emptyList()
            component.refresh()
            runCurrent()
            val revoked = requireNotNull(component.state.value.realtime.data)
            assertEquals(StrikeStatus.REVOKED, revoked.single().status)
            assertTrue(revoked.filter {
                it.end > clock.now() && it.status !in setOf(StrikeStatus.REVOKED, StrikeStatus.COMPLETED)
            }.isEmpty())
        } finally {
            driver.close()
        }
    }

    @Test
    fun disjointJourneyIntervalNeverReusesCurrentWindowFreshness() = runTest {
        val driver = createOrderingTestDriver()
        val database = TrenifyDatabase(driver)
        val clock = MutableClock()
        val provider = FakeStrikeProvider(clock)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = SqlDelightStrikeRepository(database, provider, backgroundScope, clock = clock, dispatcher = dispatcher)
        try {
            val load = LoadStrikes(repository)
            // Current-window refresh certifies only the current window.
            load(policy.currentWindow(clock), force = true)
            val callsAfterCurrent = provider.calls

            // A disjoint far-future journey interval stays unknown and its
            // targeted refresh fetches exactly that interval.
            val futureFrom = Instant.parse("2027-03-01T00:00:00Z")
            val futureTo = Instant.parse("2027-03-02T00:00:00Z")
            val unknown = assertIs<DataResult.Data<List<Strike>>>(
                load.observe(futureFrom, futureTo).first(),
            )
            assertTrue(unknown.value.isEmpty())
            assertIs<DataFreshness.Unknown>(unknown.freshness)
            load(futureFrom, futureTo)
            assertEquals(callsAfterCurrent + 1, provider.calls)
            assertEquals(futureFrom to futureTo, provider.requestedIntervals.last())
        } finally {
            driver.close()
        }
    }
}
