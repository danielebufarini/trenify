package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.domain.LoadStrikes
import it.danielebufarini.trenify.core.testing.FakeStrikeProvider
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testJourney
import it.danielebufarini.trenify.core.testing.testProviderStrike
import it.danielebufarini.trenify.data.SqlDelightStrikeRepository
import it.danielebufarini.trenify.database.TrenifyDatabase
import it.danielebufarini.trenify.feature.journey.JourneyDetailComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * T7.12 corrective pass, Blocker 1: exact-boundary strikes travel the real
 * path — provider fake filtering, persisted-cache lookup, reconciliation —
 * into the production Journey Detail warning. Fails while any layer still
 * uses strict membership.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrikeBoundaryEndToEndTest {
    @Test
    fun journeyDetailWarnsOnExactBoundaryTouchThroughRealRepository() = runTest {
        val driver = createOrderingTestDriver()
        val database = TrenifyDatabase(driver)
        val clock = MutableClock()
        // Fetch well before the journey so boundary strikes stay schedulable
        // instead of completing at fetch time.
        clock.instant = Instant.parse("2026-09-04T08:00:00Z")
        val provider = FakeStrikeProvider(clock)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = SqlDelightStrikeRepository(database, provider, backgroundScope, clock = clock, dispatcher = dispatcher)
        try {
            val leg = testJourney.legs.single()
            // One strike ends exactly at service departure, another starts
            // exactly at service arrival: touching boundaries count.
            provider.values = listOf(
                testProviderStrike.copy(externalId = "touch-dep", start = leg.departure - 2.hours, end = leg.departure),
                testProviderStrike.copy(externalId = "touch-arr", start = leg.arrival, end = leg.arrival + 2.hours),
            )
            val lifecycle = LifecycleRegistry()
            val component = JourneyDetailComponent(
                DefaultComponentContext(lifecycle),
                testJourney,
                correlate = null,
                onTrain = {},
                dispatcher = StandardTestDispatcher(testScheduler),
                strikes = LoadStrikes(repository),
            )
            lifecycle.resume()
            try {
                advanceUntilIdle()
                val warnings = requireNotNull(component.state.value.strikeWarnings[0])
                assertEquals(
                    setOf("touch-dep", "touch-arr"),
                    warnings.map { it.strike.externalId }.toSet(),
                )
            } finally {
                lifecycle.destroy()
            }
        } finally {
            driver.close()
        }
    }
}
