package it.danielebufarini.trenify

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import it.danielebufarini.trenify.core.domain.BookingHandoffResult
import it.danielebufarini.trenify.core.domain.BookingLinkPolicy
import it.danielebufarini.trenify.core.domain.OpenBookingLink
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.platform.ExternalUrlLauncher
import it.danielebufarini.trenify.core.platform.createPlatformServices
import it.danielebufarini.trenify.core.testing.testJourney
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AndroidBookingHandoffTest {
    @Test fun officialOperatorsReachTheAndroidViewIntent() = runTest {
        withLauncher { context, launcher ->
            val handoff = OpenBookingLink(openUrl = launcher::open)
            val channels = mapOf(
                "Trenitalia" to "https://www.trenitalia.com",
                "Trenitalia Tper" to "https://www.trenitalia.com",
                "Italo" to "https://www.italotreno.com",
                "Trenord" to "https://www.trenord.it",
            )
            channels.forEach { (operator, url) ->
                val journey = testJourney.copy(legs = testJourney.legs.map { it.copy(operator = Operator(operator)) })
                assertIs<BookingHandoffResult.Opened>(handoff(journey))
                val intent = context.intents.last()
                assertEquals(Intent.ACTION_VIEW, intent.action)
                assertEquals(url, intent.dataString)
                assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            }
            assertEquals(channels.size, context.intents.size)
        }
    }

    @Test fun rejectedBookingTargetNeverReachesAndroid() = runTest {
        withLauncher { context, launcher ->
            val policy = BookingLinkPolicy(operatorChannels = mapOf("trenitalia" to "https://example.com"))
            val handoff = OpenBookingLink(policy, launcher::open)
            assertIs<BookingHandoffResult.Unavailable>(handoff(testJourney))
            assertTrue(context.intents.isEmpty())
        }
    }

    @Test fun nonHttpsUrlNeverStartsAnActivity() = runTest {
        withLauncher { context, launcher ->
            assertFalse(launcher.open("http://www.trenitalia.com"))
            assertFalse(launcher.open("not a url"))
            assertTrue(context.intents.isEmpty())
        }
    }

    @Test fun missingBrowserReturnsFailure() = runTest {
        withLauncher { context, launcher ->
            context.launchFailure = ActivityNotFoundException("No browser")
            assertFalse(launcher.open("https://www.trenitalia.com"))
            assertIs<BookingHandoffResult.Failed>(OpenBookingLink(openUrl = launcher::open)(testJourney))
        }
    }

    @Test fun deniedActivityLaunchReturnsHandoffFailure() = runTest {
        withLauncher { context, launcher ->
            context.launchFailure = SecurityException("Activity launch denied")
            assertIs<BookingHandoffResult.Failed>(OpenBookingLink(openUrl = launcher::open)(testJourney))
        }
    }

    private suspend fun withLauncher(block: suspend (RecordingContext, ExternalUrlLauncher) -> Unit) {
        val context = RecordingContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val services = createPlatformServices(context)
        try {
            block(context, services.externalUrlLauncher)
        } finally {
            services.connectivity.close()
        }
    }

    // Exercise real Android URI/Intent handling and production composition, stopping
    // at the OS boundary so tests never open an external browser or require a network.
    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val intents = mutableListOf<Intent>()
        var launchFailure: RuntimeException? = null

        override fun getApplicationContext(): Context = this

        override fun startActivity(intent: Intent) {
            intents += intent
            launchFailure?.let { throw it }
        }
    }
}
