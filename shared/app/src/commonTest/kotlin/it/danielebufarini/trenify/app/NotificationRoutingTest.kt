package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.TrainRepository
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Strike
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.model.TrainRun
import it.danielebufarini.trenify.core.model.TrainRunId
import it.danielebufarini.trenify.core.platform.NotificationDestination
import it.danielebufarini.trenify.core.testing.FakeJourneyRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.core.testing.testStation
import it.danielebufarini.trenify.core.testing.testStrike
import it.danielebufarini.trenify.feature.journey.JourneyTabComponent
import it.danielebufarini.trenify.feature.strikes.AlertsTabComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Shared notification-routing behavior (T7.13-E): every platform invokes
 * the same [RootComponent.onNotificationDestination] entry point. Covers
 * train/strike routing, duplicates, malformed and unknown targets,
 * precedence over restored trees, cross-tab delivery and Back behavior.
 */
/**
 * Virtual-time discipline for these suites: every wait is a bounded
 * [advanceTimeBy], never a bare advanceUntilIdle. Screen-level polling
 * (VisibleRefresh) reschedules itself forever on resumed lifecycles, so an
 * unbounded drain would never terminate — it would even starve the runTest
 * timeout scheduled on the same dispatcher. One virtual second flushes all
 * immediate repository/UI work and short debounces without reaching the
 * 30s poll intervals; longer bounded advances cover the polling
 * assertions themselves.
 */
class NotificationRoutingTest {
    private class Fixtures(
        val realtime: FakeRealtimeRepositories = FakeRealtimeRepositories(),
        val journeys: FakeJourneyRepository = FakeJourneyRepository(),
        val strikes: FakeStrikeRepository = FakeStrikeRepository(),
    )

    private fun kotlinx.coroutines.test.TestScope.factory(fixtures: Fixtures) = AppComponentFactory(
        fixtures.realtime,
        fixtures.realtime,
        fixtures.realtime,
        fixtures.realtime,
        MutableStateFlow(true),
        StandardTestDispatcher(testScheduler),
        journeyRepository = fixtures.journeys,
        strikeRepository = fixtures.strikes,
        strikeNotificationRepository = fixtures.strikes,
    )

    private fun kotlinx.coroutines.test.TestScope.root(fixtures: Fixtures = Fixtures()): Pair<LifecycleRegistry, RootComponent> {
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(
            DefaultComponentContext(lifecycle, StateKeeperDispatcher(null)),
            factory(fixtures),
        )
        lifecycle.resume()
        return lifecycle to root
    }

    private fun mainOf(root: RootComponent): MainComponent =
        assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component

    private fun trainDestination(id: TrainRunId): NotificationDestination.Train =
        NotificationDestination.Train(
            provider = id.provider.value,
            number = id.number.value,
            origin = id.origin.value,
            serviceDate = id.serviceDate.toString(),
        )

    @Test
    fun monitorNotificationOpensCorrectTrainDetail() = runTest {
        val (lifecycle, root) = root()
        try {
            root.onNotificationDestination(trainDestination(testRunId))
            advanceTimeBy(1.seconds)
            val detail = assertIs<RootComponent.Child.Detail>(root.stack.value.active.instance)
            assertEquals(testRunId, detail.component.id)
            // Back returns to the main screen the tap interrupted.
            root.back()
            assertIs<RootComponent.Child.Main>(root.stack.value.active.instance)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun trainNumberAloneNeverIdentifiesARun() = runTest {
        val (lifecycle, root) = root()
        try {
            val before = root.stack.value.items.size
            // A destination without the full normalized identity is ignored
            // rather than resolved by number alone.
            root.onNotificationDestination(
                NotificationDestination.Train("", testRunId.number.value, testRunId.origin.value, "2026-09-05"),
            )
            root.onNotificationDestination(
                NotificationDestination.Train("test", "12X", testRunId.origin.value, "2026-09-05"),
            )
            root.onNotificationDestination(
                NotificationDestination.Train("test", testRunId.number.value, "", "2026-09-05"),
            )
            root.onNotificationDestination(
                NotificationDestination.Train("test", testRunId.number.value, testRunId.origin.value, "not-a-date"),
            )
            runCurrent()
            assertEquals(before, root.stack.value.items.size)
            assertIs<RootComponent.Child.Main>(root.stack.value.active.instance)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun strikeNotificationSelectsAlertsAndOpensDetail() = runTest {
        val (lifecycle, root) = root()
        try {
            mainOf(root).select(MainTab.Monitoring)
            runCurrent()
            root.onNotificationDestination(NotificationDestination.Strike(testStrike.id.value))
            advanceTimeBy(1.seconds)
            val main = mainOf(root)
            assertEquals(MainTab.Alerts.ordinal, main.pages.value.selectedIndex)
            val alerts = assertIs<MainComponent.Child.Alerts>(
                main.pages.value.items[MainTab.Alerts.ordinal].instance,
            ).component
            assertEquals(
                testStrike.id,
                assertIs<AlertsTabComponent.Child.Detail>(alerts.stack.value.active.instance).strikeId,
            )
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun duplicateCallbacksDoNotStackDuplicates() = runTest {
        val (lifecycle, root) = root()
        try {
            val destination = trainDestination(testRunId)
            root.onNotificationDestination(destination)
            root.onNotificationDestination(destination)
            advanceTimeBy(1.seconds)
            assertEquals(2, root.stack.value.items.size)
            // ...and a repeated legitimate later navigation still routes:
            // after moving away, the same target opens again.
            root.back()
            root.onNotificationDestination(destination)
            advanceTimeBy(1.seconds)
            assertEquals(2, root.stack.value.items.size)
            assertEquals(testRunId, assertIs<RootComponent.Child.Detail>(
                root.stack.value.active.instance).component.id)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun duplicateStrikeCallbacksDoNotStackDuplicates() = runTest {
        val (lifecycle, root) = root()
        try {
            val destination = NotificationDestination.Strike(testStrike.id.value)
            root.onNotificationDestination(destination)
            advanceTimeBy(1.seconds)
            root.onNotificationDestination(destination)
            advanceTimeBy(1.seconds)
            val main = mainOf(root)
            val alerts = assertIs<MainComponent.Child.Alerts>(
                main.pages.value.items[MainTab.Alerts.ordinal].instance,
            ).component
            assertEquals(2, alerts.stack.value.items.size)
            // Legitimate later navigation after moving away still routes.
            alerts.back()
            root.onNotificationDestination(destination)
            advanceTimeBy(1.seconds)
            assertEquals(2, alerts.stack.value.items.size)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun unknownTrainTargetReachesControlledRecoveryWithUsableBack() = runTest {
        val unknown = testRunId.copy(number = TrainNumber("999"))
        val fixtures = Fixtures()
        val strict = object : TrainRepository by fixtures.realtime {
            override fun observeTrain(id: TrainRunId): Flow<DataResult<TrainRun>> =
                flowOf(DataResult.Failure(DomainFailure.NOT_FOUND))

            override suspend fun refreshTrain(id: TrainRunId, force: Boolean): DataResult<TrainRun> =
                DataResult.Failure(DomainFailure.NOT_FOUND)
        }
        val lifecycle = LifecycleRegistry()
        val strictFactory = AppComponentFactory(
            fixtures.realtime, strict, fixtures.realtime, fixtures.realtime,
            MutableStateFlow(true), StandardTestDispatcher(testScheduler),
            journeyRepository = fixtures.journeys,
            strikeRepository = fixtures.strikes,
            strikeNotificationRepository = fixtures.strikes,
        )
        val root = DefaultRootComponent(
            DefaultComponentContext(lifecycle, StateKeeperDispatcher(null)),
            strictFactory,
        )
        lifecycle.resume()
        try {
            root.onNotificationDestination(trainDestination(unknown))
            advanceTimeBy(1.seconds)
            // The requested (unknown) target opens its detail shell — never
            // a similar-looking train — with a failed state and usable Back.
            val detail = assertIs<RootComponent.Child.Detail>(root.stack.value.active.instance)
            assertEquals(unknown, detail.component.id)
            assertTrue(detail.component.state.value.failed)
            root.back()
            assertIs<RootComponent.Child.Main>(root.stack.value.active.instance)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun deletedStrikeTargetShowsListRecovery() = runTest {
        val fixtures = Fixtures()
        fixtures.strikes.state.value =
            DataResult.Data<List<Strike>>(emptyList(), DataFreshness.Unknown)
        val (lifecycle, root) = root(fixtures)
        try {
            root.onNotificationDestination(NotificationDestination.Strike("mit-strikes:deleted"))
            advanceTimeBy(1.seconds)
            // The requested detail opens through the shared tree even though
            // the strike is gone; the list is empty (controlled recovery),
            // and Back returns to the overview.
            val main = mainOf(root)
            val alerts = assertIs<MainComponent.Child.Alerts>(
                main.pages.value.items[MainTab.Alerts.ordinal].instance,
            ).component
            assertIs<AlertsTabComponent.Child.Detail>(alerts.stack.value.active.instance)
            assertTrue(alerts.state.value.realtime.data.orEmpty().isEmpty())
            alerts.back()
            assertIs<AlertsTabComponent.Child.Overview>(alerts.stack.value.active.instance)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun pendingDestinationRoutesOnTopOfRestoredTree() = runTest {
        // Build a restored tree: journey tab deep, stations selected.
        val fixtures = Fixtures()
        val lifecycle = LifecycleRegistry()
        val keeper = StateKeeperDispatcher(null)
        val first = DefaultRootComponent(DefaultComponentContext(lifecycle, keeper), factory(fixtures))
        lifecycle.resume()
        val main = assertIs<RootComponent.Child.Main>(first.stack.value.active.instance).component
        main.select(MainTab.Stations)
        runCurrent()
        val saved = keeper.save()
        lifecycle.destroy()

        // Cold entry: the pending destination routes on top of restoration,
        // and Back returns into the restored tree.
        val fresh = Fixtures()
        val revivedLifecycle = LifecycleRegistry()
        val revived = DefaultRootComponent(
            DefaultComponentContext(revivedLifecycle, StateKeeperDispatcher(saved)),
            factory(fresh),
            pendingDestination = trainDestination(testRunId),
        )
        revivedLifecycle.resume()
        advanceTimeBy(1.seconds)
        assertEquals(testRunId, assertIs<RootComponent.Child.Detail>(
            revived.stack.value.active.instance).component.id)
        revived.back()
        val revivedMain = assertIs<RootComponent.Child.Main>(
            revived.stack.value.active.instance).component
        assertEquals(MainTab.Stations.ordinal, revivedMain.pages.value.selectedIndex)
        revivedLifecycle.destroy()
    }

    @Test
    fun strikeRoutingPopsRootPushesToReachSharedTree() = runTest {
        val (lifecycle, root) = root()
        try {
            // A root-level detail is showing; the strike tap returns to the
            // shared Main tree (no second tree) and routes through it.
            root.onNotificationDestination(trainDestination(testRunId))
            advanceTimeBy(1.seconds)
            root.onNotificationDestination(NotificationDestination.Strike(testStrike.id.value))
            advanceTimeBy(1.seconds)
            val main = mainOf(root)
            assertEquals(1, root.stack.value.items.size)
            assertEquals(MainTab.Alerts.ordinal, main.pages.value.selectedIndex)
        } finally {
            lifecycle.destroy()
        }
    }
}
