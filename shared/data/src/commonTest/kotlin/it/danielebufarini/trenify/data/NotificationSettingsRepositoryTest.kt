package it.danielebufarini.trenify.data

import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.NotificationSettings
import it.danielebufarini.trenify.database.TrenifyDatabase
import it.danielebufarini.trenify.core.testing.MutableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * T7.7 installation preference persistence: defaults, reactive saves and
 * restart survival. Strike opt-in is covered separately and never duplicated
 * here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSettingsRepositoryTest {
    private suspend fun kotlinx.coroutines.test.TestScope.withRepository(
        block: suspend kotlinx.coroutines.test.TestScope.(TrenifyDatabase, SqlDelightNotificationSettingsRepository) -> Unit,
    ) {
        val driver = createRepositoryTestDriver()
        val database = TrenifyDatabase(driver)
        val repository = SqlDelightNotificationSettingsRepository(database, StandardTestDispatcher(testScheduler))
        try {
            block(database, repository)
        } finally {
            driver.close()
        }
    }

    @Test fun absentKeysReadAsDocumentedDefaults() = runTest {
        withRepository { _, repository ->
            assertEquals(NotificationSettings(), repository.observe().first())
        }
    }

    @Test fun savesAreReactive() = runTest {
        withRepository { _, repository ->
            val emissions = mutableListOf<NotificationSettings>()
            val observation = backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
                repository.observe().take(3).toList(emissions)
            }
            runCurrent()
            repository.setNotificationsEnabled(false)
            runCurrent()
            repository.setDefaultThresholds(MonitorThresholds(delayMinutes = 30, notifyArrival = false))
            runCurrent()
            assertEquals(
                listOf(
                    NotificationSettings(),
                    NotificationSettings(notificationsEnabled = false),
                    NotificationSettings(
                        notificationsEnabled = false,
                        defaultThresholds = MonitorThresholds(delayMinutes = 30, notifyArrival = false),
                    ),
                ),
                emissions,
            )
            observation.cancel()
        }
    }

    @Test fun preferencesSurviveRestart() = runTest {
        withRepository { database, repository ->
            repository.setNotificationsEnabled(false)
            repository.setDefaultThresholds(
                MonitorThresholds(
                    delayMinutes = 10,
                    notifyDelay = false,
                    notifyPlatform = false,
                    notifyCancellation = false,
                    notifyDeparture = false,
                    notifyArrival = false,
                ),
            )
            runCurrent()

            val restarted = SqlDelightNotificationSettingsRepository(database, StandardTestDispatcher(testScheduler))
            assertEquals(
                NotificationSettings(
                    notificationsEnabled = false,
                    defaultThresholds = MonitorThresholds(
                        delayMinutes = 10,
                        notifyDelay = false,
                        notifyPlatform = false,
                        notifyCancellation = false,
                        notifyDeparture = false,
                        notifyArrival = false,
                    ),
                ),
                restarted.observe().first(),
            )
        }
    }

    @Test fun corruptValuesDegradeToDefaults() = runTest {
        withRepository { database, repository ->
            database.appMetadataQueries.insertOrReplace("notifications.enabled", "maybe")
            database.appMetadataQueries.insertOrReplace("notifications.default.delay_minutes", "-4")
            runCurrent()
            val settings = repository.observe().first()
            assertTrue(settings.notificationsEnabled)
            assertEquals(15, settings.defaultThresholds.delayMinutes)
            assertTrue(settings.defaultThresholds.notifyDelay)
        }
    }

    @Test fun installationDefaultsNeverTouchMonitorsOrStrikeOptIn() = runTest {
        withRepository { database, repository ->
            val clock = MutableClock()
            val monitors = SqlDelightMonitoringRepository(database, clock, StandardTestDispatcher(testScheduler))
            val monitor = monitors.createMonitor(
                it.danielebufarini.trenify.core.testing.testRunId,
                expiresAt = clock.now() + 1.hours,
            )
            repository.setNotificationsEnabled(false)
            repository.setDefaultThresholds(MonitorThresholds(delayMinutes = 60))
            runCurrent()
            val untouched = monitors.observeMonitor(it.danielebufarini.trenify.core.testing.testRunId).first()
            assertEquals(monitor, untouched)
            assertTrue(untouched?.thresholds?.delayMinutes == 15)
            assertFalse(database.realtimeQueries.strikeNotificationsEnabled().executeAsOneOrNull() == 1L)
        }
    }
}
