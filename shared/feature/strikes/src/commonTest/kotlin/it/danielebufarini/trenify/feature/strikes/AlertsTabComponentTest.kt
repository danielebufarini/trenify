package it.danielebufarini.trenify.feature.strikes

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
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.TickingClock
import it.danielebufarini.trenify.core.testing.testStrike
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalCoroutinesApi::class)
class AlertsTabComponentTest {
    @Test
    fun componentOwnsLoadingRefreshNavigationAndReactiveStaleState() = runTest {
        val repository = FakeStrikeRepository()
        val clock = MutableClock()
        val component = DefaultAlertsTabComponent(
            DefaultComponentContext(LifecycleRegistry()),
            LoadStrikes(repository),
            ObserveStrikeNotifications(repository),
            SetStrikeNotifications(repository),
            requestNotificationPermission = { true },
            clock = clock,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        assertEquals(1, repository.refreshes)
        assertFalse(component.state.value.realtime.loading)
        assertEquals(listOf(testStrike), component.state.value.realtime.data)

        component.open(testStrike.id)
        runCurrent()
        assertEquals(
            testStrike.id,
            assertIs<AlertsTabComponent.Child.Detail>(component.stack.value.active.instance).strikeId,
        )

        repository.state.value = DataResult.Data(
            listOf(testStrike.copy(status = StrikeStatus.REVOKED)),
            DataFreshness.Stale(clock.now(), 1.hours, null),
            DomainFailure.TEMPORARY,
        )
        runCurrent()
        assertTrue(component.state.value.realtime.stale)
        assertTrue(component.state.value.realtime.failed)
        assertEquals(StrikeStatus.REVOKED, component.state.value.realtime.data?.single()?.status)
    }

    @Test
    fun notificationToggleRequiresPermissionAndPersistsPreference() = runTest {
        val repository = FakeStrikeRepository()
        var grant = false
        val component = DefaultAlertsTabComponent(
            DefaultComponentContext(LifecycleRegistry()),
            LoadStrikes(repository),
            ObserveStrikeNotifications(repository),
            SetStrikeNotifications(repository),
            requestNotificationPermission = { grant },
            clock = MutableClock(),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        component.toggleNotifications()
        runCurrent()
        assertFalse(repository.notificationsEnabled.value)
        assertEquals(false, component.state.value.notificationPermissionGranted)

        grant = true
        component.toggleNotifications()
        runCurrent()
        assertTrue(repository.notificationsEnabled.value)
        assertTrue(component.state.value.notificationsEnabled)

        component.toggleNotifications()
        runCurrent()
        assertFalse(repository.notificationsEnabled.value)
    }

    @Test
    fun refreshDerivesWindowFromASingleCapturedNow() = runTest {
        val repository = FakeStrikeRepository()
        val policy = StrikePolicy()
        val component = DefaultAlertsTabComponent(
            DefaultComponentContext(LifecycleRegistry()),
            LoadStrikes(repository),
            ObserveStrikeNotifications(repository),
            SetStrikeNotifications(repository),
            requestNotificationPermission = { true },
            clock = TickingClock(),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        component.refresh()
        runCurrent()
        // Every recorded refresh spans exactly the policy duration: with two
        // independent now() reads a ticking clock would stretch it past the
        // span and miss the shared single flight.
        assertTrue(repository.refreshWindows.isNotEmpty())
        repository.refreshWindows.forEach { (from, to) ->
            assertEquals(policy.historyWindow + policy.futureWindow, to - from)
        }
    }
}
