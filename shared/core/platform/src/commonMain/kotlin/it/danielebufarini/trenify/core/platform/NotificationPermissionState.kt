package it.danielebufarini.trenify.core.platform

/**
 * Effective ability to deliver a local notification (T7.7).
 *
 * This is separate state from the saved user preference: a denied or revoked
 * OS permission never implies that monitoring itself is disabled, it only
 * gates delivery. Settings observes this alongside the saved preference so
 * the UI can distinguish "user turned notifications off" from "the OS cannot
 * deliver".
 */
enum class EffectiveNotificationPermission {
    GRANTED,
    DENIED,
}

/**
 * Pure Android availability decision, kept free of Android SDK types so it
 * stays deterministic under host tests.
 *
 * On API 31/32 the POST_NOTIFICATIONS runtime permission does not exist, so
 * only the system-level application notification state and the channel state
 * decide. On API 33+ the runtime permission is additionally required.
 * Disabled system-level application notifications or a disabled channel deny
 * delivery on every API level.
 */
fun androidEffectiveNotificationPermission(
    sdkInt: Int,
    runtimeGranted: Boolean,
    appNotificationsEnabled: Boolean,
    channelNotificationsEnabled: Boolean,
): EffectiveNotificationPermission = if (!appNotificationsEnabled || !channelNotificationsEnabled) {
    EffectiveNotificationPermission.DENIED
} else if (sdkInt >= 33 && !runtimeGranted) {
    EffectiveNotificationPermission.DENIED
} else {
    EffectiveNotificationPermission.GRANTED
}

