package it.danielebufarini.trenify.journey

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import it.danielebufarini.trenify.R
import it.danielebufarini.trenify.app.AppComponentFactory
import it.danielebufarini.trenify.app.DefaultRootComponent
import it.danielebufarini.trenify.app.MainComponent
import it.danielebufarini.trenify.app.MainTab
import it.danielebufarini.trenify.app.NativeJourneyDetailState
import it.danielebufarini.trenify.app.NativeJourneyResultsState
import it.danielebufarini.trenify.app.NativeLegDetailPresentation
import it.danielebufarini.trenify.app.NativeRealtimeProvenancePresentation
import it.danielebufarini.trenify.app.NativeJourneyCardPresentation
import it.danielebufarini.trenify.app.NativeServiceStrikeWarningPresentation
import it.danielebufarini.trenify.app.NativeJourneyLegPresentation
import it.danielebufarini.trenify.app.RootComponent
import it.danielebufarini.trenify.core.domain.BookingHandoffResult
import it.danielebufarini.trenify.core.domain.BookingTarget
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.HistoryRepository
import it.danielebufarini.trenify.core.domain.JourneyRepository
import it.danielebufarini.trenify.core.domain.StrikeImpact
import it.danielebufarini.trenify.core.domain.StrikeRepository
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Journey
import it.danielebufarini.trenify.core.model.JourneyLeg
import it.danielebufarini.trenify.core.model.JourneySearchRequest
import it.danielebufarini.trenify.core.model.JourneySearchResult
import it.danielebufarini.trenify.core.model.JourneySort
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.ProviderId
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.model.StationId
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.model.TrainRun
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.model.TrainStop
import it.danielebufarini.trenify.core.testing.FakeJourneyRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.journeyRequest
import it.danielebufarini.trenify.core.testing.journeyResult
import it.danielebufarini.trenify.core.testing.testJourney
import it.danielebufarini.trenify.core.testing.testStation
import it.danielebufarini.trenify.core.testing.testStrike
import it.danielebufarini.trenify.core.testing.testSummary
import it.danielebufarini.trenify.design.theme.TrenifyTheme
import it.danielebufarini.trenify.feature.journey.JourneyTabComponent
import it.danielebufarini.trenify.navigation.TrenifyAndroidShell
import it.danielebufarini.trenify.presentation.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class TrenifyJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun root(
        lifecycle: LifecycleRegistry,
        trains: FakeRealtimeRepositories = FakeRealtimeRepositories(),
        journeyRepository: JourneyRepository? = null,
        historyRepository: HistoryRepository? = null,
        strikeRepository: StrikeRepository? = null,
        booking: Boolean = false,
    ): RootComponent {
        return DefaultRootComponent(
            DefaultComponentContext(lifecycle),
            AppComponentFactory(
                trains, trains, historyRepository ?: trains, trains, MutableStateFlow(false),
                journeyRepository = journeyRepository ?: FakeJourneyRepository(),
                strikeRepository = strikeRepository,
                bookingAvailability = if (booking) ({ true }) else null,
                openBookingLink = if (booking) {
                    { BookingHandoffResult.Opened(BookingTarget("https://www.trenitalia.com", "Trenitalia")) }
                } else null,
            ),
        )
    }

    private fun journeyTab(root: RootComponent): JourneyTabComponent {
        val main = (root.stack.value.active.instance as RootComponent.Child.Main).component
        return (main.pages.value.items[MainTab.Journey.ordinal].instance as MainComponent.Child.Journey).component
    }

    private fun searchForm(root: RootComponent): it.danielebufarini.trenify.feature.journey.JourneySearchComponent {
        return (journeyTab(root).stack.value.active.instance as JourneyTabComponent.Child.Search).component
    }

    /** Completes the shared composer and submits; Results land on the Journey tab. */
    private fun submitSearch(root: RootComponent) {
        val form = searchForm(root)
        compose.runOnIdle { form.stationText(true, "Roma") }
        compose.waitUntil(timeoutMillis = 5_000) { form.state.value.suggestions.isNotEmpty() }
        compose.runOnIdle { form.select(testStation) }
        compose.runOnIdle { form.stationText(false, "Milano") }
        compose.waitUntil(timeoutMillis = 5_000) { form.state.value.suggestions.isNotEmpty() }
        compose.runOnIdle { form.select(journeyDestination) }
        compose.runOnIdle {
            form.date("2026-09-05")
            form.time("10:00")
            form.search()
            assertEquals(false, form.state.value.invalid)
        }
        compose.runOnIdle {
            val main = (root.stack.value.active.instance as RootComponent.Child.Main).component
            main.select(MainTab.Journey)
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            journeyTab(root).stack.value.active.instance is JourneyTabComponent.Child.Results
        }
    }

    private fun string(id: Int): String =
        compose.activity.getString(id)

    @Test fun resultsRenderDirectJourneyNatively() {
        val lifecycle = LifecycleRegistry()
        lateinit var root: RootComponent
        compose.runOnUiThread { root = root(lifecycle) }
        compose.setContent { TrenifyAndroidShell(root) }
        try {
            submitSearch(root)
            // Native-only markers; the legacy card tag ("journey-result") never appears.
            compose.onNodeWithTag("journey-result").assertDoesNotExist()
            compose.onNodeWithTag("journey-results-route").assertIsDisplayed()
            compose.onNodeWithTag("journey-sort").assertIsDisplayed()
            compose.onNodeWithTag("journey-result-0").assertIsDisplayed()
            // Back/title chrome is shell-owned (native TopAppBar), not per-screen.
            compose.onNodeWithTag("shell-back").assertIsDisplayed()
            compose.onNodeWithText("Frecciarossa 123", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Trenitalia", substring = true).assertIsDisplayed()
            compose.onNodeWithText("3h 00m · Direct · Trenitalia").assertIsDisplayed()
            compose.onNodeWithText("Details").assertHasClickAction()
            compose.onNodeWithTag("journey-sources-0").assertDoesNotExist()
            compose.onNodeWithText(string(R.string.journey_direct), substring = true).assertIsDisplayed()
        } finally {
            compose.runOnUiThread { lifecycle.destroy() }
        }
    }

    @Test fun sortSelectionForwardsAndStaysSelected() {
        val lifecycle = LifecycleRegistry()
        lateinit var root: RootComponent
        compose.runOnUiThread { root = root(lifecycle) }
        compose.setContent { TrenifyAndroidShell(root) }
        try {
            submitSearch(root)
            // Compact menu: the button shows the current sort; options
            // appear only after opening it (fits normal phones).
            compose.onNodeWithTag("journey-sort").assertIsDisplayed().performClick()
            compose.onNodeWithTag("journey-sort-option-duration").assertIsDisplayed().performClick()
            compose.onNodeWithTag("journey-result-0").assertIsDisplayed()
            // Selection persists: reopening shows the check-marked option.
            compose.onNodeWithTag("journey-sort").performClick()
            compose.onNodeWithText(
                "✓ " + string(R.string.journey_sort_duration), substring = true,
            ).assertIsDisplayed()
        } finally {
            compose.runOnUiThread { lifecycle.destroy() }
        }
    }

    @Test fun detailOmitsTechnicalMetadata() {
        // Top-level fetch/source metadata never renders, whatever the
        // provenance state holds; the route summary stays visible.
        compose.setContent {
            TrenifyTheme(darkTheme = false, reduceMotion = true) {
                TrenifyJourneyDetailContent(detailState(), null, {}, {}, {})
            }
        }
        try {
            compose.onNodeWithTag("journey-detail-route").assertIsDisplayed()
            compose.onNodeWithTag("journey-detail-fetched").assertDoesNotExist()
            compose.onNodeWithTag("journey-detail-source").assertDoesNotExist()
            compose.onNodeWithTag("journey-detail-sources").assertDoesNotExist()
        } finally {
        }
    }

    @Test fun resultsPartialStateOmitsWarning() {
        // Partial-source state still renders results; only the visible
        // warning is removed, never the internal partial semantics.
        compose.setContent {
            TrenifyTheme(darkTheme = false, reduceMotion = true) {
                TrenifyJourneyResultsContent(resultsState().copy(partial = true), {}, {}, {})
            }
        }
        try {
            compose.onNodeWithTag("journey-result-0").assertIsDisplayed()
            compose.onNodeWithTag("journey-results-partial").assertDoesNotExist()
        } finally {
        }
    }

    @Test fun resultsProgressiveStateOmitsOutdatedWarning() {
        // Normal progressive/partial presentation: content renders, the
        // generic outdated warning never appears, and no gap is left
        // behind. Guards against reintroducing the banner under any flag.
        compose.setContent {
            TrenifyTheme(darkTheme = false, reduceMotion = true) {
                TrenifyJourneyResultsContent(resultsState().copy(partial = true), {}, {}, {})
            }
        }
        try {
            compose.onNodeWithTag("journey-results-route").assertIsDisplayed()
            compose.onNodeWithTag("journey-result-0").assertIsDisplayed()
            compose.onNodeWithTag("journey-results-stale").assertDoesNotExist()
            capture("journey-results-no-outdated-warning")
        } finally {
        }
    }

    @Test fun detailNormalStateOmitsOutdatedWarning() {
        // Normal detail presentation (realtime enrichment included): route
        // and status render, the generic outdated warning never appears.
        compose.setContent {
            TrenifyTheme(darkTheme = false, reduceMotion = true) {
                TrenifyJourneyDetailContent(detailState(), null, {}, {}, {})
            }
        }
        try {
            compose.onNodeWithTag("journey-detail-route").assertIsDisplayed()
            compose.onNodeWithTag("journey-detail-stale").assertDoesNotExist()
            capture("journey-detail-no-outdated-warning")
        } finally {
        }
    }

    @Test fun resultsNeutralCardOmitsScheduledBadge() {
        // An ordinary scheduled result (no warnings) carries no status
        // badge; a genuine strike warning still renders its pill.
        compose.setContent {
            TrenifyTheme(darkTheme = false, reduceMotion = true) {
                TrenifyJourneyResultsContent(resultsState(), {}, {}, {})
            }
        }
        try {
            compose.onNodeWithTag("journey-result-0").assertIsDisplayed()
            // The test device runs the English catalog: the removed neutral
            // badge exposed "Scheduled" as the pill content description.
            compose.onNodeWithContentDescription("Scheduled", substring = true).assertDoesNotExist()
        } finally {
        }
    }

    @Test fun resultsWarningCardKeepsStatusBadge() {
        val at = journeyRequest.at.epochSeconds
        val warned = resultsState().copy(
            journeys = listOf(
                resultsState().journeys.single().copy(
                    warningCount = 1,
                    confirmedWarning = false,
                    warnings = listOf(
                        NativeServiceStrikeWarningPresentation(
                            strikeId = "mit-strikes:8479",
                            sector = "Ferroviario",
                            startEpochSeconds = at,
                            endEpochSeconds = at + 3 * 3600,
                            impact = StrikeImpact.LIKELY,
                            sourceLabel = "MIT",
                            sourceUrl = "https://scioperi.mit.gov.it/8479",
                            partialContext = false,
                            officiallyConfirmed = false,
                        ),
                    ),
                ),
            ),
        )
        compose.setContent {
            TrenifyTheme(darkTheme = false, reduceMotion = true) {
                TrenifyJourneyResultsContent(warned, {}, {}, {})
            }
        }
        try {
            compose.onNodeWithTag("journey-result-0").assertIsDisplayed()
            // The warning pill exposes its label as content description.
            val warning = compose.activity.resources.getQuantityString(R.plurals.journey_warnings, 1, 1)
            compose.onNodeWithContentDescription(warning, substring = true).assertIsDisplayed()
        } finally {
        }
    }

    @Test fun detailRealtimeCardOmitsProvenanceAndStaleWarning() {
        // Even with a staged stale provenance snapshot held in state, the
        // realtime card shows status only: no source, fetch, update or
        // outdated rows.
        val at = journeyRequest.at.epochSeconds
        compose.setContent {
            TrenifyTheme(darkTheme = false, reduceMotion = true) {
                TrenifyJourneyDetailContent(
                    detailState(
                        delayMinutes = 11,
                        realtimeStale = true,
                        provenance = NativeRealtimeProvenancePresentation(
                            providerName = "ViaggiaTreno",
                            fetchedAtEpochSeconds = at,
                            sourceTimestampEpochSeconds = at,
                            stale = true,
                            degraded = false,
                        ),
                    ),
                    null, {}, {}, {},
                )
            }
        }
        try {
            compose.onNodeWithTag("journey-service-0").assertIsDisplayed()
            // The status pill exposes its label as content description.
            compose.onNodeWithContentDescription(
                compose.activity.getString(R.string.journey_status_delayed, 11), substring = true,
            ).assertIsDisplayed()
            compose.onNodeWithTag("journey-service-0-source").assertDoesNotExist()
            compose.onNodeWithTag("journey-service-0-fetched").assertDoesNotExist()
            compose.onNodeWithTag("journey-service-0-source-update").assertDoesNotExist()
            compose.onNodeWithTag("journey-service-0-stale").assertDoesNotExist()
        } finally {
        }
    }

    @Test fun detailOperatorRowVisibilityFollowsAuthoritativeValue() {
        // Known Journey operator and enriched correlated operator both
        // render; an absent operator omits the row entirely.
        var mode by mutableStateOf(0)
        compose.setContent {
            TrenifyTheme(darkTheme = false, reduceMotion = true) {
                TrenifyJourneyDetailContent(
                    when (mode) {
                        0 -> detailState(operatorName = "Trenitalia")
                        1 -> detailState(operatorName = "Trenord")
                        else -> detailState(operatorName = null)
                    },
                    null, {}, {}, {},
                )
            }
        }
        try {
            compose.onNodeWithTag("journey-operator-0").assertIsDisplayed()
            // Exact match: the booking control ("Continue to …") must not
            // satisfy the operator-row assertion.
            compose.onNodeWithText("Trenitalia", substring = false).assertIsDisplayed()
            compose.runOnIdle { mode = 1 }
            compose.onNodeWithTag("journey-operator-0").assertIsDisplayed()
            compose.onNodeWithText("Trenord", substring = false).assertIsDisplayed()
            compose.runOnIdle { mode = 2 }
            compose.onNodeWithTag("journey-operator-0").assertDoesNotExist()
        } finally {
        }
    }

    @Test fun detailPlatformRowsFollowAvailability() {
        // Expected/actual platforms render independently, each only when
        // available; a platform change shows both values.
        var mode by mutableStateOf(0)
        compose.setContent {
            TrenifyTheme(darkTheme = false, reduceMotion = true) {
                TrenifyJourneyDetailContent(
                    when (mode) {
                        0 -> detailState(scheduledPlatform = "7", actualPlatform = "8")
                        1 -> detailState(scheduledPlatform = "7", actualPlatform = null)
                        2 -> detailState(scheduledPlatform = null, actualPlatform = "8")
                        else -> detailState(scheduledPlatform = null, actualPlatform = null)
                    },
                    null, {}, {}, {},
                )
            }
        }
        try {
            val expected = { value: String ->
                compose.activity.getString(R.string.journey_platform_expected, value)
            }
            val actual = { value: String ->
                compose.activity.getString(R.string.journey_platform_actual, value)
            }
            compose.onNodeWithTag("journey-platform-expected-0").assertIsDisplayed()
            compose.onNodeWithText(expected("7"), substring = true).assertIsDisplayed()
            compose.onNodeWithTag("journey-platform-actual-0").assertIsDisplayed()
            compose.onNodeWithText(actual("8"), substring = true).assertIsDisplayed()
            compose.runOnIdle { mode = 1 }
            compose.onNodeWithTag("journey-platform-expected-0").assertIsDisplayed()
            compose.onNodeWithTag("journey-platform-actual-0").assertDoesNotExist()
            compose.runOnIdle { mode = 2 }
            compose.onNodeWithTag("journey-platform-expected-0").assertDoesNotExist()
            compose.onNodeWithTag("journey-platform-actual-0").assertIsDisplayed()
            compose.onNodeWithText(actual("8"), substring = true).assertIsDisplayed()
            compose.runOnIdle { mode = 3 }
            compose.onNodeWithTag("journey-platform-expected-0").assertDoesNotExist()
            compose.onNodeWithTag("journey-platform-actual-0").assertDoesNotExist()
        } finally {
        }
    }

    @Test fun detailFavoriteFailureKeepsContentVisible() {
        val trains = FakeRealtimeRepositories()
        trains.favoriteFailure = IllegalStateException("boom")
        val lifecycle = LifecycleRegistry()
        lateinit var root: RootComponent
        compose.runOnUiThread { root = root(lifecycle, trains = trains) }
        compose.setContent { TrenifyAndroidShell(root) }
        try {
            submitSearch(root)
            compose.onNodeWithText(string(R.string.ds_details)).performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                try {
                    compose.onNodeWithTag("journey-route-favorite").assertExists()
                    true
                } catch (_: AssertionError) {
                    false
                }
            }
            compose.onNodeWithTag("journey-route-favorite").performClick()
            // Failed toggle: content stays visible with the localized
            // failure; nothing optimistic, nothing disabled around it.
            compose.waitUntil(timeoutMillis = 5_000) {
                try {
                    compose.onNodeWithTag("journey-route-favorite-error").assertExists()
                    true
                } catch (_: AssertionError) {
                    false
                }
            }
            compose.onNodeWithTag("journey-route-favorite-error").assertIsDisplayed()
            compose.onNodeWithTag("journey-leg-0").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { lifecycle.destroy() }
        }
    }

    @Test fun resultsToDetailAndBackKeepsSameHome() {
        val lifecycle = LifecycleRegistry()
        lateinit var root: RootComponent
        compose.runOnUiThread { root = root(lifecycle) }
        compose.setContent { TrenifyAndroidShell(root) }
        try {
            submitSearch(root)
            compose.onNodeWithText(string(R.string.ds_details)).performClick()
            // Native-only detail markers; the legacy provenance block never appears.
            compose.waitUntil(timeoutMillis = 5_000) {
                try {
                    compose.onNodeWithTag("journey-leg-0").assertExists()
                    true
                } catch (_: AssertionError) {
                    false
                }
            }
            compose.onNodeWithTag("journey-detail-route").assertIsDisplayed()
            // Technical metadata and booking unavailable copy never
            // render; the leg without realtime keeps its unavailable
            // marker and the flow can still go back.
            compose.onNodeWithTag("journey-detail-sources").assertDoesNotExist()
            compose.onNodeWithTag("journey-buy-unavailable").assertDoesNotExist()
            compose.onNodeWithTag("journey-realtime-unavailable-0").assertIsDisplayed()
            // Native shell Back drives the shared stack: Detail -> Results.
            compose.onNodeWithTag("shell-back").performClick()
            compose.onNodeWithTag("journey-result-0").assertIsDisplayed()
            // Native shell Back again: Results -> Home.
            compose.onNodeWithTag("shell-back").performClick()
            compose.onNodeWithTag("home-composer").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { lifecycle.destroy() }
        }
    }

    @Test fun detailBookingActionForwardsAndStaysActionable() {
        val lifecycle = LifecycleRegistry()
        lateinit var root: RootComponent
        compose.runOnUiThread { root = root(lifecycle, booking = true) }
        compose.setContent { TrenifyAndroidShell(root) }
        try {
            submitSearch(root)
            compose.onNodeWithText(string(R.string.ds_details)).performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                try {
                    compose.onNodeWithTag("journey-buy").assertExists()
                    true
                } catch (_: AssertionError) {
                    false
                }
            }
            // Available booking keeps only the actionable control; the
            // explanatory disclosure is gone.
            compose.onNodeWithTag("journey-buy-disclosure").assertDoesNotExist()
            compose.onNodeWithTag("journey-buy").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("journey-buy").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                try {
                    compose.onNodeWithTag("journey-buy").assertIsEnabled()
                    true
                } catch (_: AssertionError) {
                    false
                }
            }
            compose.onNodeWithTag("journey-buy").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { lifecycle.destroy() }
        }
    }

    @Test fun detailTrainActionForwardsToLegacyTrainDestination() {
        val trains = FakeRealtimeRepositories()
        val leg = testJourney.legs.single()
        trains.trainState.value = DataResult.Data(
            TrainRun(
                testSummary.copy(operator = leg.operator),
                listOf(
                    TrainStop(testStation, scheduledDeparture = leg.departure),
                    TrainStop(journeyDestination, scheduledArrival = leg.arrival),
                ),
            ),
            DataFreshness.Fresh(MutableClock().now(), null),
        )
        val lifecycle = LifecycleRegistry()
        lateinit var root: RootComponent
        compose.runOnUiThread { root = root(lifecycle, trains = trains) }
        compose.setContent { TrenifyAndroidShell(root) }
        try {
            submitSearch(root)
            compose.onNodeWithText(string(R.string.ds_details)).performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                try {
                    compose.onNodeWithTag("journey-realtime-0").assertExists()
                    true
                } catch (_: AssertionError) {
                    false
                }
            }
            // Correlated enrichment: status pill plus the T8.7 train entry.
            compose.onNodeWithTag("journey-service-0").assertIsDisplayed()
            compose.onNodeWithTag("journey-realtime-0").performClick()
            // T8.7: Journey action enters the native Train Detail in the same shared root.
            compose.waitUntil(timeoutMillis = 5_000) {
                try {
                    compose.onNodeWithTag("train-refresh").assertExists()
                    true
                } catch (_: AssertionError) {
                    false
                }
            }
            compose.onNodeWithTag("native-train-detail").assertExists()
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("journey-leg-0").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { lifecycle.destroy() }
        }
    }

    @Test fun transferJourneyExposesInterchange() {
        val bologna = Station(StationId("internal-bologna"), "Bologna Centrale")
        val at = journeyRequest.at
        val transfer = Journey(
            listOf(
                JourneyLeg(testStation, bologna, at, at + 1.hours, TrainNumber("9516"), "Frecciarossa", Operator("Trenitalia")),
                JourneyLeg(bologna, journeyDestination, at + 75.minutes, at + 3.hours, TrainNumber("4321"), "Regionale", Operator("Trenitalia")),
            ),
            setOf(ProviderId("test")),
        )
        val delegate = FakeJourneyRepository()
        val repository = object : JourneyRepository by delegate {
            private val payload = DataResult.Data(
                JourneySearchResult(listOf(transfer), journeyResult.coverage, journeyRequest.departureFrom, journeyRequest.departureUntil),
                DataFreshness.Fresh(MutableClock().now(), null),
            )
            override fun observe(request: JourneySearchRequest) = kotlinx.coroutines.flow.flowOf(payload)
            override suspend fun search(request: JourneySearchRequest, force: Boolean) = payload
        }
        val lifecycle = LifecycleRegistry()
        lateinit var root: RootComponent
        compose.runOnUiThread { root = root(lifecycle, journeyRepository = repository) }
        compose.setContent { TrenifyAndroidShell(root) }
        try {
            submitSearch(root)
            compose.onNodeWithTag("journey-result-0").assertIsDisplayed()
            val changes = compose.activity.resources.getQuantityString(R.plurals.journey_changes, 1, 1)
            compose.onNodeWithText(changes, substring = true).assertIsDisplayed()
            compose.onNodeWithText("Bologna Centrale", substring = true).assertIsDisplayed()
        } finally {
            compose.runOnUiThread { lifecycle.destroy() }
        }
    }

    @Test fun strikeWarningPresentationKeepsSharedCorrelation() {
        val leg = testJourney.legs.single()
        val strikes = FakeStrikeRepository()
        strikes.state.value = DataResult.Data(
            listOf(testStrike.copy(start = leg.departure - 2.hours, end = leg.arrival + 2.hours)),
            DataFreshness.Fresh(MutableClock().now(), null),
        )
        val lifecycle = LifecycleRegistry()
        lateinit var root: RootComponent
        compose.runOnUiThread { root = root(lifecycle, strikeRepository = strikes) }
        compose.setContent { TrenifyAndroidShell(root) }
        try {
            submitSearch(root)
            // Full warning semantics render per strike id (Blocker 1/1B).
            compose.waitUntil(timeoutMillis = 5_000) {
                try {
                    compose.onNodeWithTag("journey-0-strike-mit-strikes:8479").assertExists()
                    true
                } catch (_: AssertionError) {
                    false
                }
            }
            compose.onNodeWithTag("journey-0-strike-impact-mit-strikes:8479").assertIsDisplayed()
            compose.onNodeWithTag("journey-result-0").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { lifecycle.destroy() }
        }
    }

    @Test fun emptyResultsShowDeliberateEmptyState() {
        val repository = FakeJourneyRepository()
        repository.state.value = DataResult.Data(
            JourneySearchResult(emptyList(), journeyResult.coverage, journeyRequest.departureFrom, journeyRequest.departureUntil),
            DataFreshness.Fresh(MutableClock().now(), null),
        )
        val lifecycle = LifecycleRegistry()
        lateinit var root: RootComponent
        compose.runOnUiThread { root = root(lifecycle, journeyRepository = repository) }
        compose.setContent { TrenifyAndroidShell(root) }
        try {
            submitSearch(root)
            compose.onNodeWithTag("journey-results-empty").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { lifecycle.destroy() }
        }
    }

    @Test fun failureWithoutContentShowsErrorAndRetry() {
        val repository = FakeJourneyRepository()
        repository.state.value = DataResult.Failure(DomainFailure.TEMPORARY)
        val lifecycle = LifecycleRegistry()
        lateinit var root: RootComponent
        compose.runOnUiThread { root = root(lifecycle, journeyRepository = repository) }
        compose.setContent { TrenifyAndroidShell(root) }
        try {
            submitSearch(root)
            compose.onNodeWithTag("journey-results-error").assertIsDisplayed()
            compose.onNodeWithTag("journey-results-error-retry").assertIsDisplayed().assertIsEnabled()
        } finally {
            compose.runOnUiThread { lifecycle.destroy() }
        }
    }

    @Test fun loadingWithoutContentShowsSpinnerNotContent() {
        val delegate = FakeJourneyRepository()
        val gate = CompletableDeferred<Unit>()
        val repository = object : JourneyRepository by delegate {
            override fun observe(request: JourneySearchRequest) = flow {
                gate.await()
                emitAll(delegate.observe(request))
            }
            override suspend fun search(request: JourneySearchRequest, force: Boolean): DataResult<JourneySearchResult> {
                gate.await()
                return delegate.state.value
            }
        }
        val lifecycle = LifecycleRegistry()
        lateinit var root: RootComponent
        compose.runOnUiThread { root = root(lifecycle, journeyRepository = repository) }
        compose.setContent { TrenifyAndroidShell(root) }
        try {
            submitSearch(root)
            compose.onNodeWithTag("journey-results-loading").assertIsDisplayed()
            compose.runOnIdle { gate.complete(Unit) }
            compose.onNodeWithTag("journey-result-0").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { lifecycle.destroy() }
        }
    }

    // MARK: - Deterministic review captures (pure state rendering)

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val resolver = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = resolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TrenifyT86")
            },
        )!!
        resolver.openOutputStream(uri)!!.use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun resultsState(): NativeJourneyResultsState {
        val at = journeyRequest.at.epochSeconds
        fun leg(
            origin: String, destination: String, depart: Long, arrive: Long,
            identity: String?, wait: Long?,
        ) = NativeJourneyLegPresentation(origin, destination, depart, arrive, (arrive - depart) / 60, identity, "Trenitalia", wait)
        return NativeJourneyResultsState(
            originName = testStation.name,
            destinationName = journeyDestination.name,
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
            journeys = listOf(
                NativeJourneyCardPresentation(
                    index = 0,
                    key = "t814-direct",
                    originName = testStation.name,
                    destinationName = journeyDestination.name,
                    departureEpochSeconds = at,
                    arrivalEpochSeconds = at + 3 * 3600,
                    durationMinutes = 180,
                    changes = 0,
                    trainIdentities = listOf("Frecciarossa 9516"),
                    operatorNames = listOf("Trenitalia"),
                    legs = listOf(leg(testStation.name, journeyDestination.name, at, at + 3 * 3600, "Frecciarossa 9516", null)),
                    sourceNames = null,
                    warningCount = 0,
                    confirmedWarning = false,
                    warnings = emptyList(),
                ),
            ),
        )
    }

    private fun detailState(
        delayMinutes: Int? = null,
        warning: Boolean = false,
        operatorName: String? = "Trenitalia",
        scheduledPlatform: String? = "1",
        actualPlatform: String? = "1",
        realtimeStale: Boolean = false,
        provenance: NativeRealtimeProvenancePresentation? = null,
    ): NativeJourneyDetailState {
        val at = journeyRequest.at.epochSeconds
        return NativeJourneyDetailState(
            resolving = false,
            notFound = false,
            originName = testStation.name,
            destinationName = journeyDestination.name,
            departureEpochSeconds = at,
            arrivalEpochSeconds = at + 3 * 3600,
            durationMinutes = 180,
            changes = 0,
            journeyStale = false,
            journeyFetchedAtEpochSeconds = null,
            journeySourceTimestampEpochSeconds = null,
            journeySourceNames = null,
            strikesStale = false,
            strikesFailed = false,
            strikesUnknown = false,
            correlating = false,
            legs = listOf(
                NativeLegDetailPresentation(
                    testStation.name, journeyDestination.name, at, at + 3 * 3600, 180,
                    "Frecciarossa 9516", operatorName, null,
                    if (delayMinutes != null) TrainStatus.RUNNING else TrainStatus.UNKNOWN,
                    delayMinutes, scheduledPlatform, actualPlatform, realtimeStale, false, true, provenance,
                    if (warning) 1 else 0, false, emptyList(),
                ),
            ),
            bookingAvailable = true,
            bookingInProgress = false,
            bookingFailed = false,
            bookingOperatorName = "Trenitalia",
        )
    }

    @Test fun journeyReviewDirectResultsLight() {
        val state = resultsState().copy(
            journeys = listOf(resultsState().journeys.single().copy(sourceNames = "Trenitalia")),
        )
        compose.setContent {
            TrenifyTheme(darkTheme = false, reduceMotion = true) {
                TrenifyJourneyResultsContent(state, {}, {}, {})
            }
        }
        try {
            compose.onNodeWithTag("journey-result-0").assertIsDisplayed()
            compose.onNodeWithTag("journey-sources-0").assertDoesNotExist()
            compose.onNodeWithText("Sources: Trenitalia").assertDoesNotExist()
            capture("android-journey-results-direct")
        } finally {
        }
    }

    @Test fun journeyReviewTransferDelayedAndDetail() {
        val at = journeyRequest.at.epochSeconds
        val transferLegs = listOf(
            NativeJourneyLegPresentation(testStation.name, "Bologna Centrale", at, at + 3600, 60, "Frecciarossa 9516", "Trenitalia", null),
            NativeJourneyLegPresentation("Bologna Centrale", journeyDestination.name, at + 4500, at + 3 * 3600, 105, "Regionale 4321", "Trenitalia", 15),
        )
        val transfer = resultsState().copy(
            journeys = listOf(
                NativeJourneyCardPresentation(
                    index = 0,
                    key = "t814-transfer",
                    originName = testStation.name,
                    destinationName = journeyDestination.name,
                    departureEpochSeconds = at,
                    arrivalEpochSeconds = at + 3 * 3600,
                    durationMinutes = 180,
                    changes = 1,
                    trainIdentities = listOf("Frecciarossa 9516", "Regionale 4321"),
                    operatorNames = listOf("Trenitalia"),
                    legs = transferLegs,
                    sourceNames = null,
                    warningCount = 0,
                    confirmedWarning = false,
                    warnings = emptyList(),
                ),
            ),
        )
        val warning = resultsState().copy(
            journeys = listOf(
                resultsState().journeys.single().copy(
                    warningCount = 1,
                    confirmedWarning = false,
                    warnings = listOf(
                        NativeServiceStrikeWarningPresentation(
                            strikeId = "mit-strikes:8479",
                            sector = "Ferroviario",
                            startEpochSeconds = at,
                            endEpochSeconds = at + 3 * 3600,
                            impact = StrikeImpact.LIKELY,
                            sourceLabel = "MIT",
                            sourceUrl = "https://scioperi.mit.gov.it/8479",
                            partialContext = false,
                            officiallyConfirmed = false,
                        ),
                    ),
                ),
            ),
        )
        var mode by mutableStateOf(0)
        compose.setContent {
            TrenifyTheme(darkTheme = false, reduceMotion = true) {
                when (mode) {
                    0 -> TrenifyJourneyResultsContent(transfer, {}, {}, {})
                    1 -> TrenifyJourneyResultsContent(warning, {}, {}, {})
                    else -> TrenifyJourneyDetailContent(
                        detailState(delayMinutes = 15),
                        null, {}, {}, {},
                    )
                }
            }
        }
        compose.onNodeWithTag("journey-result-0").assertIsDisplayed()
        capture("android-journey-results-transfer")
        compose.runOnIdle { mode = 1 }
        compose.onNodeWithTag("journey-0-strike-mit-strikes:8479").assertIsDisplayed()
        capture("android-journey-results-warning")
        compose.runOnIdle { mode = 2 }
        compose.onNodeWithTag("journey-service-0").assertIsDisplayed()
        capture("android-journey-detail")
    }

    @Test fun journeyReviewEmptyErrorDarkAndLargeText() {
        var dark by mutableStateOf(false)
        var large by mutableStateOf(false)
        var mode by mutableStateOf(0)
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    androidx.compose.ui.platform.LocalDensity.current.density, if (large) 2f else 1f,
                ),
            ) {
                TrenifyTheme(darkTheme = dark, reduceMotion = true) {
                    when (mode) {
                        0 -> TrenifyJourneyResultsContent(resultsState().copy(hasContent = true, empty = true, journeys = emptyList()), {}, {}, {})
                        1 -> TrenifyJourneyResultsContent(
                            resultsState().copy(hasContent = false, empty = false, journeys = emptyList(), failure = DomainFailure.TEMPORARY),
                            {}, {}, {},
                        )
                        else -> TrenifyJourneyDetailContent(detailState(), null, {}, {}, {})
                    }
                }
            }
        }
        try {
            compose.onNodeWithTag("journey-results-empty").assertIsDisplayed()
            capture("android-journey-results-empty")
            compose.runOnIdle { mode = 1 }
            compose.onNodeWithTag("journey-results-error").assertIsDisplayed()
            capture("android-journey-results-error")
            compose.runOnIdle { mode = 2; dark = true }
            compose.onNodeWithTag("journey-detail-route").assertIsDisplayed()
            capture("android-journey-detail-dark")
            compose.runOnIdle { large = true }
            compose.onNodeWithTag("journey-detail-route").assertIsDisplayed()
            capture("android-journey-large-text")
        } finally {
        }
    }

    @Test fun railwayFormattingRomeTimeDurationAndTones() {
        // 2026-09-05 14:10 Europe/Rome (CEST) renders independent of host zone.
        assertEquals("14:10", railwayTime(1788610200))
        assertEquals("17:19", railwayTime(1788621540))
        assertEquals("3h 09m", railwayDurationLabel(189))
        assertEquals("45m", railwayDurationLabel(45))
        assertEquals(it.danielebufarini.trenify.design.component.TrenifyStatusTone.Delayed, railwayStatusTone(TrainStatus.RUNNING, 15))
        assertEquals(it.danielebufarini.trenify.design.component.TrenifyStatusTone.OnTime, railwayStatusTone(TrainStatus.RUNNING, 0))
        // A null delay never implies on-time.
        assertEquals(it.danielebufarini.trenify.design.component.TrenifyStatusTone.Unknown, railwayStatusTone(TrainStatus.RUNNING, null))
        assertEquals(it.danielebufarini.trenify.design.component.TrenifyStatusTone.Cancelled, railwayStatusTone(TrainStatus.CANCELLED, null))
        assertEquals(it.danielebufarini.trenify.design.component.TrenifyStatusTone.Arrived, railwayStatusTone(TrainStatus.ARRIVED, null))
        assertEquals(it.danielebufarini.trenify.design.component.TrenifyStatusTone.Warning, railwayStatusTone(TrainStatus.DIVERTED, null))
        assertEquals(it.danielebufarini.trenify.design.component.TrenifyStatusTone.Unknown, railwayStatusTone(TrainStatus.UNKNOWN, null))
    }
}
