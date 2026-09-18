package it.danielebufarini.trenify.feature.station

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.domain.HistoryRepository
import it.danielebufarini.trenify.core.domain.PersonalDataWriteSupersededException
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.testStation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * T7.8: station recency is best-effort personal data. A recency write
 * dropped by a completed delete-all (or any persistence failure) must never
 * break opening the board: navigation proceeds and no failure escapes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StationsTabDeletionResilienceTest {
    @Test fun selectingStationStillOpensBoardWhenRecencyWriteFails() = runTest {
        val repositories = FakeRealtimeRepositories()
        val failingHistory = object : HistoryRepository by repositories {
            override suspend fun record(station: Station): Unit =
                throw PersonalDataWriteSupersededException()
        }
        val lifecycle = LifecycleRegistry()
        val tab = DefaultStationsTabComponent(
            DefaultComponentContext(lifecycle),
            repositories,
            repositories,
            failingHistory,
            repositories,
            MutableStateFlow(true),
            onTrainSearch = {},
            onTrain = {},
            StandardTestDispatcher(testScheduler),
        )
        lifecycle.resume()
        try {
            runCurrent()
            val search = assertIs<StationsTabComponent.Child.Search>(tab.stack.value.active.instance).component
            search.query("roma")
            advanceTimeBy(276)
            runCurrent()
            search.select(testStation)
            runCurrent()
            // The dropped recency write neither crashes nor blocks navigation.
            assertIs<StationsTabComponent.Child.Board>(tab.stack.value.active.instance)
        } finally {
            lifecycle.destroy()
        }
    }
}
