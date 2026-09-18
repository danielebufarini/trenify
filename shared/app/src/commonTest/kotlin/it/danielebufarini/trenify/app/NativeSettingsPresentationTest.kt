package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import it.danielebufarini.trenify.core.domain.ClearAllFavorites
import it.danielebufarini.trenify.core.domain.ClearSearchHistoryAndRecency
import it.danielebufarini.trenify.core.domain.FavoritesRepository
import it.danielebufarini.trenify.core.domain.HistoryRepository
import it.danielebufarini.trenify.core.domain.MonitorEventKind
import it.danielebufarini.trenify.core.domain.NotificationSettings
import it.danielebufarini.trenify.core.domain.ObserveNotificationSettings
import it.danielebufarini.trenify.core.domain.ObserveStrikeNotifications
import it.danielebufarini.trenify.core.domain.SetDefaultMonitorThresholds
import it.danielebufarini.trenify.core.domain.SetNotificationsEnabled
import it.danielebufarini.trenify.core.domain.SetStrikeNotifications
import it.danielebufarini.trenify.core.platform.EffectiveNotificationPermission
import it.danielebufarini.trenify.core.testing.FakeNotificationPermission
import it.danielebufarini.trenify.core.testing.FakeNotificationSettingsRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.feature.settings.DefaultSettingsComponent
import it.danielebufarini.trenify.feature.settings.FailedSettingsSave
import it.danielebufarini.trenify.feature.settings.PersonalDataDeletionStatus
import it.danielebufarini.trenify.feature.settings.ThresholdValidation
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * T8.11 native Settings projection: the facade projects the existing
 * [DefaultSettingsComponent] truth and forwards its existing actions.
 * Notification, permission, threshold, event-flag, strike and deletion
 * semantics stay shared; this suite proves the projection never invents
 * state, actions or persistence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeSettingsPresentationTest {
    private data class Fixture(
        val settings: FakeNotificationSettingsRepository,
        val strikes: it.danielebufarini.trenify.core.domain.StrikeNotificationRepository,
        val permission: FakeNotificationPermission,
        val repositories: FakeRealtimeRepositories,
        val component: DefaultSettingsComponent,
        val life: LifecycleRegistry,
        val owner: NativeProjectionOwner,
        val facade: NativeSettingsPresentation,
    )

    private fun kotlinx.coroutines.test.TestScope.fixture(
        settings: FakeNotificationSettingsRepository = FakeNotificationSettingsRepository(),
        strikes: it.danielebufarini.trenify.core.domain.StrikeNotificationRepository = FakeStrikeRepository(),
        permission: FakeNotificationPermission = FakeNotificationPermission(),
        repositories: FakeRealtimeRepositories = FakeRealtimeRepositories(),
        history: HistoryRepository = repositories,
        favorites: FavoritesRepository = repositories,
        requestPermission: suspend () -> Boolean = { true },
    ): Fixture {
        val life = LifecycleRegistry()
        val component = DefaultSettingsComponent(
            DefaultComponentContext(life),
            ObserveNotificationSettings(settings),
            SetNotificationsEnabled(settings),
            SetDefaultMonitorThresholds(settings),
            ObserveStrikeNotifications(strikes),
            SetStrikeNotifications(strikes),
            notificationPermission = permission,
            requestNotificationPermission = requestPermission,
            dispatcher = StandardTestDispatcher(testScheduler),
            clearSearchHistoryAndRecency = ClearSearchHistoryAndRecency(history),
            clearAllFavorites = ClearAllFavorites(favorites),
        )
        life.resume()
        val owner = NativeProjectionOwner()
        return Fixture(settings, strikes, permission, repositories, component, life, owner,
            NativeSettingsPresentation(component, owner))
    }

    private fun kotlinx.coroutines.test.TestScope.root(
        keeper: StateKeeperDispatcher = StateKeeperDispatcher(null),
    ): Triple<DefaultRootComponent, LifecycleRegistry, NativeShellPresentation> {
        val life = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val factory = AppComponentFactory(repositories, repositories, repositories, repositories,
            MutableStateFlow(false), StandardTestDispatcher(testScheduler))
        val root = DefaultRootComponent(DefaultComponentContext(life, keeper), factory)
        val shell = createShellPresentation(root)
        life.resume()
        return Triple(root, life, shell)
    }

    @Test fun projectionReflectsLoadingNotificationAndPermissionState() = runTest {
        val f = fixture()
        try {
            runCurrent()
            val state = f.facade.state.value
            assertFalse(state.loading)
            assertTrue(state.notificationsEnabled)
            assertEquals(EffectiveNotificationPermission.GRANTED, state.permission)
            assertEquals("15", state.delayThresholdText)
            assertEquals(ThresholdValidation.Valid, state.delayThresholdValidation)
            assertFalse(state.saving)
            assertNull(state.failedSave)
            // No second copy of business state: the projection equals shared truth.
            assertEquals(f.component.state.value, state)
        } finally { f.owner.close(); f.life.destroy() }
    }

    @Test fun persistedNotificationStateProjects() = runTest {
        val settings = FakeNotificationSettingsRepository(
            initial = NotificationSettings(notificationsEnabled = false))
        val f = fixture(settings = settings)
        try {
            runCurrent()
            assertFalse(f.facade.state.value.notificationsEnabled)
            f.facade.setNotificationsEnabled(true)
            runCurrent()
            assertTrue(f.facade.state.value.notificationsEnabled)
            assertTrue(settings.state.value.notificationsEnabled)
        } finally { f.owner.close(); f.life.destroy() }
    }

    @Test fun deniedPermissionProjectsDistinctFromSavedPreference() = runTest {
        val f = fixture(permission = FakeNotificationPermission(
            effective = EffectiveNotificationPermission.DENIED))
        try {
            runCurrent()
            // Saved preference stays enabled while the OS denies delivery:
            // a denied permission is never presented as disabled monitoring.
            assertTrue(f.facade.state.value.notificationsEnabled)
            assertEquals(EffectiveNotificationPermission.DENIED, f.facade.state.value.permission)
        } finally { f.owner.close(); f.life.destroy() }
    }

    @Test fun thresholdDraftAndValidationProjectAndEditForwards() = runTest {
        val f = fixture()
        try {
            runCurrent()
            f.facade.editDelayThreshold("abc")
            assertEquals("abc", f.facade.state.value.delayThresholdText)
            assertEquals(ThresholdValidation.Invalid, f.facade.state.value.delayThresholdValidation)
            assertEquals("abc", f.component.state.value.delayThresholdText)
            f.facade.editDelayThreshold("0")
            assertEquals(ThresholdValidation.Invalid, f.facade.state.value.delayThresholdValidation)
            f.facade.editDelayThreshold("30")
            assertEquals(ThresholdValidation.Valid, f.facade.state.value.delayThresholdValidation)
            // The draft never leaks into unrelated writes.
            assertTrue(f.settings.writes.isEmpty())
        } finally { f.owner.close(); f.life.destroy() }
    }

    @Test fun savingThresholdForwardsAndPersists() = runTest {
        val f = fixture()
        try {
            runCurrent()
            f.facade.editDelayThreshold("30")
            f.facade.saveDelayThreshold()
            runCurrent()
            assertEquals(30, f.settings.state.value.defaultThresholds.delayMinutes)
            assertEquals("30", f.facade.state.value.delayThresholdText)
            assertFalse(f.facade.state.value.saving)
            assertNull(f.facade.state.value.failedSave)
        } finally { f.owner.close(); f.life.destroy() }
    }

    @Test fun invalidThresholdStaysInvalidRatherThanCoerced() = runTest {
        val f = fixture()
        try {
            runCurrent()
            f.facade.editDelayThreshold("abc")
            f.facade.saveDelayThreshold()
            runCurrent()
            assertEquals(ThresholdValidation.Invalid, f.facade.state.value.delayThresholdValidation)
            assertNull(f.facade.state.value.failedSave)
            assertTrue(f.settings.writes.isEmpty())
        } finally { f.owner.close(); f.life.destroy() }
    }

    @Test fun eachSupportedEventFlagForwards() = runTest {
        val f = fixture()
        try {
            runCurrent()
            val flags = listOf(
                MonitorEventKind.DELAY to { s: NotificationSettings -> s.defaultThresholds.notifyDelay },
                MonitorEventKind.PLATFORM to { s: NotificationSettings -> s.defaultThresholds.notifyPlatform },
                MonitorEventKind.CANCELLATION to { s: NotificationSettings -> s.defaultThresholds.notifyCancellation },
                MonitorEventKind.DEPARTURE to { s: NotificationSettings -> s.defaultThresholds.notifyDeparture },
                MonitorEventKind.ARRIVAL to { s: NotificationSettings -> s.defaultThresholds.notifyArrival },
            )
            for ((kind, read) in flags) {
                f.facade.setEventFlag(kind, false)
                runCurrent()
                assertFalse(read(f.settings.state.value), "flag $kind must persist")
            }
            val projected = f.facade.state.value
            assertFalse(projected.notifyDelay)
            assertFalse(projected.notifyPlatform)
            assertFalse(projected.notifyCancellation)
            assertFalse(projected.notifyDeparture)
            assertFalse(projected.notifyArrival)
        } finally { f.owner.close(); f.life.destroy() }
    }

    @Test fun unsupportedEventKindsHaveNoEditableSetting() = runTest {
        val f = fixture()
        try {
            runCurrent()
            val writes = f.settings.writes.size
            f.facade.setEventFlag(MonitorEventKind.SCHEDULE, false)
            f.facade.setEventFlag(MonitorEventKind.STATUS, false)
            f.facade.setEventFlag(MonitorEventKind.ROUTE_CHANGED, false)
            runCurrent()
            assertEquals(writes, f.settings.writes.size)
            assertNull(f.facade.state.value.failedSave)
        } finally { f.owner.close(); f.life.destroy() }
    }

    @Test fun strikeOptInUsesSharedActionAndViewingNeverWrites() = runTest {
        val strikes = FakeStrikeRepository()
        var strikeWrites = 0
        val countingStrikes = object : it.danielebufarini.trenify.core.domain.StrikeNotificationRepository by strikes {
            override suspend fun setNotificationsEnabled(enabled: Boolean) {
                strikeWrites++
                strikes.setNotificationsEnabled(enabled)
            }
        }
        val f = fixture(strikes = countingStrikes)
        try {
            runCurrent()
            // Simply observing Settings performs no strike write.
            assertFalse(f.facade.state.value.strikeNotificationsEnabled)
            assertEquals(0, strikeWrites)
            f.facade.toggleStrikeNotifications()
            runCurrent()
            assertTrue(f.facade.state.value.strikeNotificationsEnabled)
            assertTrue(strikes.notificationsEnabled.value)
            assertEquals(1, strikeWrites)
            // Reactivity with Alerts through the same repository.
            strikes.notificationsEnabled.value = false
            runCurrent()
            assertFalse(f.facade.state.value.strikeNotificationsEnabled)
        } finally { f.owner.close(); f.life.destroy() }
    }

    @Test fun strikePermissionDeniedStateProjects() = runTest {
        val f = fixture(requestPermission = { false })
        try {
            runCurrent()
            f.facade.toggleStrikeNotifications()
            runCurrent()
            assertTrue(f.facade.state.value.strikePermissionRequestDenied)
            assertFalse(f.facade.state.value.strikeNotificationsEnabled)
        } finally { f.owner.close(); f.life.destroy() }
    }

    @Test fun retryForwardsFailedSave() = runTest {
        val f = fixture()
        try {
            runCurrent()
            f.settings.failure = IllegalStateException("T8.11 save failure")
            f.facade.setNotificationsEnabled(false)
            runCurrent()
            assertIs<FailedSettingsSave.Global>(f.facade.state.value.failedSave)
            f.settings.failure = null
            f.facade.retry()
            runCurrent()
            assertNull(f.facade.state.value.failedSave)
            assertFalse(f.settings.state.value.notificationsEnabled)
            assertFalse(f.facade.state.value.notificationsEnabled)
        } finally { f.owner.close(); f.life.destroy() }
    }

    @Test fun historyDeletionRequestCancelConfirmForward() = runTest {
        val f = fixture()
        try {
            runCurrent()
            assertEquals(PersonalDataDeletionStatus.Idle, f.facade.state.value.historyDeletion)
            f.facade.requestHistoryDeletion()
            assertEquals(PersonalDataDeletionStatus.Confirming, f.facade.state.value.historyDeletion)
            // Cancel is a no-op: no persistence call runs.
            f.facade.cancelHistoryDeletion()
            assertEquals(PersonalDataDeletionStatus.Idle, f.facade.state.value.historyDeletion)
            f.facade.requestHistoryDeletion()
            f.facade.confirmHistoryDeletion()
            runCurrent()
            assertEquals(PersonalDataDeletionStatus.Success, f.facade.state.value.historyDeletion)
            // Duplicate confirm after success stays a no-op success.
            f.facade.confirmHistoryDeletion()
            runCurrent()
            assertEquals(PersonalDataDeletionStatus.Success, f.facade.state.value.historyDeletion)
            assertEquals(PersonalDataDeletionStatus.Idle, f.facade.state.value.favoritesDeletion)
        } finally { f.owner.close(); f.life.destroy() }
    }

    @Test fun favoritesDeletionRequestCancelConfirmForward() = runTest {
        val f = fixture()
        try {
            runCurrent()
            f.facade.requestFavoritesDeletion()
            assertEquals(PersonalDataDeletionStatus.Confirming, f.facade.state.value.favoritesDeletion)
            f.facade.cancelFavoritesDeletion()
            assertEquals(PersonalDataDeletionStatus.Idle, f.facade.state.value.favoritesDeletion)
            f.facade.requestFavoritesDeletion()
            f.facade.confirmFavoritesDeletion()
            runCurrent()
            assertEquals(PersonalDataDeletionStatus.Success, f.facade.state.value.favoritesDeletion)
            assertEquals(PersonalDataDeletionStatus.Idle, f.facade.state.value.historyDeletion)
        } finally { f.owner.close(); f.life.destroy() }
    }

    @Test fun deletionFailureRetriesThroughConfirmPath() = runTest {
        val repositories = FakeRealtimeRepositories()
        val failingHistory = object : HistoryRepository by repositories {
            override suspend fun clearSearchHistoryAndRecency() =
                throw IllegalStateException("T8.11 history deletion failure")
        }
        val f = fixture(history = failingHistory)
        try {
            runCurrent()
            f.facade.requestHistoryDeletion()
            f.facade.confirmHistoryDeletion()
            runCurrent()
            assertEquals(PersonalDataDeletionStatus.Failed, f.facade.state.value.historyDeletion)
            // Confirming again retries; a null failed-save retry also routes here.
            f.facade.retry()
            runCurrent()
            assertEquals(PersonalDataDeletionStatus.Failed, f.facade.state.value.historyDeletion)
        } finally { f.owner.close(); f.life.destroy() }
    }

    @Test fun facadeClosesWithDestinationLifecycle() = runTest {
        val f = fixture()
        try {
            assertEquals(1, f.owner.probes.subscriptions.value)
            f.owner.close()
            runCurrent()
            assertEquals(0, f.owner.probes.subscriptions.value)
            // Closed projections forward nothing.
            f.facade.setNotificationsEnabled(false)
            runCurrent()
            assertTrue(f.settings.state.value.notificationsEnabled)
        } finally { f.owner.close(); f.life.destroy() }
    }

    @Test fun settingsOpensFromEveryPrimaryAreaAsSecondaryNativeDestination() = runTest {
        val (root, life, shell) = root()
        try {
            val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
            for (area in NativePrimaryArea.entries) {
                shell.select(area)
                shell.openSettings()
                val active = shell.state.value.active
                assertEquals(NativeDestination.Settings, active.destination)
                // Settings stays secondary: the primary area is unchanged and
                // Settings is never a fifth primary area.
                assertEquals(area, shell.state.value.primaryArea)
                assertNotNull(active.settings)
                // Repeated opens do not create a second navigation model.
                val identity = active.identity
                val facade = active.settings
                shell.openSettings()
                assertEquals(identity, shell.state.value.active.identity)
                assertSame(facade, shell.state.value.active.settings)
                // Back returns to the shared source.
                shell.back()
                assertEquals(area, shell.state.value.primaryArea)
                assertEquals(main.settingsSource, main.pages.value.items[main.pages.value.selectedIndex].configuration)
            }
        } finally { shell.close(); life.destroy() }
    }

    @Test fun settingsSourceSurvivesRestoration() = runTest {
        val repositories = FakeRealtimeRepositories()
        val factory = AppComponentFactory(repositories, repositories, repositories, repositories,
            MutableStateFlow(false), StandardTestDispatcher(testScheduler))
        val lifecycle = LifecycleRegistry()
        val keeper = StateKeeperDispatcher()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle, stateKeeper = keeper), factory)
        val shell = createShellPresentation(root)
        shell.select(NativePrimaryArea.Saved)
        shell.openSettings()
        assertEquals(NativeDestination.Settings, shell.state.value.active.destination)
        assertNotNull(shell.state.value.active.settings)
        val saved = keeper.save()
        shell.close(); lifecycle.destroy()
        val restoredLifecycle = LifecycleRegistry()
        val restored = DefaultRootComponent(
            DefaultComponentContext(restoredLifecycle, stateKeeper = StateKeeperDispatcher(saved)), factory)
        val owner = NativeProjectionOwner()
        val projection = NativeShellPresentation(restored, owner)
        try {
            assertEquals(NativePrimaryArea.Saved, projection.state.value.primaryArea)
            assertEquals(NativeDestination.Settings, projection.state.value.active.destination)
            assertNotNull(projection.state.value.active.settings)
            projection.back()
            assertEquals(NativeDestination.Saved, projection.state.value.active.destination)
        } finally { projection.close(); restoredLifecycle.destroy() }
    }
}
