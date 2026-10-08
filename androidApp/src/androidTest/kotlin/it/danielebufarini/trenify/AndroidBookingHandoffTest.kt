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
import it.danielebufarini.trenify.core.testing.bookingContractTestChannel
import it.danielebufarini.trenify.core.testing.bookingContractTestPolicy
import it.danielebufarini.trenify.core.testing.testJourney
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AndroidBookingHandoffTest {
    @Test fun homepageFallbacksNeverReachTheAndroidViewIntent() = runTest {
        withLauncher { context, launcher ->
            val handoff = OpenBookingLink(openUrl = launcher::open)
            listOf("Trenitalia", "Trenitalia Tper", "Italo", "Trenord", "Unknown Operator").forEach { operator ->
                val journey = testJourney.copy(legs = testJourney.legs.map { it.copy(operator = Operator(operator)) })
                assertFalse(handoff.canBook(journey), operator)
                assertIs<BookingHandoffResult.Unavailable>(handoff(journey), operator)
            }
            assertTrue(context.intents.isEmpty())
        }
    }

    @Test fun eligibleContractTargetReachesTheAndroidViewIntentUnchanged() = runTest {
        // Contract double exercises the real policy and OS boundary; no live site claim.
        withLauncher { context, launcher ->
            val handoff = OpenBookingLink(bookingContractTestPolicy(), launcher::open)
            assertTrue(handoff.canBook(testJourney))
            val result = assertIs<BookingHandoffResult.Opened>(handoff(testJourney))
            val intent = context.intents.single()
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("https://www.trenitalia.com/contract-test?from=Roma%20Termini&to=Milano%20Centrale", intent.dataString)
            assertEquals(result.target.url, intent.dataString)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
    }

    @Test fun rejectedBookingTargetNeverReachesAndroid() = runTest {
        withLauncher { context, launcher ->
            val policy = BookingLinkPolicy(operatorChannels = mapOf("trenitalia" to bookingContractTestChannel("https://example.com/search")))
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
            assertIs<BookingHandoffResult.Failed>(OpenBookingLink(bookingContractTestPolicy(), launcher::open)(testJourney))
        }
    }

    @Test fun deniedActivityLaunchReturnsHandoffFailure() = runTest {
        withLauncher { context, launcher ->
            context.launchFailure = SecurityException("Activity launch denied")
            assertIs<BookingHandoffResult.Failed>(OpenBookingLink(bookingContractTestPolicy(), launcher::open)(testJourney))
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
