package it.danielebufarini.trenify.core.testing

import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.NotificationSettings
import it.danielebufarini.trenify.core.domain.NotificationSettingsRepository
import it.danielebufarini.trenify.core.platform.EffectiveNotificationPermission
import it.danielebufarini.trenify.core.platform.NotificationPermission
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class FakeNotificationSettingsRepository(
    initial: NotificationSettings = NotificationSettings(),
) : NotificationSettingsRepository {
    val state = MutableStateFlow(initial)
    var failure: Throwable? = null
    val writes = mutableListOf<NotificationSettings>()

    override fun observe(): Flow<NotificationSettings> = state

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        failure?.let { throw it }
        state.update { it.copy(notificationsEnabled = enabled) }
        writes += state.value
    }

    override suspend fun setDefaultThresholds(thresholds: MonitorThresholds) {
        failure?.let { throw it }
        state.update { it.copy(defaultThresholds = thresholds) }
        writes += state.value
    }
}

class FakeNotificationPermission(
    var effective: EffectiveNotificationPermission = EffectiveNotificationPermission.GRANTED,
    var requestResult: Boolean = true,
) : NotificationPermission {
    val requests = mutableListOf<Unit>()

    override suspend fun isGranted(): Boolean = effective == EffectiveNotificationPermission.GRANTED

    override suspend fun request(): Boolean {
        requests += Unit
        return requestResult
    }

    override suspend fun effective(): EffectiveNotificationPermission = effective
}
