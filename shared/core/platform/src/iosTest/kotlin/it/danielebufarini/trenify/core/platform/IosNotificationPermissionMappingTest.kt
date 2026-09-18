package it.danielebufarini.trenify.core.platform

import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T7.7 iOS effective authorization mapping, semantically aligned with
 * Android: authorized (including provisional and ephemeral grants) allows
 * delivery, while denied, not-determined or missing settings deny it.
 */
class IosNotificationPermissionMappingTest {
    @Test fun grantsAllowDelivery() {
        assertTrue(iosAuthorizationAllowsDelivery(UNAuthorizationStatusAuthorized))
        assertTrue(iosAuthorizationAllowsDelivery(UNAuthorizationStatusProvisional))
        assertTrue(iosAuthorizationAllowsDelivery(UNAuthorizationStatusEphemeral))
    }

    @Test fun deniedNotDeterminedAndMissingSettingsDenyDelivery() {
        assertFalse(iosAuthorizationAllowsDelivery(UNAuthorizationStatusDenied))
        assertFalse(iosAuthorizationAllowsDelivery(UNAuthorizationStatusNotDetermined))
        assertFalse(iosAuthorizationAllowsDelivery(null))
    }
}
