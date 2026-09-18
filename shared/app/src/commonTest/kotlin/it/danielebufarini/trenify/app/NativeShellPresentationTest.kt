package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.router.stack.items
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import it.danielebufarini.trenify.core.platform.NotificationDestination
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.feature.journey.JourneyTabComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NativeShellPresentationTest {
    @Test fun allPrimaryActionsUseOneRootAndRepeatedSelectionRetainsLiveChild() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), AppComponentFactory(
            repositories, repositories, repositories, repositories, MutableStateFlow(false), StandardTestDispatcher(testScheduler)))
        val shell = createShellPresentation(root)
        try {
            val mainChild = root.stack.value.active.instance
            val main = assertIs<RootComponent.Child.Main>(mainChild).component
            for ((area, tab) in listOf(NativePrimaryArea.Search to MainTab.Home,
                NativePrimaryArea.Monitoring to MainTab.Monitoring, NativePrimaryArea.Saved to MainTab.Favorites,
                NativePrimaryArea.Alerts to MainTab.Alerts)) {
                shell.select(area)
                val child = main.pages.value.items[tab.ordinal].instance
                val token = shell.state.value.active.identity
                val subscriptions = shell.state.value
                repeat(4) { shell.select(area) }
                assertSame(mainChild, root.stack.value.active.instance)
                assertSame(child, main.pages.value.items[tab.ordinal].instance)
                assertEquals(token, shell.state.value.active.identity)
                assertEquals(area, shell.state.value.primaryArea)
                assertEquals(subscriptions, shell.state.value)
                assertEquals(tab.ordinal, main.pages.value.selectedIndex)
            }
        } finally { shell.close(); lifecycle.destroy() }
    }

    @Test fun nestedBackPrefixValidationAndHostInvalidationFollowSharedTree() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), AppComponentFactory(
            repositories, repositories, repositories, repositories, MutableStateFlow(false), StandardTestDispatcher(testScheduler)))
        val shell = createShellPresentation(root)
        try {
            val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
            main.openHistory()
            // T8.5: the journey composer lives in the native Home base, so the
            // Search form is not a separate visual path entry. History appears
            // directly above Home; the shared Journey stack still retains
            // Search below it.
            assertEquals(NativeDestination.Home, shell.state.value.base.destination)
            assertEquals(listOf(NativeDestination.History), shell.state.value.path.map { it.destination })
            val path = shell.state.value.path.map { it.identity }
            shell.requestPath(NativePrimaryArea.Search, listOf(-1), path)
            shell.requestPath(NativePrimaryArea.Saved, emptyList(), path)
            shell.requestPath(NativePrimaryArea.Search, path + 100, path)
            shell.requestPath(NativePrimaryArea.Search, path.dropLast(1), listOf(-1))
            assertEquals(path, shell.state.value.path.map { it.identity })
            shell.requestPath(NativePrimaryArea.Search, path.dropLast(1), path)
            assertEquals(NativeDestination.Home, shell.state.value.active.destination)
            val journey = assertIs<MainComponent.Child.Journey>(main.pages.value.items[MainTab.Journey.ordinal].instance).component
            assertIs<JourneyTabComponent.Child.Search>(journey.stack.value.active.instance)
            shell.back()
            assertEquals(MainTab.Home.ordinal, main.pages.value.selectedIndex)
            assertFalse(shell.state.value.canGoBack)
        } finally { shell.close(); lifecycle.destroy() }
    }

    @Test fun coldAndWarmNotificationReordersKeepTypedProjectionAndIdentity() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), AppComponentFactory(
            repositories, repositories, repositories, repositories, MutableStateFlow(false), StandardTestDispatcher(testScheduler)),
            pendingDestination = NotificationDestination.Strike("strike-a"))
        val shell = createShellPresentation(root)
        try {
            assertEquals(NativePrimaryArea.Alerts, shell.state.value.primaryArea)
            assertEquals(NativeDestination.StrikeDetail, shell.state.value.active.destination)
            val token = shell.state.value.active.identity
            root.onNotificationDestination(NotificationDestination.Strike("strike-b"))
            root.onNotificationDestination(NotificationDestination.Strike("strike-a"))
            root.onNotificationDestination(NotificationDestination.Strike("strike-a"))
            assertEquals(token, shell.state.value.active.identity)
            assertEquals(2, shell.state.value.path.size)
            shell.back()
            val remaining = shell.state.value.active.identity
            assertNotEquals(token, remaining)
            root.onNotificationDestination(NotificationDestination.Train("viaggiatreno", "123", "S1", "2026-09-14"))
            assertEquals(NativePrimaryArea.Alerts, shell.state.value.primaryArea)
            assertEquals(NativeDestination.TrainDetail, shell.state.value.active.destination)
            val size = shell.state.value.path.size
            root.onNotificationDestination(NotificationDestination.Train("viaggiatreno", "123", "S1", "2026-09-14"))
            assertEquals(size, shell.state.value.path.size)
            shell.back()
            assertEquals(remaining, shell.state.value.active.identity)
        } finally { shell.close(); lifecycle.destroy() }
    }

    @Test fun restoredSettingsReturnsToSharedSourceAndClosedShellCancelsObservers() = runTest {
        val repositories = FakeRealtimeRepositories()
        val factory = AppComponentFactory(repositories, repositories, repositories, repositories,
            MutableStateFlow(false), StandardTestDispatcher(testScheduler))
        val lifecycle = LifecycleRegistry()
        val keeper = StateKeeperDispatcher()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle, stateKeeper = keeper), factory)
        val shell = createShellPresentation(root)
        shell.select(NativePrimaryArea.Saved)
        shell.openSettings()
        assertEquals(NativePrimaryArea.Saved, shell.state.value.primaryArea)
        val saved = keeper.save()
        shell.close(); lifecycle.destroy()
        val restoredLifecycle = LifecycleRegistry()
        val restored = DefaultRootComponent(DefaultComponentContext(restoredLifecycle, stateKeeper = StateKeeperDispatcher(saved)), factory)
        val owner = NativeProjectionOwner()
        val projection = NativeShellPresentation(restored, owner)
        val observer = launch(UnconfinedTestDispatcher(testScheduler)) { projection.state.collect {} }
        try {
            assertEquals(NativePrimaryArea.Saved, projection.state.value.primaryArea)
            assertEquals(NativeDestination.Settings, projection.state.value.active.destination)
            projection.back()
            assertEquals(NativeDestination.Saved, projection.state.value.active.destination)
            assertFalse(projection.state.value.canGoBack)
            projection.close()
            runCurrent()
            assertTrue(observer.isCancelled)
            assertEquals(0, owner.probes.subscriptions.value)
            assertEquals(0, owner.probes.collectors.value)
        } finally { projection.close(); restoredLifecycle.destroy() }
    }
}
