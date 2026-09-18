package it.danielebufarini.trenify.feature.settings

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.domain.MonitorEventKind
import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.NotificationSettings
import it.danielebufarini.trenify.core.domain.NotificationSettingsRepository
import it.danielebufarini.trenify.core.domain.ObserveNotificationSettings
import it.danielebufarini.trenify.core.domain.ObserveStrikeNotifications
import it.danielebufarini.trenify.core.domain.SetDefaultMonitorThresholds
import it.danielebufarini.trenify.core.domain.SetNotificationsEnabled
import it.danielebufarini.trenify.core.domain.SetStrikeNotifications
import it.danielebufarini.trenify.core.platform.EffectiveNotificationPermission
import it.danielebufarini.trenify.core.platform.NotificationPermission
import it.danielebufarini.trenify.core.testing.FakeNotificationPermission
import it.danielebufarini.trenify.core.testing.FakeNotificationSettingsRepository
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T7.7 Settings vertical slice: persistent defaults, reactive saves,
 * validation, permission presentation, failure/retry, lifecycle and the
 * single shared strike opt-in with Alerts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsComponentTest {
    private fun kotlinx.coroutines.test.TestScope.component(
        lifecycle: LifecycleRegistry = LifecycleRegistry(),
        settings: it.danielebufarini.trenify.core.domain.NotificationSettingsRepository =
            FakeNotificationSettingsRepository(),
        strikes: it.danielebufarini.trenify.core.domain.StrikeNotificationRepository = FakeStrikeRepository(),
        permission: NotificationPermission = FakeNotificationPermission(),
        requestPermission: suspend () -> Boolean = { true },
    ) = DefaultSettingsComponent(
        DefaultComponentContext(lifecycle),
        ObserveNotificationSettings(settings),
        SetNotificationsEnabled(settings),
        SetDefaultMonitorThresholds(settings),
        ObserveStrikeNotifications(strikes),
        SetStrikeNotifications(strikes),
        notificationPermission = permission,
        requestNotificationPermission = requestPermission,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test fun exposesPersistentDefaults() = runTest {
        val component = component()
        runCurrent()

        val state = component.state.value
        assertFalse(state.loading)
        assertTrue(state.notificationsEnabled)
        assertEquals("15", state.delayThresholdText)
        assertEquals(ThresholdValidation.Valid, state.delayThresholdValidation)
        assertTrue(state.notifyDelay)
        assertTrue(state.notifyPlatform)
        assertTrue(state.notifyCancellation)
        assertTrue(state.notifyDeparture)
        assertTrue(state.notifyArrival)
        assertFalse(state.strikeNotificationsEnabled)
        assertEquals(EffectiveNotificationPermission.GRANTED, state.permission)
        assertFalse(state.saving)
        assertNull(state.failedSave)
    }

    @Test fun savesAreReactive() = runTest {
        val settings = FakeNotificationSettingsRepository()
        val component = component(settings = settings)
        runCurrent()

        component.setNotificationsEnabled(false)
        runCurrent()
        assertFalse(component.state.value.notificationsEnabled)
        assertFalse(settings.state.value.notificationsEnabled)

        component.editDelayThreshold("30")
        assertEquals(ThresholdValidation.Valid, component.state.value.delayThresholdValidation)
        component.saveDelayThreshold()
        runCurrent()
        assertEquals(30, settings.state.value.defaultThresholds.delayMinutes)
        assertEquals("30", component.state.value.delayThresholdText)

        component.setEventFlag(MonitorEventKind.DELAY, false)
        runCurrent()
        assertFalse(component.state.value.notifyDelay)
        assertFalse(settings.state.value.defaultThresholds.notifyDelay)
        assertTrue(settings.state.value.defaultThresholds.notifyPlatform)
    }

    @Test fun preferencesSurviveComponentRestart() = runTest {
        val settings = FakeNotificationSettingsRepository()
        val first = component(settings = settings)
        runCurrent()
        first.setNotificationsEnabled(false)
        first.editDelayThreshold("10")
        first.saveDelayThreshold()
        first.setEventFlag(MonitorEventKind.ARRIVAL, false)
        runCurrent()

        val restarted = component(settings = settings)
        runCurrent()
        val state = restarted.state.value
        assertFalse(state.loading)
        assertFalse(state.notificationsEnabled)
        assertEquals("10", state.delayThresholdText)
        assertFalse(state.notifyArrival)
        assertTrue(state.notifyDelay)
    }

    @Test fun thresholdRejectsNonPositiveInputWithoutSaving() = runTest {
        val settings = FakeNotificationSettingsRepository()
        val component = component(settings = settings)
        runCurrent()

        listOf("0", "-3", "abc", "").forEach { invalid ->
            component.editDelayThreshold(invalid)
            assertEquals(ThresholdValidation.Invalid, component.state.value.delayThresholdValidation, invalid)
            component.saveDelayThreshold()
            runCurrent()
            assertEquals(ThresholdValidation.Invalid, component.state.value.delayThresholdValidation, invalid)
        }
        assertTrue(settings.writes.isEmpty())
        assertEquals(15, settings.state.value.defaultThresholds.delayMinutes)

        component.editDelayThreshold("20")
        assertEquals(ThresholdValidation.Valid, component.state.value.delayThresholdValidation)
    }

    @Test fun unsavedValidThresholdDraftIsNotCommittedByEventFlagChange() = runTest {
        val settings = FakeNotificationSettingsRepository()
        val component = component(settings = settings)
        runCurrent()

        component.editDelayThreshold("30")
        component.setEventFlag(MonitorEventKind.DELAY, false)
        runCurrent()

        // Only the flag is persisted; the draft stays unsaved.
        assertEquals(15, settings.state.value.defaultThresholds.delayMinutes)
        assertFalse(settings.state.value.defaultThresholds.notifyDelay)
        assertEquals("30", component.state.value.delayThresholdText)
        assertEquals(ThresholdValidation.Valid, component.state.value.delayThresholdValidation)

        // The draft still commits explicitly afterwards, keeping the flag.
        component.saveDelayThreshold()
        runCurrent()
        assertEquals(30, settings.state.value.defaultThresholds.delayMinutes)
        assertFalse(settings.state.value.defaultThresholds.notifyDelay)
    }

    @Test fun invalidThresholdDraftIsNotResetOrPersistedByEventFlagChange() = runTest {
        val backing = FakeNotificationSettingsRepository()
        val settings = ProductionSemanticsSettingsRepository(backing)
        val setup = component(settings = settings)
        runCurrent()
        setup.editDelayThreshold("30")
        setup.saveDelayThreshold()
        runCurrent()
        assertEquals(30, backing.state.value.defaultThresholds.delayMinutes)

        val component = component(settings = settings)
        runCurrent()
        listOf("0", "abc").forEach { invalid ->
            component.editDelayThreshold(invalid)
            assertEquals(ThresholdValidation.Invalid, component.state.value.delayThresholdValidation, invalid)
            component.setEventFlag(MonitorEventKind.PLATFORM, false)
            runCurrent()
            // No null write (production would coerce it to 15), no reset:
            // the persisted threshold stays 30 and the draft stays visible.
            assertEquals(30, backing.state.value.defaultThresholds.delayMinutes, invalid)
            assertFalse(backing.state.value.defaultThresholds.notifyPlatform, invalid)
            assertEquals(invalid, component.state.value.delayThresholdText, invalid)
            assertEquals(ThresholdValidation.Invalid, component.state.value.delayThresholdValidation, invalid)
        }
    }

    @Test fun eventFlagMutationWithoutPersistedSnapshotNeverWritesTheDraft() = runTest {
        val settings = FailingReadSettingsRepository()
        val component = component(settings = settings)
        runCurrent()
        // Observation failed before any emission, but Settings is interactive.
        assertFalse(component.state.value.loading)

        component.editDelayThreshold("30")
        component.setEventFlag(MonitorEventKind.DELAY, false)
        runCurrent()

        // No persisted state exists to modify: nothing is written, the draft
        // is untouched, and the failure stays retryable.
        assertTrue(settings.writes.isEmpty())
        assertEquals("30", component.state.value.delayThresholdText)
        assertEquals(ThresholdValidation.Valid, component.state.value.delayThresholdValidation)
        val failed = assertIs<FailedSettingsSave.EventFlag>(component.state.value.failedSave)
        assertEquals(MonitorEventKind.DELAY, failed.kind)
        assertFalse(failed.enabled)
    }

    @Test fun thresholdSaveWithoutPersistedSnapshotDoesNotOverwriteUnknownFlags() = runTest {
        val settings = FlakyReadSettingsRepository(
            persisted = NotificationSettings(
                defaultThresholds = MonitorThresholds(delayMinutes = 15, notifyArrival = false),
            ),
            failReads = true,
        )
        val component = component(settings = settings)
        runCurrent()
        // Initial observation failed before any emission: interactive without a snapshot.
        assertFalse(component.state.value.loading)

        component.editDelayThreshold("30")
        component.saveDelayThreshold()
        runCurrent()

        // No authoritative or committed snapshot exists: no write, retryable
        // failure, draft stays visible.
        assertTrue(settings.writes.isEmpty())
        val failed = assertIs<FailedSettingsSave.Threshold>(component.state.value.failedSave)
        assertEquals(30, failed.minutes)
        assertEquals("30", component.state.value.delayThresholdText)
        assertEquals(ThresholdValidation.Valid, component.state.value.delayThresholdValidation)
        assertEquals(15, settings.persisted.defaultThresholds.delayMinutes)

        // Once authoritative settings become readable, retry changes only the
        // delay while preserving every persisted event flag.
        settings.failReads = false
        component.retry()
        runCurrent()

        assertEquals(1, settings.writes.size)
        assertEquals(30, settings.persisted.defaultThresholds.delayMinutes)
        assertEquals(
            MonitorThresholds(
                delayMinutes = 30,
                notifyDelay = true,
                notifyPlatform = true,
                notifyCancellation = true,
                notifyDeparture = true,
                notifyArrival = false,
            ),
            settings.persisted.defaultThresholds,
        )
        assertFalse(settings.persisted.defaultThresholds.notifyArrival)
        assertNull(component.state.value.failedSave)
        assertEquals("30", component.state.value.delayThresholdText)
        assertEquals(ThresholdValidation.Valid, component.state.value.delayThresholdValidation)
    }

    @Test fun failedThresholdSaveDraftSurvivesUnrelatedSuccessfulEventFlagMutation() = runTest {
        val settings = FakeNotificationSettingsRepository()
        val component = component(settings = settings)
        runCurrent()

        component.editDelayThreshold("30")
        settings.failure = IllegalStateException("disk")
        component.saveDelayThreshold()
        runCurrent()
        assertIs<FailedSettingsSave.Threshold>(component.state.value.failedSave)
        assertEquals(15, settings.state.value.defaultThresholds.delayMinutes)

        // An unrelated flag mutation succeeds against the still-persisted 15
        // without committing or erasing the failed 30 draft.
        settings.failure = null
        component.setEventFlag(MonitorEventKind.PLATFORM, false)
        runCurrent()
        assertEquals(15, settings.state.value.defaultThresholds.delayMinutes)
        assertFalse(settings.state.value.defaultThresholds.notifyPlatform)
        assertEquals("30", component.state.value.delayThresholdText)
        assertEquals(ThresholdValidation.Valid, component.state.value.delayThresholdValidation)

        // The draft remains explicitly saveable afterwards, keeping the flag.
        component.saveDelayThreshold()
        runCurrent()
        assertEquals(30, settings.state.value.defaultThresholds.delayMinutes)
        assertFalse(settings.state.value.defaultThresholds.notifyPlatform)
    }

    @Test fun backToBackFlagMutationsDoNotLoseEachOther() = runTest {
        val backing = FakeNotificationSettingsRepository()
        val settings = GatedWriteSettingsRepository(backing)
        val component = component(settings = settings)
        runCurrent()

        // Park the first write inside the mutation gate while holding the
        // serialized section, then issue the second mutation.
        settings.writeGate = CompletableDeferred()
        component.setEventFlag(MonitorEventKind.DELAY, false)
        runCurrent()
        component.setEventFlag(MonitorEventKind.PLATFORM, false)
        runCurrent()
        settings.writeGate?.complete(Unit)
        runCurrent()

        // The second mutation resolved from the first one's persisted result,
        // so both flags land and the threshold is untouched.
        val persisted = backing.state.value.defaultThresholds
        assertFalse(persisted.notifyDelay)
        assertFalse(persisted.notifyPlatform)
        assertEquals(15, persisted.delayMinutes)
        assertTrue(persisted.notifyCancellation)
    }

    @Test fun deniedPermissionIsPresentedWithoutChangingSavedPreference() = runTest {
        val settings = FakeNotificationSettingsRepository()
        val component = component(
            settings = settings,
            permission = FakeNotificationPermission(EffectiveNotificationPermission.DENIED),
        )
        runCurrent()

        assertEquals(EffectiveNotificationPermission.DENIED, component.state.value.permission)
        // Saved preference and OS state stay separate: the switch still saves.
        component.setNotificationsEnabled(false)
        runCurrent()
        assertFalse(settings.state.value.notificationsEnabled)
        assertEquals(EffectiveNotificationPermission.DENIED, component.state.value.permission)
    }

    @Test fun delayedPermissionResultCannotRestoreThePreloadSettingsSnapshot() = runTest {
        val persisted = NotificationSettings(
            notificationsEnabled = false,
            defaultThresholds = MonitorThresholds(
                delayMinutes = 42,
                notifyDelay = false,
                notifyPlatform = true,
                notifyCancellation = false,
                notifyDeparture = true,
                notifyArrival = false,
            ),
        )
        val settings = GatedInitialSettingsRepository(persisted)
        val permission = GatedNotificationPermission()
        val component = component(settings = settings, permission = permission)

        runCurrent()
        assertTrue(component.state.value.loading)

        settings.release.complete(Unit)
        runCurrent()
        assertFalse(component.state.value.loading)
        assertFalse(component.state.value.notificationsEnabled)
        assertEquals("42", component.state.value.delayThresholdText)
        assertFalse(component.state.value.notifyDelay)
        assertFalse(component.state.value.notifyCancellation)
        assertFalse(component.state.value.notifyArrival)

        permission.release.complete(EffectiveNotificationPermission.DENIED)
        runCurrent()

        // A platform answer may update only permission. It must not write the
        // stale SettingsState captured before the persisted emission arrived.
        val afterPermission = component.state.value
        assertFalse(afterPermission.loading)
        assertEquals(EffectiveNotificationPermission.DENIED, afterPermission.permission)
        assertFalse(afterPermission.notificationsEnabled)
        assertEquals("42", afterPermission.delayThresholdText)
        assertFalse(afterPermission.notifyDelay)
        assertFalse(afterPermission.notifyCancellation)
        assertFalse(afterPermission.notifyArrival)
    }

    @Test fun mutationFailureIsRetryable() = runTest {
        val settings = FakeNotificationSettingsRepository()
        val component = component(settings = settings)
        runCurrent()

        settings.failure = IllegalStateException("disk")
        component.setNotificationsEnabled(false)
        runCurrent()
        assertIs<FailedSettingsSave.Global>(component.state.value.failedSave)
        assertTrue(settings.state.value.notificationsEnabled)
        assertFalse(component.state.value.saving)

        settings.failure = null
        component.retry()
        runCurrent()
        assertNull(component.state.value.failedSave)
        assertFalse(settings.state.value.notificationsEnabled)
    }

    @Test fun destroyedComponentStopsObserving() = runTest {
        val lifecycle = LifecycleRegistry()
        val settings = FakeNotificationSettingsRepository()
        val component = component(lifecycle = lifecycle, settings = settings)
        lifecycle.resume()
        runCurrent()
        assertFalse(component.state.value.loading)

        lifecycle.destroy()
        settings.state.value = NotificationSettings(
            notificationsEnabled = false,
            defaultThresholds = MonitorThresholds(delayMinutes = 60),
        )
        runCurrent()
        // No collection after destroy: the snapshot is frozen.
        assertTrue(component.state.value.notificationsEnabled)
        assertEquals("15", component.state.value.delayThresholdText)
    }

    @Test fun viewingSettingsNeverEnablesStrikeNotifications() = runTest {
        val strikes = CountingStrikeRepository()
        val settings = FakeNotificationSettingsRepository()
        component(settings = settings, strikes = strikes.inner)
        runCurrent()
        runCurrent()

        assertEquals(0, strikes.setCalls)
        assertFalse(strikes.inner.notificationsEnabled.value)
        // Toggling the installation switch never touches the strike opt-in.
        component(settings = settings, strikes = strikes.proxied).apply {
            runCurrent()
            setNotificationsEnabled(false)
            runCurrent()
        }
        assertEquals(0, strikes.setCalls)
        assertFalse(strikes.inner.notificationsEnabled.value)
    }

    /**
     * Test double replicating the production coercion rule of
     * `SqlDelightNotificationSettingsRepository`: a null delay is stored as
     * the installation default. A component write of null would therefore
     * surface here as 15 instead of staying hidden the way a verbatim fake
     * would allow.
     */
    private class ProductionSemanticsSettingsRepository(
        private val delegate: FakeNotificationSettingsRepository = FakeNotificationSettingsRepository(),
    ) : it.danielebufarini.trenify.core.domain.NotificationSettingsRepository by delegate {
        override suspend fun setDefaultThresholds(thresholds: MonitorThresholds) {
            delegate.setDefaultThresholds(
                if (thresholds.delayMinutes == null) thresholds.copy(delayMinutes = 15) else thresholds,
            )
        }
    }

    /**
     * Settings source whose observation always fails before any emission,
     * leaving the component with no persisted snapshot while interactive.
     */
    private class FailingReadSettingsRepository : NotificationSettingsRepository {
        val writes = mutableListOf<MonitorThresholds>()

        override fun observe(): Flow<NotificationSettings> = flow {
            throw IllegalStateException("settings unavailable")
        }

        override suspend fun setNotificationsEnabled(enabled: Boolean) = Unit

        override suspend fun setDefaultThresholds(thresholds: MonitorThresholds) {
            writes += thresholds
        }
    }

    private class GatedInitialSettingsRepository(
        private var persisted: NotificationSettings,
    ) : NotificationSettingsRepository {
        val release = CompletableDeferred<Unit>()

        override fun observe(): Flow<NotificationSettings> = flow {
            release.await()
            emit(persisted)
        }

        override suspend fun setNotificationsEnabled(enabled: Boolean) {
            persisted = persisted.copy(notificationsEnabled = enabled)
        }

        override suspend fun setDefaultThresholds(thresholds: MonitorThresholds) {
            persisted = persisted.copy(defaultThresholds = thresholds)
        }
    }

    private class GatedNotificationPermission : NotificationPermission {
        val release = CompletableDeferred<EffectiveNotificationPermission>()

        override suspend fun isGranted(): Boolean = release.await() == EffectiveNotificationPermission.GRANTED

        override suspend fun request(): Boolean = isGranted()

        override suspend fun effective(): EffectiveNotificationPermission = release.await()
    }

    /**
     * Settings source whose authoritative read fails until explicitly
     * released, while the conceptual persisted row (with a non-default flag)
     * stays observable through writes. Proves a threshold save never invents
     * unknown flags and only writes once a persisted base is resolvable.
     */
    private class FlakyReadSettingsRepository(
        var persisted: NotificationSettings = NotificationSettings(
            defaultThresholds = MonitorThresholds(delayMinutes = 15, notifyArrival = false),
        ),
        var failReads: Boolean = true,
    ) : NotificationSettingsRepository {
        val writes = mutableListOf<MonitorThresholds>()

        override fun observe(): Flow<NotificationSettings> = flow {
            if (failReads) throw IllegalStateException("settings unavailable")
            else emit(persisted)
        }

        override suspend fun setNotificationsEnabled(enabled: Boolean) {
            persisted = persisted.copy(notificationsEnabled = enabled)
        }

        override suspend fun setDefaultThresholds(thresholds: MonitorThresholds) {
            writes += thresholds
            persisted = persisted.copy(defaultThresholds = thresholds)
        }
    }

    /**
     * Settings source with a gate inside the write path, proving serialized
     * mutations resolve from each predecessor's persisted result instead of
     * a shared stale snapshot.
     */
    private class GatedWriteSettingsRepository(
        private val delegate: FakeNotificationSettingsRepository = FakeNotificationSettingsRepository(),
    ) : NotificationSettingsRepository by delegate {
        var writeGate: CompletableDeferred<Unit>? = null

        override suspend fun setDefaultThresholds(thresholds: MonitorThresholds) {
            writeGate?.await()
            delegate.setDefaultThresholds(thresholds)
        }
    }

    private class CountingStrikeRepository {
        val inner = FakeStrikeRepository()
        var setCalls = 0
        val proxied: it.danielebufarini.trenify.core.domain.StrikeNotificationRepository =
            object : it.danielebufarini.trenify.core.domain.StrikeNotificationRepository by inner {
                override suspend fun setNotificationsEnabled(enabled: Boolean) {
                    setCalls++
                    inner.setNotificationsEnabled(enabled)
                }
            }
    }
}
