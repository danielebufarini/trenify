package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.SearchJourneys
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Journey
import it.danielebufarini.trenify.core.model.JourneyLeg
import it.danielebufarini.trenify.core.model.JourneySearchResult
import it.danielebufarini.trenify.core.model.JourneySort
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.ProviderId
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.testing.FakeJourneyRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.journeyRequest
import it.danielebufarini.trenify.core.testing.journeyResult
import it.danielebufarini.trenify.core.testing.testStation
import it.danielebufarini.trenify.core.testing.testSummary
import it.danielebufarini.trenify.feature.journey.JourneyResultsComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * T8.14 deterministic performance baseline (JVM, androidHostTest).
 *
 * Measures production projection paths over large deterministic fixtures and
 * prints `T8.14-PERF` lines consumed by `doc/design/review/t8_14/`.
 * Bounds below are pathology guards (seconds, not product gates): they fail
 * only on structural blowups such as accidental quadratic work or eager
 * whole-collection rendering, never on ordinary machine variance. No product
 * threshold is invented here; the recorded medians are the baseline for
 * future continuous comparison.
 */
class T814PerformanceBaselineTest {
    private data class Distribution(val medianMs: Double, val minMs: Double, val maxMs: Double, val runs: Int) {
        override fun toString(): String = "median=${"%.2f".format(medianMs)}ms min=${"%.2f".format(minMs)}ms " +
            "max=${"%.2f".format(maxMs)}ms n=$runs"
    }

    private fun measure(name: String, rows: Int, warmup: Int = 3, runs: Int = 11, block: () -> Unit): Distribution {
        repeat(warmup) { block() }
        val samples = DoubleArray(runs) { measureNanoTime(block) / 1_000_000.0 }
        samples.sort()
        val distribution = Distribution(samples[runs / 2], samples.first(), samples.last(), runs)
        println("T8.14-PERF $name rows=$rows $distribution")
        return distribution
    }

    private fun journeyFixture(size: Int, shiftSeconds: Long = 0) = List(size) { i ->
        val departure = journeyRequest.at + i.minutes + shiftSeconds.seconds
        Journey(
            listOf(
                JourneyLeg(
                    testStation, journeyDestination, departure, departure + (1 + i % 5).hours,
                    TrainNumber((10000 + i).toString()), "Regionale", Operator("Trenitalia"),
                ),
            ),
            setOf(ProviderId("test")),
        )
    }

    /**
     * Corrective T8.14-R2: every timed iteration publishes a deterministically
     * DISTINCT shared state (departures shifted by the iteration index), so
     * StateFlow conflation cannot suppress the emission and each iteration
     * executes the real component-sort + facade-projection path. A projection
     * counter proves all timed iterations were consumed; equal-state
     * assignments (the invalid ~0.05 ms baseline) are superseded, not kept.
     */
    @Test fun journeyResultsProjectionOver200Journeys() {
        val size = 200
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            val repository = FakeJourneyRepository()
            val facade = NativeJourneyResultsPresentation(
                JourneyResultsComponent(
                    DefaultComponentContext(lifecycle),
                    journeyRequest,
                    SearchJourneys(repository),
                    {},
                    Dispatchers.Unconfined,
                ),
                NativeProjectionOwner(),
            )
            runBlocking {
                withTimeout(30_000) {
                    // The fake repository opens with its single default journey.
                    while (facade.state.value.journeys.size != 1) {
                        kotlinx.coroutines.delay(1)
                    }
                }
            }
            var iteration = 0
            val observedMarkers = mutableSetOf<Long>()
            // Records the iteration marker carried by each facade emission:
            // deterministic proof every timed iteration really projected.
            val observer = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined).launch {
                facade.state.collect { state ->
                    state.journeys.firstOrNull()?.departureEpochSeconds?.let(observedMarkers::add)
                }
            }
            try {
                // Warmup (3) + timed runs (11): shifts 1..14 stay distinct
                // from the initial unshifted state and from each other.
                val expectedMarkers = (1..14).map { journeyRequest.at.epochSeconds + it }.toSet()
                val distribution = measure("journeyResultsProjection", size) {
                    runBlocking {
                        // Distinct per iteration: all departures shift by
                        // (iteration + 1) seconds, forcing a real emission
                        // through component sort + facade projection.
                        val k = ++iteration
                        val shifted = journeyFixture(size, shiftSeconds = k.toLong())
                        val marker = shifted.minOf { it.departure.epochSeconds }
                        repository.state.value = DataResult.Data(
                            JourneySearchResult(shifted, journeyResult.coverage,
                                journeyRequest.departureFrom, journeyRequest.departureUntil),
                            DataFreshness.Fresh(MutableClock().now(), null),
                        )
                        withTimeout(30_000) {
                            while (facade.state.value.journeys.firstOrNull()?.departureEpochSeconds != marker) {
                                kotlinx.coroutines.delay(1)
                            }
                        }
                    }
                }
                assertEquals(size, facade.state.value.journeys.size)
                assertTrue(observedMarkers.containsAll(expectedMarkers),
                    "missing projections: expected $expectedMarkers observed $observedMarkers")
                assertTrue(distribution.medianMs < 5_000.0, "projection pathology: $distribution")
            } finally {
                observer.cancel()
            }
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun journeyResortOver200Journeys() {
        val journeys = journeyFixture(200)
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            val repository = FakeJourneyRepository()
            repository.state.value = DataResult.Data(
                JourneySearchResult(journeys, journeyResult.coverage,
                    journeyRequest.departureFrom, journeyRequest.departureUntil),
                DataFreshness.Fresh(MutableClock().now(), null),
            )
            val facade = NativeJourneyResultsPresentation(
                JourneyResultsComponent(
                    DefaultComponentContext(lifecycle),
                    journeyRequest,
                    SearchJourneys(repository),
                    {},
                    Dispatchers.Unconfined,
                ),
                NativeProjectionOwner(),
            )
            val sorts = listOf(JourneySort.DURATION, JourneySort.ARRIVAL, JourneySort.CHANGES, JourneySort.DEPARTURE)
            var round = 0
            // Proof note: every iteration sets a sort different from the
            // previous one and waits until the facade publishes that exact
            // sort, so each timed iteration provably re-sorted + re-projected.
            val distribution = measure("journeyResort", journeys.size) {
                runBlocking {
                    val sort = sorts[round++ % sorts.size]
                    facade.setSort(sort)
                    withTimeout(30_000) {
                        while (facade.state.value.sort != sort) kotlinx.coroutines.delay(1)
                    }
                }
            }
            assertTrue(distribution.medianMs < 5_000.0, "resort pathology: $distribution")
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun stationBoardRowMappingOver500Rows() {
        val summaries = List(500) { i ->
            testSummary.copy(
                id = testSummary.id.copy(number = TrainNumber((10000 + i).toString())),
                scheduledTime = journeyRequest.at + i.minutes,
            )
        }
        var mapped = 0
        val distribution = measure("stationBoardRowMapping", summaries.size) {
            mapped = summaries.map { it.nativeRow() }.size
        }
        assertEquals(summaries.size, mapped)
        assertTrue(distribution.medianMs < 5_000.0, "row-mapping pathology: $distribution")
    }
}
