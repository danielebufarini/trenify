package it.danielebufarini.trenify.perf

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import it.danielebufarini.trenify.app.NativeJourneyCardPresentation
import it.danielebufarini.trenify.app.NativeJourneyLegPresentation
import it.danielebufarini.trenify.app.NativeJourneyResultsState
import it.danielebufarini.trenify.app.NativeRealtimeFreshness
import it.danielebufarini.trenify.app.NativeRealtimeObservation
import it.danielebufarini.trenify.app.NativeRealtimeProvenancePresentation
import it.danielebufarini.trenify.app.NativeStationBoardState
import it.danielebufarini.trenify.app.NativeStationRow
import it.danielebufarini.trenify.app.NativeStationSearchState
import it.danielebufarini.trenify.app.NativeTrainRow
import it.danielebufarini.trenify.app.NativeTrainRunIdentity
import it.danielebufarini.trenify.core.model.BoardKind
import it.danielebufarini.trenify.core.model.JourneySort
import it.danielebufarini.trenify.core.model.TrainCategory
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.journey.TrenifyJourneyResultsContent
import it.danielebufarini.trenify.stationtrain.StationBoardContent
import it.danielebufarini.trenify.stationtrain.StationSearchContent
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test

/**
 * T8.14-R4 bounded native Android performance evidence (instrumented).
 *
 * Renders production content composables with large deterministic fixtures on
 * the test emulator (Pixel_9 AVD, API 36.1 image, Debug) and reports
 * composition/update/scroll distributions. The v2 compose rule allows one
 * `setContent` per test, so updates are state-driven — which matches
 * production push/pop (recomposition, never activity recreation).
 *
 * No invented thresholds: every case asserts rendering completed (nodes
 * displayed, last row reachable by scroll) and logs median/range for
 * continuous comparison. Scrape via `adb logcat -d | grep T8.14-ANDROID-PERF`.
 */
class T814NativePerformanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun report(name: String, rows: Int, samples: DoubleArray, extra: String = "") {
        val sorted = samples.sorted()
        val line = "T8.14-ANDROID-PERF $name rows=$rows " +
            "median=${"%.1f".format(sorted[sorted.size / 2])}ms " +
            "min=${"%.1f".format(sorted.first())}ms " +
            "max=${"%.1f".format(sorted.last())}ms n=${sorted.size}$extra"
        Log.i("T814", line)
        println(line)
    }

    private fun freshObservation() = NativeRealtimeObservation(
        loading = false,
        failure = null,
        hasContent = true,
        empty = false,
        freshness = NativeRealtimeFreshness.Fresh,
        provenance = NativeRealtimeProvenancePresentation(
            providerName = "test",
            fetchedAtEpochSeconds = 1_788_610_200L,
            sourceTimestampEpochSeconds = 1_788_610_200L,
            stale = false,
            degraded = false,
        ),
    )

    private fun journeyState(cards: Int): NativeJourneyResultsState {
        val at = 1_788_610_200L
        return NativeJourneyResultsState(
            originName = "Roma Termini",
            destinationName = "Milano Centrale",
            windowStartEpochSeconds = at,
            windowEndEpochSeconds = at + 24 * 3600,
            loading = false,
            failure = null,
            hasContent = true,
            empty = false,
            stale = false,
            partial = false,
            strikesStale = false,
            strikesFailed = false,
            strikesUnknown = false,
            sort = JourneySort.DEPARTURE,
            journeys = List(cards) { i ->
                val depart = at + i * 60L
                NativeJourneyCardPresentation(
                    index = i,
                    key = "perf-$i",
                    originName = "Roma Termini",
                    destinationName = "Milano Centrale",
                    departureEpochSeconds = depart,
                    arrivalEpochSeconds = depart + 3 * 3600,
                    durationMinutes = 180,
                    changes = 0,
                    trainIdentities = listOf("Regionale ${10000 + i}"),
                    operatorNames = listOf("Trenitalia"),
                    legs = listOf(
                        NativeJourneyLegPresentation(
                            "Roma Termini", "Milano Centrale", depart, depart + 3 * 3600,
                            180, "Regionale ${10000 + i}", "Trenitalia", null,
                        ),
                    ),
                    sourceNames = null,
                    warningCount = 0,
                    confirmedWarning = false,
                    warnings = emptyList(),
                )
            },
        )
    }

    private fun boardState(trains: Int): NativeStationBoardState {
        val at = 1_788_610_200L
        return NativeStationBoardState(
            stationId = "internal-station",
            station = NativeStationRow("internal-station", "Roma Termini", favorite = false, pending = false, failed = false),
            unavailable = false,
            direction = BoardKind.DEPARTURES,
            observation = freshObservation(),
            trains = List(trains) { i ->
                NativeTrainRow(
                    identity = NativeTrainRunIdentity(
                        key = "test:${10000 + i}:opaque:2026-09-17",
                        provider = "test",
                        number = "${10000 + i}",
                        origin = "opaque",
                        serviceDate = LocalDate(2026, 9, 17),
                    ),
                    originId = "internal-station",
                    originName = "Roma Termini",
                    destinationName = "Milano Centrale",
                    operatorName = "Trenitalia",
                    category = TrainCategory.REG,
                    serviceDate = "2026-09-17",
                    status = TrainStatus.RUNNING,
                    delayMinutes = i % 7,
                    eventEpochSeconds = at + i * 120L,
                    scheduledDepartureEpochSeconds = at + i * 120L,
                    scheduledArrivalEpochSeconds = null,
                    scheduledPlatform = "1",
                    actualPlatform = "1",
                    providerName = "test",
                )
            },
        )
    }

    @Test fun journeyResults200RenderAndScroll() {
        val small = journeyState(1)
        val large = journeyState(200)
        var current by mutableStateOf(small)
        // Cold first composition (single sample: includes activity init).
        val coldMs = kotlin.system.measureNanoTime {
            compose.setContent { TrenifyJourneyResultsContent(current, {}, {}, {}) }
            compose.waitForIdle()
        } / 1_000_000.0
        // Warmup.
        current = large
        compose.waitForIdle()
        current = small
        compose.waitForIdle()
        // Timed small -> large list updates (production push shape).
        val samples = DoubleArray(5) {
            current = small
            compose.waitForIdle()
            kotlin.system.measureNanoTime {
                current = large
                compose.waitForIdle()
            } / 1_000_000.0
        }
        compose.onNodeWithTag("journey-results").assertIsDisplayed()
        // With 200 cards the trailing refresh action sits below the fold, so
        // it is asserted on the swapped-back small state (which also proves
        // the reverse update composes).
        current = small
        compose.waitForIdle()
        compose.onNodeWithTag("journey-refresh").assertIsDisplayed()
        current = large
        compose.waitForIdle()
        // Full scroll pass composes every row on demand; the last card must
        // exist and display, proving the whole 200-row dataset renders.
        val scrollMs = kotlin.system.measureNanoTime {
            compose.onNodeWithTag("journey-results").performScrollToIndex(201)
            compose.waitForIdle()
        } / 1_000_000.0
        compose.onNodeWithTag("journey-result-199").assertIsDisplayed()
        report("journeyResultsUpdate", 200, samples, " coldFirstComposition=${"%.1f".format(coldMs)}ms")
        report("journeyResultsScrollToEnd", 200, doubleArrayOf(scrollMs))
    }

    @Test fun stationBoard300RenderAndScroll() {
        val small = boardState(1)
        val large = boardState(300)
        var current by mutableStateOf(small)
        val coldMs = kotlin.system.measureNanoTime {
            compose.setContent { StationBoardContent(current, {}, {}, {}, {}) }
            compose.waitForIdle()
        } / 1_000_000.0
        current = large
        compose.waitForIdle()
        current = small
        compose.waitForIdle()
        val samples = DoubleArray(5) {
            current = small
            compose.waitForIdle()
            kotlin.system.measureNanoTime {
                current = large
                compose.waitForIdle()
            } / 1_000_000.0
        }
        compose.onNodeWithTag("native-station-board").assertIsDisplayed()
        compose.onNodeWithTag("board-refresh").assertIsDisplayed()
        // Scroll to the last of 300 rows and prove it composes + displays.
        val scrollMs = kotlin.system.measureNanoTime {
            compose.onNodeWithTag("native-station-board").performScrollToIndex(300)
            compose.waitForIdle()
        } / 1_000_000.0
        compose.onNodeWithTag("run-test:10299:opaque:2026-09-17").assertIsDisplayed()
        report("stationBoardUpdate", 300, samples, " coldFirstComposition=${"%.1f".format(coldMs)}ms")
        report("stationBoardScrollToEnd", 300, doubleArrayOf(scrollMs))
    }

    @Test fun cachedSearchSmallRender() {
        val state = NativeStationSearchState(
            query = "rom",
            observation = freshObservation(),
            results = listOf(
                NativeStationRow("s1", "Roma Termini", favorite = true, pending = false, failed = false),
                NativeStationRow("s2", "Roma Tiburtina", favorite = false, pending = false, failed = false),
            ),
            recent = listOf(
                NativeStationRow("s1", "Roma Termini", favorite = true, pending = false, failed = false),
            ),
            recencyFailed = false,
        )
        var revision by mutableStateOf(0)
        val coldMs = kotlin.system.measureNanoTime {
            compose.setContent {
                androidx.compose.runtime.key(revision) {
                    StationSearchContent(state, {}, {}, {}, {}, {}, {}, {})
                }
            }
            compose.waitForIdle()
        } / 1_000_000.0
        // State-driven recompositions of the cached surface.
        revision = 1
        compose.waitForIdle()
        val samples = DoubleArray(5) {
            kotlin.system.measureNanoTime {
                revision++
                compose.waitForIdle()
            } / 1_000_000.0
        }
        compose.onNodeWithTag("native-station-search").assertIsDisplayed()
        report("cachedSearchRecompose", 3, samples, " coldFirstComposition=${"%.1f".format(coldMs)}ms")
    }

    @Test fun destinationPushPopCycles() {
        val journeys = journeyState(50)
        val board = boardState(50)
        var showJourneys by mutableStateOf(true)
        compose.setContent {
            if (showJourneys) TrenifyJourneyResultsContent(journeys, {}, {}, {})
            else StationBoardContent(board, {}, {}, {}, {})
        }
        compose.waitForIdle()
        // Warmup round trip.
        showJourneys = false
        compose.waitForIdle()
        showJourneys = true
        compose.waitForIdle()
        var cycles = 0
        val totalMs = kotlin.system.measureNanoTime {
            repeat(10) {
                showJourneys = false
                compose.waitForIdle()
                compose.onNodeWithTag("native-station-board").assertIsDisplayed()
                showJourneys = true
                compose.waitForIdle()
                compose.onNodeWithTag("journey-results").assertIsDisplayed()
                cycles++
            }
        } / 1_000_000.0
        // 10 round trips == 20 destination compositions, all idle-settled and
        // content-asserted: repeated cycling completes without pathology.
        assert(cycles == 10)
        val line = "T8.14-ANDROID-PERF pushPopCycles cycles=10 compositions=20 " +
            "total=${"%.1f".format(totalMs)}ms perCycle=${"%.1f".format(totalMs / 10)}ms"
        Log.i("T814", line)
        println(line)
    }
}
