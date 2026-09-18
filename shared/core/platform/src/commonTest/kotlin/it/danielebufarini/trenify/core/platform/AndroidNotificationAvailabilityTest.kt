package it.danielebufarini.trenify.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T7.7 Android effective notification availability: the POST_NOTIFICATIONS
 * runtime permission only exists on API 33+, while system-level application
 * and channel state gate delivery on every API level.
 */
class AndroidNotificationAvailabilityTest {
    @Test fun api31And32IgnoreRuntimeAndHonorSystemAndChannelState() {
        listOf(31, 32).forEach { sdk ->
            assertEquals(
                EffectiveNotificationPermission.GRANTED,
                androidEffectiveNotificationPermission(sdk, runtimeGranted = false, appNotificationsEnabled = true, channelNotificationsEnabled = true),
            )
            assertEquals(
                EffectiveNotificationPermission.DENIED,
                androidEffectiveNotificationPermission(sdk, runtimeGranted = false, appNotificationsEnabled = false, channelNotificationsEnabled = true),
            )
            assertEquals(
                EffectiveNotificationPermission.DENIED,
                androidEffectiveNotificationPermission(sdk, runtimeGranted = false, appNotificationsEnabled = true, channelNotificationsEnabled = false),
            )
            assertEquals(
                EffectiveNotificationPermission.DENIED,
                androidEffectiveNotificationPermission(sdk, runtimeGranted = true, appNotificationsEnabled = false, channelNotificationsEnabled = false),
            )
        }
    }

    @Test fun api33AndLaterRequireRuntimePermissionAsWell() {
        listOf(33, 34, 35).forEach { sdk ->
            assertEquals(
                EffectiveNotificationPermission.GRANTED,
                androidEffectiveNotificationPermission(sdk, runtimeGranted = true, appNotificationsEnabled = true, channelNotificationsEnabled = true),
            )
            assertEquals(
                EffectiveNotificationPermission.DENIED,
                androidEffectiveNotificationPermission(sdk, runtimeGranted = false, appNotificationsEnabled = true, channelNotificationsEnabled = true),
            )
            assertEquals(
                EffectiveNotificationPermission.DENIED,
                androidEffectiveNotificationPermission(sdk, runtimeGranted = true, appNotificationsEnabled = false, channelNotificationsEnabled = true),
            )
            assertEquals(
                EffectiveNotificationPermission.DENIED,
                androidEffectiveNotificationPermission(sdk, runtimeGranted = true, appNotificationsEnabled = true, channelNotificationsEnabled = false),
            )
        }
    }
}
