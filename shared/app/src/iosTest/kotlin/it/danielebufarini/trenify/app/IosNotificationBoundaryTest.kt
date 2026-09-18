package it.danielebufarini.trenify.app

import it.danielebufarini.trenify.core.platform.NotificationDestination
import it.danielebufarini.trenify.core.platform.NotificationDestinationCodec
import it.danielebufarini.trenify.core.platform.NotificationTap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * iOS notification boundary (T7.13-G): the Kotlin response adapter extracts
 * only the minimal payload and forwards validated destinations to the same
 * shared routing entry Android uses. Warm responses route immediately;
 * cold responses buffer in the graph handoff until the root exists.
 * Foreground presentation follows the shared eligibility policy.
 *
 * Pure-host Swift behavior (delegate registration mechanics) is covered by
 * the Xcode build; everything decision-bearing is tested here on the
 * simulator binary.
 */
class IosNotificationBoundaryTest {
    private val train = NotificationDestination.Train("test", "123", "opaque-origin", "2026-09-05")
    private val strike = NotificationDestination.Strike("mit-strikes:8479")

    private fun userInfo(destination: NotificationDestination): Map<Any?, *> =
        NotificationTap.extras(destination).mapKeys { it.key as Any? }

    @Test
    fun responseForwardsValidatedTrainDestination() {
        val delivered = mutableListOf<NotificationDestination>()
        val router = IosNotificationTapRouter(deliver = { delivered.add(it) }, eligible = { true })
        try {
            assertTrue(router.onResponse(userInfo(train)))
            assertEquals<List<NotificationDestination>>(listOf(train), delivered)
        } finally {
            router.close()
        }
    }

    @Test
    fun responseForwardsValidatedStrikeDestination() {
        val delivered = mutableListOf<NotificationDestination>()
        val router = IosNotificationTapRouter(deliver = { delivered.add(it) }, eligible = { true })
        try {
            assertTrue(router.onResponse(userInfo(strike)))
            assertEquals<List<NotificationDestination>>(listOf(strike), delivered)
        } finally {
            router.close()
        }
    }

    @Test
    fun malformedResponseIsSafe() {
        val delivered = mutableListOf<NotificationDestination>()
        val router = IosNotificationTapRouter(deliver = { delivered.add(it) }, eligible = { true })
        try {
            assertFalse(router.onResponse(null))
            assertFalse(router.onResponse(emptyMap<Any?, Any?>()))
            assertFalse(router.onResponse(mapOf("title" to "Train 123")))
            val tampered: Map<Any?, *> = NotificationTap.extras(train).mapKeys { entry -> entry.key as Any? }
                .mapValues { entry ->
                    if (entry.key == NotificationDestinationCodec.KEY_NUMBER) "12X" else entry.value as Any?
                }
            assertFalse(router.onResponse(tampered))
            assertTrue(delivered.isEmpty())
        } finally {
            router.close()
        }
    }

    @Test
    fun duplicateResponsesForwardIdempotently() = runTest {
        // The router forwards every validated response; duplicate
        // suppression lives in the shared routing entry point (covered by
        // NotificationRoutingTest), so both deliveries arrive exactly.
        val delivered = mutableListOf<NotificationDestination>()
        val router = IosNotificationTapRouter(deliver = { delivered.add(it) }, eligible = { true })
        try {
            assertTrue(router.onResponse(userInfo(train)))
            assertTrue(router.onResponse(userInfo(train)))
            assertEquals<List<NotificationDestination>>(listOf(train, train), delivered)
        } finally {
            router.close()
        }
    }

    @Test
    fun foregroundPresentsBannerAndSoundWhenEligible() = runTest {
        val router = IosNotificationTapRouter(deliver = {}, eligible = { true })
        try {
            val presented = CompletableDeferred<ULong>()
            router.foregroundOptions(userInfo(train), presented::complete)
            val options = presented.await()
            assertTrue(options != 0uL)
        } finally {
            router.close()
        }
    }

    @Test
    fun foregroundSuppressesWhenPolicyDenies() = runTest {
        val router = IosNotificationTapRouter(deliver = {}, eligible = { false })
        try {
            val presented = CompletableDeferred<ULong>()
            router.foregroundOptions(userInfo(strike), presented::complete)
            assertEquals(0uL, presented.await())
        } finally {
            router.close()
        }
    }

    @Test
    fun foregroundDecisionDoesNotParseDisplayText() = runTest {
        var seen: NotificationDestination? = NotificationDestination.Strike("sentinel")
        val router = IosNotificationTapRouter(deliver = {}, eligible = { destination ->
            seen = destination
            true
        })
        try {
            val presented = CompletableDeferred<ULong>()
            router.foregroundOptions(mapOf("title" to "Railway strike scheduled"), presented::complete)
            presented.await()
            assertEquals(null, seen)
        } finally {
            router.close()
        }
    }
}
