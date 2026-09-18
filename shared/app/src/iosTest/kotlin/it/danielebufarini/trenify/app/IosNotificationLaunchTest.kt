package it.danielebufarini.trenify.app

import it.danielebufarini.trenify.core.platform.NotificationDestination
import it.danielebufarini.trenify.core.platform.NotificationTap
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Real delegate-installation ownership (T7.13-G corrective pass).
 *
 * Unlike [IosNotificationBoundaryTest] — which drives a directly constructed
 * [IosNotificationTapRouter] — every test here goes through the delegate
 * instance actually installed by [IosNotificationLaunch.install], proving:
 * - the installation owner strongly retains its delegate for its entire
 *   active lifetime (the center only holds it weakly);
 * - uninstall/close is safe, idempotent, and never clears a delegate owned
 *   by another instance;
 * - responses arriving through the installed delegate reach shared routing
 *   (warm) or the deterministic cold buffer (exactly once);
 * - malformed input through the installed delegate remains safe.
 *
 * Runner limitation: the real `UNUserNotificationCenter` requires a bundled
 * application process and crashes in the bundle-less `iosSimulatorArm64Test`
 * binary (`bundleProxyForCurrentProcess is nil`), so the suite substitutes a
 * plain recording fake for the center slot through the production seam. The
 * weak nature of the real slot is Apple's documented API contract; what this
 * suite proves deterministically (no GC dependence) is that the owner itself
 * holds the delegate strongly from [IosNotificationLaunch.install] until
 * [IosNotificationLaunch.uninstall] — the original defect (a delegate
 * temporary owned by nobody) reads null on the ownership probe. The two-line
 * real access is compile-verified and exercised by the Xcode app.
 */
class IosNotificationLaunchTest {
    private val train = NotificationDestination.Train("test", "123", "opaque-origin", "2026-09-05")

    /** Recording fake for the center slot (see [IosNotificationCenterAccess]). */
    private class FakeNotificationCenterAccess : IosNotificationCenterAccess {
        var slot: Any? = null
        override fun getDelegate(): Any? = slot
        override fun setDelegate(delegate: Any?) {
            slot = delegate
        }
    }

    private val center = FakeNotificationCenterAccess()

    private fun userInfo(destination: NotificationDestination): Map<Any?, *> =
        NotificationTap.extras(destination).mapKeys { it.key as Any? }

    private fun installedDelegate(): IosNotificationCenterDelegate =
        assertIs<IosNotificationCenterDelegate>(center.getDelegate())

    private fun setup() {
        IosNotificationLaunch.centerAccess = center
        IosNotificationLaunch.detach()
        IosNotificationLaunch.uninstall()
        center.setDelegate(null)
    }

    private fun teardown() {
        IosNotificationLaunch.detach()
        IosNotificationLaunch.uninstall()
        center.setDelegate(null)
        IosNotificationLaunch.centerAccess = RealIosNotificationCenterAccess
    }

    @Test
    fun installStronglyRetainsDelegateForItsActiveLifetime() {
        setup()
        try {
            assertNull(IosNotificationLaunch.retainedDelegateForTest())
            IosNotificationLaunch.install()
            // The only strong owner is the launch object: install returns
            // nothing, and the center slot holds the delegate weakly per
            // Apple's contract — so a readable delegate here proves the
            // owner retains it.
            val owned = assertNotNull(IosNotificationLaunch.retainedDelegateForTest())
            assertIs<IosNotificationCenterDelegate>(owned)
            assertSame(owned, center.getDelegate())
            // Still owned after dropping every test-side reference and
            // churning allocations, for the whole active lifetime.
            var churn = 0
            repeat(10_000) { churn += "$it".length }
            assertTrue(churn > 0)
            assertSame(owned, assertNotNull(IosNotificationLaunch.retainedDelegateForTest()))
            assertSame(owned, center.getDelegate())
            // Reinstalling is idempotent and keeps the same owner.
            IosNotificationLaunch.install()
            assertSame(owned, assertNotNull(IosNotificationLaunch.retainedDelegateForTest()))
        } finally {
            teardown()
        }
        assertNull(IosNotificationLaunch.retainedDelegateForTest())
        assertNull(center.getDelegate())
    }

    @Test
    fun installedDelegateForwardsWarmResponseToSharedRouting() {
        setup()
        try {
            IosNotificationLaunch.install()
            val delivered = mutableListOf<NotificationDestination>()
            var completions = 0
            IosNotificationLaunch.attach(deliver = { delivered.add(it) }, shouldPresent = { true })
            val forwarded = installedDelegate().handleResponse(userInfo(train)) { completions++ }
            assertTrue(forwarded)
            assertEquals(1, completions)
            assertEquals<List<NotificationDestination>>(listOf(train), delivered)
            assertNull(IosNotificationLaunch.pendingForTest())
        } finally {
            teardown()
        }
    }

    @Test
    fun installedDelegateKeepsMalformedInputSafe() {
        setup()
        try {
            IosNotificationLaunch.install()
            val delivered = mutableListOf<NotificationDestination>()
            var completions = 0
            IosNotificationLaunch.attach(deliver = { delivered.add(it) }, shouldPresent = { true })
            val delegate = installedDelegate()
            assertFalse(delegate.handleResponse(null) { completions++ })
            assertFalse(delegate.handleResponse(emptyMap<Any?, Any?>()) { completions++ })
            assertFalse(delegate.handleResponse(mapOf("title" to "Train 123")) { completions++ })
            assertEquals(3, completions)
            assertTrue(delivered.isEmpty())
            assertNull(IosNotificationLaunch.pendingForTest())
        } finally {
            teardown()
        }
    }

    @Test
    fun coldResponseBuffersDeterministicallyAndDeliversExactlyOnce() {
        setup()
        try {
            IosNotificationLaunch.install()
            var completions = 0
            // No graph attached: the cold response buffers instead of
            // being lost.
            assertTrue(installedDelegate().handleResponse(userInfo(train)) { completions++ })
            assertEquals(1, completions)
            assertEquals(train, IosNotificationLaunch.pendingForTest())
            // Duplicate cold delivery coalesces in the buffer.
            assertTrue(installedDelegate().handleResponse(userInfo(train)) { completions++ })
            // Attaching the graph drains the buffer exactly once.
            val delivered = mutableListOf<NotificationDestination>()
            IosNotificationLaunch.attach(deliver = { delivered.add(it) }, shouldPresent = { true })
            assertEquals<List<NotificationDestination>>(listOf(train), delivered)
            assertNull(IosNotificationLaunch.pendingForTest())
            // A later attach (graph recreation) never redelivers.
            val second = mutableListOf<NotificationDestination>()
            IosNotificationLaunch.attach(deliver = { second.add(it) }, shouldPresent = { true })
            assertTrue(second.isEmpty())
            // Warm responses now route immediately through the currently
            // attached graph (the second attach replaced the first).
            assertTrue(installedDelegate().handleResponse(userInfo(train)) { completions++ })
            assertEquals<List<NotificationDestination>>(listOf(train), delivered)
            assertEquals<List<NotificationDestination>>(listOf(train), second)
        } finally {
            teardown()
        }
    }

    @Test
    fun uninstallNeverClearsADelegateOwnedByAnotherInstance() {
        setup()
        try {
            IosNotificationLaunch.install()
            val owned = installedDelegate()
            assertNotNull(owned)
            // A foreign owner replaces the center delegate (e.g. a test
            // double or a future second integration).
            val foreignRouter = IosNotificationTapRouter(deliver = {}, eligible = { true })
            try {
                val foreign = IosNotificationCenterDelegate(foreignRouter)
                center.setDelegate(foreign)
                // Closing our integration must not clear the foreign delegate.
                IosNotificationLaunch.uninstall()
                assertSame(foreign, assertNotNull(center.getDelegate()))
                // Uninstalling again (nothing owned) stays safe.
                IosNotificationLaunch.uninstall()
                assertSame(foreign, assertNotNull(center.getDelegate()))
                center.setDelegate(null)
            } finally {
                foreignRouter.close()
            }
            assertNull(center.getDelegate())
        } finally {
            teardown()
        }
    }

    @Test
    fun foregroundEligibilityThroughInstalledDelegateFollowsAttachedPolicy() = runTest {
        setup()
        try {
            IosNotificationLaunch.install()
            val delegate = installedDelegate()
            IosNotificationLaunch.attach(deliver = {}, shouldPresent = { false })
            val suppressed = kotlinx.coroutines.CompletableDeferred<ULong>()
            delegate.handleForeground(userInfo(train), suppressed::complete)
            assertEquals(0uL, suppressed.await())
            IosNotificationLaunch.attach(deliver = {}, shouldPresent = { true })
            val presented = kotlinx.coroutines.CompletableDeferred<ULong>()
            delegate.handleForeground(userInfo(train), presented::complete)
            assertTrue(presented.await() != 0uL)
        } finally {
            teardown()
        }
    }
}
