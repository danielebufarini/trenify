package it.danielebufarini.trenify

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import it.danielebufarini.trenify.core.platform.NotificationDestination
import it.danielebufarini.trenify.core.platform.NotificationDestinationCodec
import it.danielebufarini.trenify.core.platform.NotificationTap
import it.danielebufarini.trenify.app.notificationDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Android notification-tap boundary on a real device/emulator (T7.13-F).
 *
 * Exercises the real Android Intent/Bundle/Uri/PendingIntent behavior that
 * JVM unit tests cannot: the shared-codec payload survives a round trip
 * through framework extras, tap intents for distinct destinations stay
 * distinct (no destination overwrite), immutable/update-safe flags apply,
 * and malformed/missing extras decode to a safe null the Activity ignores.
 * No Activity is launched and no provider is touched, so this never
 * requires a network.
 */
class AndroidNotificationTapTest {
    private val first = NotificationDestination.Train("test", "123", "opaque-a", "2026-09-05")
    private val second = NotificationDestination.Train("test", "456", "opaque-b", "2026-09-05")
    private val strike = NotificationDestination.Strike("mit-strikes:8479")
    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Builds the tap intent exactly like AndroidNotificationPresenter does. */
    private fun tapIntent(destination: NotificationDestination?): Intent =
        Intent(NotificationTap.ACTION).apply {
            setPackage(targetContext.packageName)
            data = Uri.parse(NotificationTap.dataUri(destination))
            destination?.let { NotificationTap.extras(it).forEach { (key, value) -> putExtra(key, value) } }
        }

    @Test fun trainDestinationSurvivesFrameworkExtras() {
        assertEquals(first, tapIntent(first).notificationDestination())
    }

    @Test fun strikeDestinationSurvivesFrameworkExtras() {
        assertEquals(strike, tapIntent(strike).notificationDestination())
    }

    @Test fun malformedOrMissingExtrasAreSafelyIgnored() {
        // No extras at all (plain launcher intent).
        assertNull(Intent().notificationDestination())
        // Wrong action without payload.
        assertNull(Intent(Intent.ACTION_MAIN).notificationDestination())
        // Tampered payload through real framework extras.
        val tampered = tapIntent(first).apply {
            putExtra(NotificationDestinationCodec.KEY_NUMBER, "12X")
        }
        assertNull(tampered.notificationDestination())
        // Half-written payload.
        val partial = Intent().apply {
            putExtra(NotificationDestinationCodec.KEY_KIND, NotificationDestinationCodec.KIND_TRAIN)
            putExtra(NotificationDestinationCodec.KEY_NUMBER, "123")
        }
        assertNull(partial.notificationDestination())
        // Display text alone never routes.
        val textOnly = Intent().apply {
            putExtra("title", "Train 123")
            putExtra("body", "Delay changed from unknown to 5 minutes")
        }
        assertNull(textOnly.notificationDestination())
    }

    @Test fun tapIntentTargetsOnlyThisApp() {
        val intent = tapIntent(first)
        assertEquals(NotificationTap.ACTION, intent.action)
        assertEquals(targetContext.packageName, intent.`package`)
        assertEquals("trenify", intent.data?.scheme)
    }

    @Test fun distinctDestinationsStayDistinctOnDevice() {
        val firstIntent = tapIntent(first)
        val secondIntent = tapIntent(second)
        val strikeIntent = tapIntent(strike)
        // Different data URIs keep the platform from collapsing taps into
        // one PendingIntent and overwriting destinations.
        assertNotEquals(firstIntent.data, secondIntent.data)
        assertNotEquals(firstIntent.data, strikeIntent.data)
        assertNotEquals(
            NotificationTap.requestCode(first, "a"),
            NotificationTap.requestCode(second, "b"),
        )
        // Same destination is stable: re-taps address the same intent.
        assertEquals(firstIntent.data, tapIntent(first).data)
        assertEquals(
            NotificationTap.requestCode(first, "a"),
            NotificationTap.requestCode(first, "a"),
        )
    }

    @Test fun tapExtrasCarryOnlyMinimalRoutingKeys() {
        val extras = tapIntent(first).extras
        assertNotNull(extras)
        val names = extras.keySet()
        assertTrue(names.isNotEmpty())
        assertTrue(names.all { it.startsWith("trenify.dest.") })
    }

    @Test fun contentPendingIntentBuildsImmutableAndUpdateSafe() {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val content = PendingIntent.getActivity(
            targetContext,
            NotificationTap.requestCode(first, "fallback"),
            tapIntent(first),
            flags,
        )
        assertNotNull(content)
        // Same destination re-requests the identical tap; a different one
        // never collides with it.
        val same = PendingIntent.getActivity(
            targetContext,
            NotificationTap.requestCode(first, "fallback"),
            tapIntent(first),
            flags,
        )
        assertEquals(content, same)
        val other = PendingIntent.getActivity(
            targetContext,
            NotificationTap.requestCode(second, "fallback"),
            tapIntent(second),
            flags,
        )
        assertNotEquals(content, other)
        content.cancel()
        same.cancel()
        other.cancel()
    }
}
