package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.model.TrainRunId
import it.danielebufarini.trenify.core.platform.NotificationDestination
import it.danielebufarini.trenify.core.testing.FakeJourneyRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.testRunId
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

/**
 * Restore-safe root search-generation semantics (T7.13 corrective pass,
 * blocker 3): a save/destroy/recreate cycle through the real Decompose
 * state keeper must not let a later search push recreate an equal
 * configuration and throw.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RootNavigationGenerationTest {
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

    private fun mainInStack(root: RootComponent): MainComponent =
        root.stack.value.items.map { it.instance }
            .filterIsInstance<RootComponent.Child.Main>().single().component

    @Test
    fun postRestoreSearchPushNeverRecreatesARestoredGeneration() = runTest {
        val fixtures = Fixtures()
        val lifecycle = LifecycleRegistry()
        val keeper = StateKeeperDispatcher(null)
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle, keeper), factory(fixtures))
        lifecycle.resume()
        val saved = try {
            // A prefilled search (generation 1) with a detail on top.
            mainInStack(root).openTrainSearch(TrainNumber("123"), serviceDate = testRunId.serviceDate, expected = null)
            runCurrent()
            assertEquals(1L, assertIs<DefaultRootComponent.Route.Search>(
                root.stack.value.active.configuration).generation)
            root.onNotificationDestination(
                NotificationDestination.Train(
                    provider = testRunId.provider.value,
                    number = testRunId.number.value,
                    origin = testRunId.origin.value,
                    serviceDate = testRunId.serviceDate.toString(),
                ),
            )
            advanceTimeBy(1.seconds)
            assertIs<RootComponent.Child.Detail>(root.stack.value.active.instance)
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
            assertIs<RootComponent.Child.Detail>(revived.stack.value.active.instance)
            // A later search must allocate a fresh generation — never
            // Search(1) again — and must not throw even though Search(1)
            // sits in the back stack.
            mainInStack(revived).openTrainSearch(
                TrainNumber("456"),
                serviceDate = testRunId.serviceDate,
                expected = null,
            )
            runCurrent()
            val configs = revived.stack.value.items.map { it.configuration }
            assertEquals(4, configs.size)
            assertEquals(2L, assertIs<DefaultRootComponent.Route.Search>(configs.last()).generation)
            val generations = configs
                .filterIsInstance<DefaultRootComponent.Route.Search>()
                .map { it.generation }
            assertEquals(generations.size, generations.toSet().size)
            // Back ordering stays correct: S2 -> Detail -> S1 -> Main.
            revived.back()
            assertIs<RootComponent.Child.Detail>(revived.stack.value.active.instance)
            revived.back()
            assertEquals("123", assertIs<RootComponent.Child.Search>(
                revived.stack.value.active.instance).component.state.value.number)
            revived.back()
            assertIs<RootComponent.Child.Main>(revived.stack.value.active.instance)
        } finally {
            revivedLifecycle.destroy()
        }
    }
}
