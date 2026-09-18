package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import it.danielebufarini.trenify.core.model.StrikeId
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.model.TrainRunId
import it.danielebufarini.trenify.core.platform.NotificationDestination
import it.danielebufarini.trenify.core.testing.FakeJourneyRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.core.testing.testStrike
import it.danielebufarini.trenify.feature.strikes.AlertsTabComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Notification routing when the target already exists in the back stack
 * (T7.13 corrective pass, blocker 2).
 *
 * Decompose `pushNew` throws when an equal configuration already sits in
 * the back stack, so notification routing must use explicit pushToFront
 * semantics: already active stays idempotent, absent pushes normally, and
 * present-below moves to the front without duplicating. Back from a
 * reordered A -> B -> A tap returns through B.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationStackReorderTest {
    private val trainA: TrainRunId = testRunId
    private val trainB: TrainRunId = testRunId.copy(number = TrainNumber("456"))
    private val strikeB = testStrike.copy(id = StrikeId("mit-strikes:8480"), externalId = "8480")

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

    private fun trainDestination(id: TrainRunId): NotificationDestination.Train =
        NotificationDestination.Train(
            provider = id.provider.value,
            number = id.number.value,
            origin = id.origin.value,
            serviceDate = id.serviceDate.toString(),
        )

    private fun mainOf(root: RootComponent): MainComponent =
        root.stack.value.items.map { it.instance }
            .filterIsInstance<RootComponent.Child.Main>().single().component

    private fun alertsOf(root: RootComponent): AlertsTabComponent =
        assertIs<MainComponent.Child.Alerts>(
            mainOf(root).pages.value.items[MainTab.Alerts.ordinal].instance,
        ).component

    private fun activeTrainId(root: RootComponent): TrainRunId =
        assertIs<RootComponent.Child.Detail>(root.stack.value.active.instance).component.id

    private fun activeStrikeId(root: RootComponent): it.danielebufarini.trenify.core.model.StrikeId =
        assertIs<AlertsTabComponent.Child.Detail>(alertsOf(root).stack.value.active.instance).strikeId

    @Test
    fun trainAToBToAReordersWithoutThrowOrDuplicates() = runTest {
        val lifecycle = LifecycleRegistry()
        val fixtures = Fixtures()
        val root = DefaultRootComponent(
            DefaultComponentContext(lifecycle, StateKeeperDispatcher(null)),
            factory(fixtures),
        )
        lifecycle.resume()
        try {
            root.onNotificationDestination(trainDestination(trainA))
            root.onNotificationDestination(trainDestination(trainB))
            // The equal back-stack configuration must move to the front,
            // never throw and never duplicate.
            root.onNotificationDestination(trainDestination(trainA))
            advanceTimeBy(1.seconds)
            assertEquals(trainA, activeTrainId(root))
            assertEquals(
                listOf(
                    DefaultRootComponent.Route.Main,
                    DefaultRootComponent.Route.Detail.of(trainB),
                    DefaultRootComponent.Route.Detail.of(trainA),
                ),
                root.stack.value.items.map { it.configuration },
            )
            // Back from the reordered target returns through B, then Main.
            root.back()
            assertEquals(trainB, activeTrainId(root))
            root.back()
            assertIs<RootComponent.Child.Main>(root.stack.value.active.instance)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun reorderedTargetStaysIdempotentWhileActiveThenRoutesAgainAfterMovingAway() = runTest {
        val lifecycle = LifecycleRegistry()
        val fixtures = Fixtures()
        val root = DefaultRootComponent(
            DefaultComponentContext(lifecycle, StateKeeperDispatcher(null)),
            factory(fixtures),
        )
        lifecycle.resume()
        try {
            root.onNotificationDestination(trainDestination(trainA))
            root.onNotificationDestination(trainDestination(trainB))
            root.onNotificationDestination(trainDestination(trainA))
            advanceTimeBy(1.seconds)
            assertEquals(3, root.stack.value.items.size)
            // Duplicate delivery while the same destination is active is
            // idempotent.
            root.onNotificationDestination(trainDestination(trainA))
            runCurrent()
            assertEquals(3, root.stack.value.items.size)
            assertEquals(trainA, activeTrainId(root))
            // After navigating elsewhere, a legitimate later tap works.
            root.back()
            root.back()
            assertIs<RootComponent.Child.Main>(root.stack.value.active.instance)
            root.onNotificationDestination(trainDestination(trainA))
            advanceTimeBy(1.seconds)
            assertEquals(trainA, activeTrainId(root))
            assertEquals(2, root.stack.value.items.size)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun strikeAToBToAReordersWithoutThrowOrDuplicates() = runTest {
        val lifecycle = LifecycleRegistry()
        val fixtures = Fixtures()
        val root = DefaultRootComponent(
            DefaultComponentContext(lifecycle, StateKeeperDispatcher(null)),
            factory(fixtures),
        )
        lifecycle.resume()
        try {
            root.onNotificationDestination(NotificationDestination.Strike(testStrike.id.value))
            root.onNotificationDestination(NotificationDestination.Strike(strikeB.id.value))
            root.onNotificationDestination(NotificationDestination.Strike(testStrike.id.value))
            advanceTimeBy(1.seconds)
            assertEquals(testStrike.id, activeStrikeId(root))
            val alerts = alertsOf(root)
            assertEquals(3, alerts.stack.value.items.size)
            val visible = alerts.stack.value.items.map { it.instance }
            assertTrue(visible.filterIsInstance<AlertsTabComponent.Child.Detail>()
                .count { it.strikeId == testStrike.id } == 1)
            // Back from the reordered target returns through B, then Overview.
            alerts.back()
            assertEquals(strikeB.id, activeStrikeId(root))
            alerts.back()
            assertIs<AlertsTabComponent.Child.Overview>(alerts.stack.value.active.instance)
            // Duplicate while active stays idempotent; later taps still work.
            root.onNotificationDestination(NotificationDestination.Strike(testStrike.id.value))
            advanceTimeBy(1.seconds)
            root.onNotificationDestination(NotificationDestination.Strike(testStrike.id.value))
            advanceTimeBy(1.seconds)
            assertEquals(2, alertsOf(root).stack.value.items.size)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun restoredRootStackWithTrainBelowPlusColdPendingDoesNotThrowOrDuplicate() = runTest {
        val fixtures = Fixtures()
        val lifecycle = LifecycleRegistry()
        val keeper = StateKeeperDispatcher(null)
        val first = DefaultRootComponent(DefaultComponentContext(lifecycle, keeper), factory(fixtures))
        lifecycle.resume()
        val saved = try {
            first.onNotificationDestination(trainDestination(trainA))
            first.onNotificationDestination(trainDestination(trainB))
            advanceTimeBy(1.seconds)
            keeper.save()
        } finally {
            lifecycle.destroy()
        }

        val fresh = Fixtures()
        val revivedLifecycle = LifecycleRegistry()
        val revived = DefaultRootComponent(
            DefaultComponentContext(revivedLifecycle, StateKeeperDispatcher(saved)),
            factory(fresh),
            pendingDestination = trainDestination(trainA),
        )
        revivedLifecycle.resume()
        try {
            advanceTimeBy(1.seconds)
            assertEquals(trainA, activeTrainId(revived))
            assertEquals(
                listOf(
                    DefaultRootComponent.Route.Main,
                    DefaultRootComponent.Route.Detail.of(trainB),
                    DefaultRootComponent.Route.Detail.of(trainA),
                ),
                revived.stack.value.items.map { it.configuration },
            )
            revived.back()
            assertEquals(trainB, activeTrainId(revived))
            revived.back()
            assertIs<RootComponent.Child.Main>(revived.stack.value.active.instance)
        } finally {
            revivedLifecycle.destroy()
        }
    }

    @Test
    fun restoredAlertsStackWithStrikeBelowPlusIncomingDoesNotThrowOrDuplicate() = runTest {
        val fixtures = Fixtures()
        val lifecycle = LifecycleRegistry()
        val keeper = StateKeeperDispatcher(null)
        val first = DefaultRootComponent(DefaultComponentContext(lifecycle, keeper), factory(fixtures))
        lifecycle.resume()
        val saved = try {
            first.onNotificationDestination(NotificationDestination.Strike(testStrike.id.value))
            first.onNotificationDestination(NotificationDestination.Strike(strikeB.id.value))
            advanceTimeBy(1.seconds)
            keeper.save()
        } finally {
            lifecycle.destroy()
        }

        val fresh = Fixtures()
        val revivedLifecycle = LifecycleRegistry()
        val revived = DefaultRootComponent(
            DefaultComponentContext(revivedLifecycle, StateKeeperDispatcher(saved)),
            factory(fresh),
        )
        revivedLifecycle.resume()
        try {
            advanceTimeBy(1.seconds)
            // Incoming strike already present below another destination:
            // moves to the front without throwing or duplicating.
            revived.onNotificationDestination(NotificationDestination.Strike(testStrike.id.value))
            advanceTimeBy(1.seconds)
            assertEquals(testStrike.id, activeStrikeId(revived))
            val alerts = alertsOf(revived)
            assertEquals(3, alerts.stack.value.items.size)
            assertTrue(alerts.stack.value.items.map { it.instance }
                .filterIsInstance<AlertsTabComponent.Child.Detail>()
                .count { it.strikeId == testStrike.id } == 1)
            alerts.back()
            assertEquals(strikeB.id, activeStrikeId(revived))
            alerts.back()
            assertIs<AlertsTabComponent.Child.Overview>(alerts.stack.value.active.instance)
        } finally {
            revivedLifecycle.destroy()
        }
    }
}
