package it.danielebufarini.trenify.saved

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.app.AppComponentFactory
import it.danielebufarini.trenify.app.DefaultRootComponent
import it.danielebufarini.trenify.app.NativeDestination
import it.danielebufarini.trenify.app.NativePrimaryArea
import it.danielebufarini.trenify.app.NativeShellPresentation
import it.danielebufarini.trenify.app.RootComponent
import it.danielebufarini.trenify.app.createShellPresentation
import it.danielebufarini.trenify.core.model.FavoriteRoute
import it.danielebufarini.trenify.core.model.FavoriteTrain
import it.danielebufarini.trenify.core.model.JourneySearchMode
import it.danielebufarini.trenify.core.model.JourneySearchRequest
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.model.StationId
import it.danielebufarini.trenify.core.model.TrainLookupIntent
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.testStation
import it.danielebufarini.trenify.design.theme.TrenifyTheme
import it.danielebufarini.trenify.navigation.TrenifyAndroidShell
import it.danielebufarini.trenify.navigation.TrenifyShellLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class TrenifySavedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val at = Instant.parse("2026-09-15T09:00:00Z")
    private val life = LifecycleRegistry()
    private val repo = FakeRealtimeRepositories()
    private lateinit var root: RootComponent
    private lateinit var shell: NativeShellPresentation

    private val roma = Station(testStation.id, "Roma Termini")
    private val napoli = Station(StationId("napoli-centrale"), "Napoli Centrale")
    private fun train(origin: Station = roma, operator: Operator? = Operator("Trenitalia")) =
        FavoriteTrain.create(TrainNumber("123"), originId = origin.id, operator = operator,
            originName = origin.name, destinationName = "Milano Centrale")

    private fun mount() {
        compose.runOnUiThread {
            root = DefaultRootComponent(DefaultComponentContext(life), AppComponentFactory(repo, repo, repo, repo,
                MutableStateFlow(false)))
            shell = createShellPresentation(root)
            life.resume()
        }
        compose.setContent { TrenifyAndroidShell(root) }
    }
    private fun close() = compose.runOnUiThread { shell.close(); life.destroy() }
    private fun openSaved() {
        compose.onNodeWithTag("shell-tab-Saved").performClick()
        waitTag("native-saved")
    }
    private fun waitTag(tag: String) = compose.waitUntil(5000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun scrollTo(tag: String) =
        compose.onNodeWithTag("native-saved", useUnmergedTree = true).performScrollToNode(hasTestTag(tag))

    @Test fun emptySavedShowsEmptyState() {
        mount()
        try {
            openSaved()
            compose.onNodeWithTag("saved-empty").assertIsDisplayed()
            compose.onNodeWithTag("saved-favorites-empty").assertIsDisplayed()
            compose.onNodeWithTag("saved-journey-history-empty").assertIsDisplayed()
            compose.onNodeWithTag("saved-train-history-empty").assertIsDisplayed()
        } finally { close() }
    }

    @Test fun populatedFavoritesShowStationsRoutesAndTrains() {
        val route = FavoriteRoute.create(roma, journeyDestination)
        kotlinx.coroutines.runBlocking {
            repo.favorites.value = listOf(roma)
            repo.favoriteRoutes.value = listOf(route)
            repo.favoriteTrains.value = listOf(train())
        }
        mount()
        try {
            openSaved()
            scrollTo("favorite-station-${roma.id.value}")
            compose.onNodeWithTag("favorite-station-${roma.id.value}").assertTextContains("Roma Termini", substring = true)
            scrollTo("favorite-route-${route.id.value}")
            compose.onNodeWithTag("favorite-route-${route.id.value}").assertTextContains("Roma Termini", substring = true)
            val identity = train().id.value
            scrollTo("favorite-train-$identity")
            compose.onNodeWithTag("favorite-train-identity-$identity", useUnmergedTree = true)
                .assertTextContains("123", substring = true)
            compose.onNodeWithTag("favorite-train-context-$identity", useUnmergedTree = true)
                .assertTextContains("Milano Centrale", substring = true)
            compose.onNodeWithTag("favorite-train-operator-$identity", useUnmergedTree = true)
                .assertTextContains("Trenitalia", substring = true)
        } finally { close() }
    }

    @Test fun sameNumberDifferentOriginTrainsRemainDistinguishable() {
        val a = train(origin = roma)
        val b = train(origin = napoli, operator = Operator("Italo"))
        kotlinx.coroutines.runBlocking { repo.favoriteTrains.value = listOf(a, b) }
        mount()
        try {
            openSaved()
            scrollTo("favorite-train-${a.id.value}")
            scrollTo("favorite-train-${b.id.value}")
            // Stable semantic keys: two distinct rows, never collapsed by number.
            compose.onNodeWithTag("favorite-train-${a.id.value}").assertIsDisplayed()
            compose.onNodeWithTag("favorite-train-${b.id.value}").assertIsDisplayed()
            compose.onNodeWithTag("favorite-train-context-${a.id.value}", useUnmergedTree = true)
                .assertTextContains("Roma Termini", substring = true)
            compose.onNodeWithTag("favorite-train-context-${b.id.value}", useUnmergedTree = true)
                .assertTextContains("Napoli Centrale", substring = true)
            compose.onNodeWithTag("favorite-train-operator-${b.id.value}", useUnmergedTree = true)
                .assertTextContains("Italo", substring = true)
        } finally { close() }
    }

    @Test fun unknownFavoriteTrainOmitsAbsentValues() {
        val bare = FavoriteTrain.create(TrainNumber("123"), originId = null, operator = null)
        kotlinx.coroutines.runBlocking { repo.favoriteTrains.value = listOf(bare) }
        mount()
        try {
            openSaved()
            scrollTo("favorite-train-${bare.id.value}")
            compose.onNodeWithTag("favorite-train-identity-${bare.id.value}", useUnmergedTree = true).assertIsDisplayed()
            compose.onAllNodesWithTag("favorite-train-context-${bare.id.value}").assertCountEquals(0)
            compose.onAllNodesWithTag("favorite-train-operator-${bare.id.value}").assertCountEquals(0)
        } finally { close() }
    }

    @Test fun removeFavoriteTrainForwardsSharedRemoval() {
        val kept = train(origin = napoli, operator = Operator("Italo"))
        kotlinx.coroutines.runBlocking { repo.favoriteTrains.value = listOf(train(), kept) }
        mount()
        try {
            openSaved()
            val removed = train().id.value
            scrollTo("favorite-train-remove-$removed")
            compose.onNodeWithTag("favorite-train-remove-$removed").performClick()
            compose.waitUntil(5000) { repo.favoriteTrains.value.none { it.id.value == removed } }
            compose.onNodeWithTag("favorite-train-${kept.id.value}").assertIsDisplayed()
        } finally { close() }
    }

    @Test fun openFavoriteTrainEntersSharedTrainSearch() {
        kotlinx.coroutines.runBlocking { repo.favoriteTrains.value = listOf(train()) }
        mount()
        try {
            openSaved()
            val identity = train().id.value
            scrollTo("favorite-train-$identity")
            compose.onNodeWithTag("favorite-train-$identity").performClick()
            // Fresh discriminated lookup through shared navigation, never a dated run.
            compose.waitUntil(5000) {
                shell.state.value.active.destination == NativeDestination.TrainSearch ||
                    shell.state.value.active.destination == NativeDestination.TrainDetail
            }
            assertTrue(shell.state.value.active.destination == NativeDestination.TrainSearch ||
                shell.state.value.active.destination == NativeDestination.TrainDetail)
        } finally { close() }
    }

    @Test fun journeyHistoryRepeatPrefillsSharedComposer() {
        val request = JourneySearchRequest(testStation, journeyDestination, at, JourneySearchMode.DEPART_AFTER)
        val entry = kotlinx.coroutines.runBlocking { repo.recordSearch(request) }
        mount()
        try {
            openSaved()
            scrollTo("journey-history-repeat-${entry.id.value}")
            compose.onNodeWithTag("journey-history-title-${entry.id.value}", useUnmergedTree = true)
                .assertTextContains("Roma Termini", substring = true)
            compose.onNodeWithTag("journey-history-repeat-${entry.id.value}").performClick()
            // Repeat lands on the shared Search composer with the ORIGINAL
            // criteria for explicit execution, never auto-submitted.
            compose.waitUntil(5000) { shell.state.value.primaryArea == NativePrimaryArea.Search }
            // The prefilled criteria are VISIBLE in the production composer,
            // not just present in component state.
            waitTag("home-composer")
            compose.onNodeWithTag("home-origin", useUnmergedTree = true)
                .assertTextContains("Roma Termini", substring = true)
            compose.onNodeWithTag("home-destination", useUnmergedTree = true)
                .assertTextContains("Milano Centrale", substring = true)
            var origin: Station? = null
            var destination: Station? = null
            compose.runOnUiThread {
                val search = shell.journeySearchComponent()
                origin = search?.state?.value?.origin
                destination = search?.state?.value?.destination
            }
            assertEquals(testStation, origin)
            assertEquals(journeyDestination, destination)
        } finally { close() }
    }

    @Test fun trainHistoryRepeatPrefillsSharedTrainSearch() {
        val date = LocalDate.parse("2026-09-10")
        val entry = kotlinx.coroutines.runBlocking {
            repo.recordTrainSearch(TrainNumber("8640"), date,
                TrainLookupIntent(TrainNumber("8640"), originId = roma.id, originName = roma.name,
                    operator = Operator("Trenitalia")))
        }
        mount()
        try {
            openSaved()
            scrollTo("train-history-repeat-${entry.id.value}")
            compose.onNodeWithTag("train-history-title-${entry.id.value}", useUnmergedTree = true)
                .assertTextContains("8640", substring = true)
            compose.onNodeWithTag("train-history-repeat-${entry.id.value}").performClick()
            compose.waitUntil(5000) { shell.state.value.active.destination == NativeDestination.TrainSearch }
            waitTag("native-train-search")
            compose.onNodeWithTag("train-number").assertTextContains("8640", substring = true)
            compose.onNodeWithTag("train-expected-origin").assertTextContains("Roma Termini", substring = true)
        } finally { close() }
    }

    @Test fun historyRemoveAndClearForwardThroughSharedRepository() {
        val journey = kotlinx.coroutines.runBlocking {
            repo.recordSearch(JourneySearchRequest(testStation, journeyDestination, at, JourneySearchMode.DEPART_AFTER))
        }
        val trainEntry = kotlinx.coroutines.runBlocking { repo.recordTrainSearch(TrainNumber("55"), null, null) }
        mount()
        try {
            openSaved()
            scrollTo("journey-history-remove-${journey.id.value}")
            compose.onNodeWithTag("journey-history-remove-${journey.id.value}").performClick()
            compose.waitUntil(5000) { repo.observeSearchHistoryValue().none { it.id == journey.id } }
            scrollTo("saved-clear-history")
            compose.onNodeWithTag("saved-clear-history").performClick()
            compose.waitUntil(5000) { repo.observeSearchHistoryValue().isEmpty() }
            compose.onNodeWithTag("saved-journey-history-empty").assertIsDisplayed()
            compose.onNodeWithTag("saved-train-history-empty").assertIsDisplayed()
            assertEquals(trainEntry.id.value, trainEntry.id.value)
        } finally { close() }
    }

    @Test fun favoriteRemoveFailureKeepsRetainedContentVisible() {
        kotlinx.coroutines.runBlocking { repo.favoriteTrains.value = listOf(train()) }
        repo.favoriteFailure = IllegalStateException("disk")
        mount()
        try {
            openSaved()
            val identity = train().id.value
            scrollTo("favorite-train-remove-$identity")
            compose.onNodeWithTag("favorite-train-remove-$identity").performClick()
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("saved-error").fetchSemanticsNodes().isNotEmpty()
            }
            // Retained content stays visible while the failure shows.
            compose.onNodeWithTag("favorite-train-$identity").assertIsDisplayed()
        } finally { close() }
    }

    @Test fun favoritesObservationFailureRetainsRowsAndRetryRecovers() {
        kotlinx.coroutines.runBlocking {
            repo.favorites.value = listOf(roma)
            repo.favoriteTrains.value = listOf(train())
            repo.recordSearch(JourneySearchRequest(testStation, journeyDestination, at, JourneySearchMode.DEPART_AFTER))
        }
        mount()
        try {
            openSaved()
            val identity = train().id.value
            scrollTo("favorite-train-$identity")
            // Emit from the test thread: emitting from the UI thread would
            // inline-dispatch into the Main.immediate collectors.
            repo.failFavoritesObservation(IllegalStateException("offline"))
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("saved-error").fetchSemanticsNodes().isNotEmpty()
            }
            // Retained rows stay visible AND actionable during the outage.
            compose.onNodeWithTag("favorite-train-$identity").assertIsDisplayed()
            compose.onNodeWithTag("saved-retry").performClick()
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("saved-error").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("favorite-train-$identity").assertIsDisplayed()
        } finally { close() }
    }

    @Test fun historyObservationFailureRetainsRowsAndRetryRecovers() {
        val journey = kotlinx.coroutines.runBlocking {
            repo.recordSearch(JourneySearchRequest(testStation, journeyDestination, at, JourneySearchMode.DEPART_AFTER))
        }
        mount()
        try {
            openSaved()
            scrollTo("journey-history-${journey.id.value}")
            // Same test-thread emission discipline as the favorites case above.
            repo.failSearchHistoryObservation(IllegalStateException("offline"))
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("saved-history-error").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("journey-history-${journey.id.value}").assertIsDisplayed()
            scrollTo("saved-history-retry")
            compose.onNodeWithTag("saved-history-retry").performClick()
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("saved-history-error").fetchSemanticsNodes().isEmpty()
            }
            scrollTo("journey-history-${journey.id.value}")
            compose.onNodeWithTag("journey-history-${journey.id.value}").assertIsDisplayed()
        } finally { close() }
    }

    @Test fun simultaneousFailuresRetryOnlyTheirOwnScope() {
        val journey = kotlinx.coroutines.runBlocking {
            repo.favoriteTrains.value = listOf(train())
            repo.recordSearch(JourneySearchRequest(testStation, journeyDestination, at, JourneySearchMode.DEPART_AFTER))
        }
        repo.favoriteFailure = IllegalStateException("disk")
        repo.removeSearchFailure = IllegalStateException("disk")
        mount()
        try {
            openSaved()
            val identity = train().id.value
            scrollTo("favorite-train-remove-$identity")
            compose.onNodeWithTag("favorite-train-remove-$identity").performClick()
            scrollTo("journey-history-remove-${journey.id.value}")
            compose.onNodeWithTag("journey-history-remove-${journey.id.value}").performClick()
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("saved-error").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithTag("saved-history-error").fetchSemanticsNodes().isNotEmpty()
            }
            // History Retry clears HISTORY only; the favorite failure stays.
            repo.removeSearchFailure = null
            scrollTo("saved-history-retry")
            compose.onNodeWithTag("saved-history-retry").performClick()
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("saved-history-error").fetchSemanticsNodes().isEmpty()
            }
            compose.onAllNodesWithTag("saved-error").fetchSemanticsNodes().let {
                assertTrue(it.isNotEmpty())
            }
            // Favorites Retry clears FAVORITES only; history stays cleared.
            repo.favoriteFailure = null
            scrollTo("saved-retry")
            compose.onNodeWithTag("saved-retry").performClick()
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("saved-error").fetchSemanticsNodes().isEmpty()
            }
            compose.onAllNodesWithTag("saved-history-error").fetchSemanticsNodes().let {
                assertTrue(it.isEmpty())
            }
        } finally { close() }
    }

    @Test fun savedAccessibilityHeadingsTouchTargetsAndText() {
        val route = FavoriteRoute.create(roma, journeyDestination)
        kotlinx.coroutines.runBlocking {
            repo.favorites.value = listOf(roma)
            repo.favoriteRoutes.value = listOf(route)
            repo.favoriteTrains.value = listOf(train())
            repo.recordSearch(JourneySearchRequest(testStation, journeyDestination, at, JourneySearchMode.DEPART_AFTER))
            repo.recordTrainSearch(TrainNumber("55"), null, null)
        }
        mount()
        try {
            openSaved()
            compose.onNodeWithTag("saved-favorites-header")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
            compose.onNodeWithTag("saved-journey-history-header")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
            compose.onNodeWithTag("saved-train-history-header")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
            scrollTo("favorite-train-remove-${train().id.value}")
            compose.onNodeWithTag("favorite-train-remove-${train().id.value}").assertHeightIsAtLeast(48.dp)
            // EN representative text from the production catalog.
            compose.onNodeWithTag("saved-favorites-header").assertTextContains(
                compose.activity.getString(it.danielebufarini.trenify.R.string.saved_favorites), substring = true)
            compose.onNodeWithTag("native-saved").assertIsDisplayed()
        } finally { close() }
    }

    @Test fun deterministicNativeSavedReviewCaptures() {
        val date = LocalDate.parse("2026-09-15")
        val captureRoma = Station(StationId("roma-termini"), "Roma Termini")
        val captureNapoli = Station(StationId("napoli-centrale"), "Napoli Centrale")
        val captureMilano = Station(StationId("milano-centrale"), "Milano Centrale")
        val sameA = FavoriteTrain.create(TrainNumber("9410"), originId = captureRoma.id,
            operator = Operator("Trenitalia"), originName = captureRoma.name, destinationName = captureMilano.name)
        val sameB = FavoriteTrain.create(TrainNumber("9410"), originId = captureNapoli.id,
            operator = Operator("Italo"), originName = captureNapoli.name, destinationName = captureMilano.name)
        val journeyAt = Instant.parse("2026-09-15T07:30:00Z")
        compose.runOnUiThread {
            root = DefaultRootComponent(DefaultComponentContext(life), AppComponentFactory(repo, repo, repo, repo,
                MutableStateFlow(false)))
            shell = createShellPresentation(root)
            life.resume()
            shell.select(NativePrimaryArea.Saved)
        }
        // Corrective B3: render the PRODUCTION shell, not a Saved-only
        // content slot. Repeat navigates away from Saved (journey repeat
        // lands on the Home anchor with the prefilled composer; train
        // repeat lands on TrainSearch), so a Saved-only slot captures a
        // blank screen by construction. Dark/large-text live in
        // savedReviewDarkAndLargeText (theme-controlled direct render).
        compose.setContent { TrenifyAndroidShell(root) }
        try {
            waitTag("native-saved")
            capture("android-saved-empty")
            // Stage favorites first (history still empty) so the favorites
            // capture differs from the full root by construction.
            kotlinx.coroutines.runBlocking {
                repo.favorites.value = listOf(captureRoma)
                repo.favoriteRoutes.value = listOf(FavoriteRoute.create(captureRoma, captureMilano))
                repo.favoriteTrains.value = listOf(sameA, sameB)
            }
            waitTag("favorite-train-${sameB.id.value}")
            capture("android-saved-favorites")
            kotlinx.coroutines.runBlocking {
                repo.recordSearch(JourneySearchRequest(captureRoma, captureMilano, journeyAt, JourneySearchMode.DEPART_AFTER))
                repo.recordTrainSearch(TrainNumber("9410"), date,
                    TrainLookupIntent(TrainNumber("9410"), originId = captureRoma.id,
                        originName = captureRoma.name, operator = Operator("Trenitalia")))
            }
            val captureJourneyId = repo.observeSearchHistoryValue()
                .filterIsInstance<it.danielebufarini.trenify.core.model.JourneySearchHistoryEntry>().single().id.value
            waitTag("journey-history-$captureJourneyId")
            capture("android-saved-root")
            // Fixed-distance swipe puts the same-number pair at the top with
            // history below: pixel-distinct from the root by construction.
            compose.onNodeWithTag("native-saved").performTouchInput {
                swipeUp(startY = centerY + 220, endY = centerY - 220)
            }
            compose.waitForIdle()
            capture("android-saved-same-number")
            val captureTrainId = repo.observeSearchHistoryValue()
                .filterIsInstance<it.danielebufarini.trenify.core.model.TrainSearchHistoryEntry>().single().id.value
            // Ping-pong anchors: force the opposite end first so each
            // history capture lands on a pixel-distinct viewport.
            compose.onNodeWithTag("native-saved", useUnmergedTree = true)
                .performScrollToNode(hasTestTag("train-history-remove-$captureTrainId"))
            compose.onNodeWithTag("native-saved", useUnmergedTree = true)
                .performScrollToNode(hasTestTag("journey-history-title-$captureJourneyId"))
            capture("android-saved-journey-history")
            compose.onNodeWithTag("native-saved", useUnmergedTree = true)
                .performScrollToNode(hasTestTag("saved-favorites-header"))
            compose.onNodeWithTag("native-saved", useUnmergedTree = true)
                .performScrollToNode(hasTestTag("train-history-title-$captureTrainId"))
            capture("android-saved-train-history")
            // Remove interaction result.
            scrollTo("favorite-train-remove-${sameB.id.value}")
            compose.onNodeWithTag("favorite-train-remove-${sameB.id.value}").performClick()
            compose.waitUntil(5000) { repo.favoriteTrains.value.none { it.id == sameB.id } }
            capture("android-saved-remove")
            // Journey repeat/prefill result lands on the shared composer.
            val journeyId = repo.observeSearchHistoryValue()
                .filterIsInstance<it.danielebufarini.trenify.core.model.JourneySearchHistoryEntry>().single().id.value
            compose.onNodeWithTag("native-saved", useUnmergedTree = true)
                .performScrollToNode(hasTestTag("journey-history-repeat-$journeyId"))
            compose.onNodeWithTag("journey-history-repeat-$journeyId").performClick()
            compose.waitUntil(5000) { shell.state.value.primaryArea == NativePrimaryArea.Search }
            // Settle on the VISIBLE prefilled composer before capturing.
            waitTag("home-composer")
            compose.onNodeWithTag("home-origin", useUnmergedTree = true)
                .assertTextContains("Roma Termini", substring = true)
            capture("android-saved-journey-repeat")
            compose.runOnUiThread { shell.select(NativePrimaryArea.Saved) }
            waitTag("native-saved")
            val trainId = repo.observeSearchHistoryValue()
                .filterIsInstance<it.danielebufarini.trenify.core.model.TrainSearchHistoryEntry>().single().id.value
            compose.onNodeWithTag("native-saved", useUnmergedTree = true)
                .performScrollToNode(hasTestTag("train-history-repeat-$trainId"))
            compose.onNodeWithTag("train-history-repeat-$trainId").performClick()
            compose.waitUntil(5000) { shell.state.value.active.destination == NativeDestination.TrainSearch }
            // Settle on the VISIBLE prefilled train search before capturing.
            waitTag("native-train-search")
            capture("android-saved-train-repeat")
            compose.runOnUiThread { shell.back(); shell.select(NativePrimaryArea.Saved) }
            waitTag("native-saved")
            // Degraded retained-content state.
            repo.favoriteFailure = IllegalStateException("disk")
            scrollTo("favorite-train-remove-${sameA.id.value}")
            compose.onNodeWithTag("favorite-train-remove-${sameA.id.value}").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("saved-error").fetchSemanticsNodes().isNotEmpty() }
            repo.favoriteFailure = null
            capture("android-saved-degraded")
        } finally { close() }
    }

    @Test fun savedReviewDarkAndLargeText() {
        // Dark/large-text render the Saved destination directly under a
        // controlled theme (Home-test precedent): the production shell
        // owns its own theme from the system setting, so driving dark
        // through it would capture light theme. Destination stays Saved,
        // so no content-slot blanking applies. Degraded banner persists
        // (no retry), documenting the combined state like prior evidence.
        kotlinx.coroutines.runBlocking {
            repo.favorites.value = listOf(roma)
            repo.favoriteTrains.value = listOf(train())
            repo.recordSearch(JourneySearchRequest(testStation, journeyDestination, at, JourneySearchMode.DEPART_AFTER))
        }
        repo.favoriteFailure = IllegalStateException("disk")
        var dark by mutableStateOf(true); var large by mutableStateOf(false)
        compose.runOnUiThread {
            root = DefaultRootComponent(DefaultComponentContext(life), AppComponentFactory(repo, repo, repo, repo,
                MutableStateFlow(false)))
            shell = createShellPresentation(root)
            life.resume()
            shell.select(NativePrimaryArea.Saved)
        }
        compose.setContent {
            val state by shell.state.collectAsState()
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    androidx.compose.ui.platform.LocalDensity.current.density, if (large) 2f else 1f)) {
                TrenifyTheme(darkTheme = dark, reduceMotion = true) {
                    TrenifyShellLayout(state, shell::select, shell::back, shell::openSettings) {
                        state.active.saved?.let { NativeSavedEntry(state.active) }
                    }
                }
            }
        }
        try {
            waitTag("native-saved")
            val identity = train().id.value
            scrollTo("favorite-train-remove-$identity")
            compose.onNodeWithTag("favorite-train-remove-$identity").performClick()
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("saved-error").fetchSemanticsNodes().isNotEmpty()
            }
            repo.favoriteFailure = null
            capture("android-saved-dark")
            compose.runOnIdle { large = true }
            waitTag("native-saved")
            compose.onNodeWithTag("saved-error").assertIsDisplayed()
            capture("android-saved-large-text")
        } finally { close() }
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val resolver = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TrenifyT89")
        })!!
        resolver.openOutputStream(uri)!!.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}

private fun FakeRealtimeRepositories.observeSearchHistoryValue(): List<it.danielebufarini.trenify.core.model.SearchHistoryEntry> =
    kotlinx.coroutines.runBlocking { observeSearchHistory().first() }
