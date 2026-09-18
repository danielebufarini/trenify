package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.LoadStrikes
import it.danielebufarini.trenify.core.domain.ObserveStrikeNotifications
import it.danielebufarini.trenify.core.domain.OpenStrikeReference
import it.danielebufarini.trenify.core.domain.SetStrikeNotifications
import it.danielebufarini.trenify.core.domain.StrikeInformationPolicy
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.ProviderId
import it.danielebufarini.trenify.core.model.StrikeGeography
import it.danielebufarini.trenify.core.model.StrikeId
import it.danielebufarini.trenify.core.model.StrikeRelevance
import it.danielebufarini.trenify.core.model.StrikeSource
import it.danielebufarini.trenify.core.model.StrikeStatus
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testStrike
import it.danielebufarini.trenify.feature.strikes.AlertsTabComponent
import it.danielebufarini.trenify.feature.strikes.DefaultAlertsTabComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * T8.10 native Alerts projection: semantic snapshots over [AlertsTabComponent].
 * Overview filtering, status truth, railway relevance, scope, freshness,
 * notification and reference semantics stay shared; this suite proves the
 * projection never invents filtering, identity or actions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeAlertsPresentationTest {
    private fun kotlinx.coroutines.test.TestScope.facade(
        repository: FakeStrikeRepository = FakeStrikeRepository(),
        clock: MutableClock = MutableClock(),
        openUrl: (suspend (String) -> Boolean)? = { true },
    ): Triple<NativeAlertsPresentation, DefaultAlertsTabComponent, LifecycleRegistry> {
        val life = LifecycleRegistry()
        val component = DefaultAlertsTabComponent(
            DefaultComponentContext(life),
            LoadStrikes(repository),
            ObserveStrikeNotifications(repository),
            SetStrikeNotifications(repository),
            requestNotificationPermission = { true },
            clock = clock,
            dispatcher = StandardTestDispatcher(testScheduler),
            openStrikeReference = openUrl?.let { OpenStrikeReference(openUrl = it) },
        )
        life.resume()
        val owner = NativeProjectionOwner()
        return Triple(NativeAlertsPresentation(component, owner), component, life)
    }

    @Test fun overviewProjectsVisibleStrikeWithScopeAndReference() = runTest {
        val (facade, _, life) = facade()
        try {
            runCurrent()
            val state = facade.state.value
            assertFalse(state.loading)
            assertEquals(1, state.strikes.size)
            val row = state.strikes.single()
            assertEquals(testStrike.id.value, row.strikeId)
            assertEquals("Ferroviario", row.sector)
            assertEquals(StrikeStatus.SCHEDULED, row.status)
            assertEquals(testStrike.start.epochSeconds, row.startEpochSeconds)
            assertEquals(testStrike.end.epochSeconds, row.endEpochSeconds)
            assertTrue(row.railwayRelevant)
            assertEquals(StrikeRelevance.REGIONAL, row.relevance)
            assertEquals(listOf("Piemonte"), row.regions)
            assertEquals(listOf("Trenitalia"), row.operators)
            assertEquals("24 ore: dalle 21.18 alle 21.00", row.mode)
            assertEquals(listOf("ORSA Ferrovie"), row.unions)
            assertEquals("Personale Trenitalia", row.workforce)
            assertEquals("MIT", row.sourceLabel)
            assertEquals("https://scioperi.mit.gov.it/8479", row.sourceUrl)
            assertTrue(row.canOpenReference)
            assertTrue(facade.canOpenReference(row.sourceUrl))
            assertFalse(facade.canOpenReference("https://www.trenitalia.com"))
            assertFalse(state.notificationsEnabled)
            assertFalse(state.notificationPermissionDenied)
            assertFalse(state.referenceInProgress)
            assertFalse(state.referenceFailed)
            assertFalse(state.visibleEmpty)
        } finally { life.destroy() }
    }

    @Test fun modifiedStrikeStaysVisibleWithExplicitStatus() = runTest {
        val repository = FakeStrikeRepository()
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            repository.state.value = DataResult.Data(
                listOf(testStrike.copy(status = StrikeStatus.MODIFIED)),
                DataFreshness.Fresh(MutableClock().now(), null),
            )
            runCurrent()
            val row = facade.state.value.strikes.single()
            assertEquals(StrikeStatus.MODIFIED, row.status)
            assertEquals(1, facade.state.value.allStrikes.size)
        } finally { life.destroy() }
    }

    @Test fun revokedAndCompletedLeaveOverviewButRemainResolvableAsDetail() = runTest {
        val repository = FakeStrikeRepository()
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            val revoked = testStrike.copy(id = StrikeId("revoked-1"), status = StrikeStatus.REVOKED)
            val completed = testStrike.copy(id = StrikeId("completed-1"), status = StrikeStatus.COMPLETED)
            repository.state.value = DataResult.Data(
                listOf(testStrike, revoked, completed),
                DataFreshness.Fresh(MutableClock().now(), null),
            )
            runCurrent()
            val state = facade.state.value
            assertEquals(listOf(testStrike.id.value), state.strikes.map { it.strikeId })
            assertEquals(3, state.allStrikes.size)
            assertNotNull(facade.detail(revoked.id.value))
            assertEquals(StrikeStatus.REVOKED, facade.detail(revoked.id.value)!!.status)
            assertNotNull(facade.detail(completed.id.value))
            assertTrue(state.visibleEmpty == false)
        } finally { life.destroy() }
    }

    @Test fun endedStrikeIsNotVisible() = runTest {
        val clock = MutableClock(testStrike.end + 1.hours)
        val repository = FakeStrikeRepository()
        val (facade, _, life) = facade(repository, clock)
        try {
            runCurrent()
            // Reference time is past the strike end: the shared predicate
            // hides it from the overview while detail still resolves.
            assertTrue(facade.state.value.strikes.isEmpty())
            assertTrue(facade.state.value.visibleEmpty)
            assertNotNull(facade.detail(testStrike.id.value))
        } finally { life.destroy() }
    }

    @Test fun multipleOperatorsAndGeographyProjected() = runTest {
        val repository = FakeStrikeRepository()
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            repository.state.value = DataResult.Data(
                listOf(
                    testStrike.copy(
                        operators = listOf(Operator("Trenitalia"), Operator("Italo")),
                        geography = StrikeGeography(StrikeRelevance.NATIONAL, regions = listOf("Lazio", "Lombardia"), provinces = listOf("Roma")),
                        notes = "note",
                    ),
                ),
                DataFreshness.Fresh(MutableClock().now(), null),
            )
            runCurrent()
            val row = facade.state.value.strikes.single()
            assertEquals(listOf("Trenitalia", "Italo"), row.operators)
            assertEquals(StrikeRelevance.NATIONAL, row.relevance)
            assertEquals(listOf("Lazio", "Lombardia"), row.regions)
            assertEquals(listOf("Roma"), row.provinces)
            assertEquals("note", row.notes)
        } finally { life.destroy() }
    }

    @Test fun untrustedReferenceIsNotActionable() = runTest {
        val repository = FakeStrikeRepository()
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            repository.state.value = DataResult.Data(
                listOf(testStrike.copy(source = StrikeSource(ProviderId("mit-strikes"), "MIT", "https://www.trenitalia.com"))),
                DataFreshness.Fresh(MutableClock().now(), null),
            )
            runCurrent()
            val row = facade.state.value.strikes.single()
            assertFalse(row.canOpenReference)
        } finally { life.destroy() }
    }

    @Test fun referenceOpenFailureSurfacesTruthfully() = runTest {
        val repository = FakeStrikeRepository()
        val (facade, _, life) = facade(repository, openUrl = { false })
        try {
            runCurrent()
            facade.openReference("https://scioperi.mit.gov.it/8479")
            runCurrent()
            assertTrue(facade.state.value.referenceFailed)
            assertFalse(facade.state.value.referenceInProgress)
        } finally { life.destroy() }
    }

    @Test fun rejectedReferenceFailsWithoutLaunch() = runTest {
        var calls = 0
        val repository = FakeStrikeRepository()
        val (facade, _, life) = facade(repository, openUrl = { calls++; true })
        try {
            runCurrent()
            facade.openReference("https://www.trenitalia.com")
            runCurrent()
            assertEquals(0, calls)
            assertTrue(facade.state.value.referenceFailed)
        } finally { life.destroy() }
    }

    @Test fun refreshInProgressFreshAndStaleProjected() = runTest {
        val repository = FakeStrikeRepository()
        val clock = MutableClock()
        val (facade, component, life) = facade(repository, clock)
        try {
            runCurrent()
            component.refresh()
            assertTrue(facade.state.value.loading)
            runCurrent()
            assertFalse(facade.state.value.loading)
            repository.state.value = DataResult.Data(
                listOf(testStrike),
                DataFreshness.Stale(clock.now(), 1.hours, null),
                it.danielebufarini.trenify.core.domain.DomainFailure.TEMPORARY,
            )
            runCurrent()
            val state = facade.state.value
            // Stale cached data preserves useful content with failure info.
            assertEquals(1, state.strikes.size)
            assertNotNull(state.observation.failure)
            assertTrue(state.hasRetainedContent)
        } finally { life.destroy() }
    }

    @Test fun providerFailureWithRetainedContentKeepsCards() = runTest {
        val repository = FakeStrikeRepository()
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            assertEquals(1, facade.state.value.strikes.size)
            repository.state.value = DataResult.Failure(DomainFailure.OFFLINE)
            runCurrent()
            // Failure without new data retains the useful cached cards.
            assertEquals(1, facade.state.value.strikes.size)
            assertEquals(DomainFailure.OFFLINE, facade.state.value.observation.failure)
        } finally { life.destroy() }
    }

    @Test fun emptyUpcomingAlertState() = runTest {
        val repository = FakeStrikeRepository()
        repository.state.value = DataResult.Data(emptyList(), DataFreshness.Fresh(MutableClock().now(), null))
        repository.refreshResult = DataResult.Data(
            it.danielebufarini.trenify.core.domain.StrikeRefresh(emptyList()),
            DataFreshness.Fresh(MutableClock().now(), null),
        )
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            assertTrue(facade.state.value.strikes.isEmpty())
            assertTrue(facade.state.value.visibleEmpty)
            assertNull(facade.detail("missing"))
        } finally { life.destroy() }
    }

    @Test fun unknownSelectedStrikeIsControlledNotFound() = runTest {
        val (facade, _, life) = facade()
        try {
            runCurrent()
            assertNull(facade.detail("no-such-strike"))
            // Opening an unknown id is a no-op through the shared action.
            facade.open("no-such-strike")
            runCurrent()
        } finally { life.destroy() }
    }

    @Test fun disappearedStrikeBecomesNotFound() = runTest {
        val repository = FakeStrikeRepository()
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            assertNotNull(facade.detail(testStrike.id.value))
            repository.state.value = DataResult.Data(emptyList(), DataFreshness.Fresh(MutableClock().now(), null))
            runCurrent()
            assertNull(facade.detail(testStrike.id.value))
            assertTrue(facade.state.value.visibleEmpty)
        } finally { life.destroy() }
    }

    @Test fun overviewToDetailUsesSharedActionAndBackUsesNavigation() = runTest {
        val (facade, component, life) = facade()
        try {
            runCurrent()
            facade.open(testStrike.id.value)
            runCurrent()
            assertEquals(
                testStrike.id,
                assertIs<AlertsTabComponent.Child.Detail>(component.stack.value.active.instance).strikeId,
            )
            facade.back()
            runCurrent()
            assertIs<AlertsTabComponent.Child.Overview>(component.stack.value.active.instance)
        } finally { life.destroy() }
    }

    @Test fun notificationToggleDelegatesAndDenialStaysTruthful() = runTest {
        val repository = FakeStrikeRepository()
        val life = LifecycleRegistry()
        var grant = false
        val component = DefaultAlertsTabComponent(
            DefaultComponentContext(life),
            LoadStrikes(repository),
            ObserveStrikeNotifications(repository),
            SetStrikeNotifications(repository),
            requestNotificationPermission = { grant },
            clock = MutableClock(),
            dispatcher = StandardTestDispatcher(testScheduler),
            openStrikeReference = OpenStrikeReference(StrikeInformationPolicy()) { true },
        )
        life.resume()
        val owner = NativeProjectionOwner()
        val facade = NativeAlertsPresentation(component, owner)
        try {
            runCurrent()
            assertFalse(facade.state.value.notificationsEnabled)
            facade.toggleNotifications()
            runCurrent()
            assertFalse(facade.state.value.notificationsEnabled)
            assertTrue(facade.state.value.notificationPermissionDenied)
            grant = true
            facade.toggleNotifications()
            runCurrent()
            assertTrue(facade.state.value.notificationsEnabled)
            assertFalse(facade.state.value.notificationPermissionDenied)
        } finally { life.destroy() }
    }

    @Test fun restorationRetainsSelectedStrikeIdentity() = runTest {
        val repository = FakeStrikeRepository()
        val factoryStrikes = repository
        val realtime = FakeRealtimeRepositories()
        val factory = AppComponentFactory(
            realtime, realtime, realtime, realtime, MutableStateFlow(false),
            StandardTestDispatcher(testScheduler),
            strikeRepository = factoryStrikes, strikeNotificationRepository = factoryStrikes,
        )
        val life = LifecycleRegistry()
        val keeper = StateKeeperDispatcher()
        val root = DefaultRootComponent(DefaultComponentContext(life, stateKeeper = keeper), factory)
        try {
            life.resume()
            runCurrent()
            val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
            main.select(MainTab.Alerts)
            runCurrent()
            val alerts = assertIs<MainComponent.Child.Alerts>(
                main.pages.value.items[MainTab.Alerts.ordinal].instance,
            ).component
            alerts.open(testStrike.id)
            runCurrent()
            val saved = keeper.save()
            val revivedLife = LifecycleRegistry()
            val revived = DefaultRootComponent(
                DefaultComponentContext(revivedLife, stateKeeper = StateKeeperDispatcher(saved)), factory,
            )
            try {
                val revivedMain = assertIs<RootComponent.Child.Main>(revived.stack.value.active.instance).component
                revivedMain.select(MainTab.Alerts)
                runCurrent()
                val revivedAlerts = assertIs<MainComponent.Child.Alerts>(
                    revivedMain.pages.value.items[MainTab.Alerts.ordinal].instance,
                ).component
                val detail = assertIs<AlertsTabComponent.Child.Detail>(revivedAlerts.stack.value.active.instance)
                assertEquals(testStrike.id, detail.strikeId)
                val owner = NativeProjectionOwner()
                val revivedFacade = NativeAlertsPresentation(revivedAlerts, owner)
                assertNotNull(revivedFacade.detail(testStrike.id.value))
                owner.close()
            } finally { revivedLife.destroy() }
        } finally { life.destroy() }
    }

    @Test fun projectionLifecycleClosesWithoutLeak() = runTest {
        val (facade, _, life) = facade()
        try {
            runCurrent()
            assertNotNull(facade.state.value)
            life.destroy()
            runCurrent()
        } finally { life.destroy() }
    }

    @Test fun shellCachesAlertsFacade() = runTest {
        val realtime = FakeRealtimeRepositories()
        val strikes = FakeStrikeRepository()
        val life = LifecycleRegistry()
        val factory = AppComponentFactory(
            realtime, realtime, realtime, realtime, MutableStateFlow(false),
            StandardTestDispatcher(testScheduler),
            strikeRepository = strikes, strikeNotificationRepository = strikes,
        )
        val root = DefaultRootComponent(DefaultComponentContext(life), factory)
        val shell = createShellPresentation(root)
        try {
            life.resume()
            runCurrent()
            shell.select(NativePrimaryArea.Alerts)
            runCurrent()
            val overview = shell.state.value.base
            assertEquals(NativeDestination.AlertsOverview, overview.destination)
            assertNotNull(overview.alerts)
            assertNull(overview.alertStrikeId)
            val again = shell.state.value.base.alerts
            assertTrue(overview.alerts === again)
            // Open detail through the shared component: same facade, per-entry strike id.
            val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
            val alerts = assertIs<MainComponent.Child.Alerts>(
                main.pages.value.items[MainTab.Alerts.ordinal].instance,
            ).component
            alerts.open(testStrike.id)
            runCurrent()
            assertEquals(1, shell.state.value.path.size)
            val detail = shell.state.value.active
            assertEquals(NativeDestination.StrikeDetail, detail.destination)
            assertEquals(testStrike.id.value, detail.alertStrikeId)
            assertTrue(detail.alerts === overview.alerts)
            // Back restores the overview through shared navigation.
            shell.back()
            runCurrent()
            assertEquals(NativeDestination.AlertsOverview, shell.state.value.active.destination)
        } finally {
            shell.close()
            life.destroy()
        }
    }

    @Test fun duplicateStrikeRouteIsIdempotent() = runTest {
        val realtime = FakeRealtimeRepositories()
        val strikes = FakeStrikeRepository()
        val life = LifecycleRegistry()
        val factory = AppComponentFactory(
            realtime, realtime, realtime, realtime, MutableStateFlow(false),
            StandardTestDispatcher(testScheduler),
            strikeRepository = strikes, strikeNotificationRepository = strikes,
        )
        val root = DefaultRootComponent(DefaultComponentContext(life), factory)
        val shell = createShellPresentation(root)
        try {
            life.resume()
            runCurrent()
            val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
            assertTrue(main.routeStrikeIfNew(testStrike.id))
            runCurrent()
            val token = shell.state.value.active.identity
            assertFalse(main.routeStrikeIfNew(testStrike.id))
            runCurrent()
            assertEquals(token, shell.state.value.active.identity)
            assertEquals(NativeDestination.StrikeDetail, shell.state.value.active.destination)
        } finally {
            shell.close()
            life.destroy()
        }
    }
}
