package it.danielebufarini.trenify.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.NotificationSettings
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pre-T8.13 iOS Settings corrective: persisted installation values survive a
 * real file-backed NativeSqliteDriver close/reopen cycle. The unique database
 * name keeps runs independent without relying on an in-memory fixture.
 */
class NotificationSettingsReopenIosTest {
    @Test fun persistedSettingsSurviveNativeDriverRecreationAndFurtherMutation() = runTest {
        val name = "settings-corrective-${NSUUID().UUIDString()}.db"
        val seeded = NotificationSettings(
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

        NativeSqliteDriver(TrenifyDatabase.Schema, name).also { firstDriver ->
            try {
                val repository = SqlDelightNotificationSettingsRepository(TrenifyDatabase(firstDriver))
                repository.setNotificationsEnabled(seeded.notificationsEnabled)
                repository.setDefaultThresholds(seeded.defaultThresholds)
                assertEquals(seeded, repository.observe().first())
            } finally {
                firstDriver.close()
            }
        }

        val mutated = seeded.copy(
            notificationsEnabled = true,
            defaultThresholds = seeded.defaultThresholds.copy(
                delayMinutes = 27,
                notifyDelay = true,
                notifyPlatform = false,
            ),
        )
        NativeSqliteDriver(TrenifyDatabase.Schema, name).also { secondDriver ->
            try {
                val repository = SqlDelightNotificationSettingsRepository(TrenifyDatabase(secondDriver))
                assertEquals(seeded, repository.observe().first())
                repository.setNotificationsEnabled(mutated.notificationsEnabled)
                repository.setDefaultThresholds(mutated.defaultThresholds)
                assertEquals(mutated, repository.observe().first())
            } finally {
                secondDriver.close()
            }
        }

        NativeSqliteDriver(TrenifyDatabase.Schema, name).also { thirdDriver ->
            try {
                val restarted = SqlDelightNotificationSettingsRepository(TrenifyDatabase(thirdDriver))
                assertEquals(mutated, restarted.observe().first())
            } finally {
                thirdDriver.close()
            }
        }
    }
}
