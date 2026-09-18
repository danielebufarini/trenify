package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.statekeeper.StateKeeper
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import it.danielebufarini.trenify.core.database.IosDatabaseDriverFactory
import it.danielebufarini.trenify.core.network.createPlatformHttpClientEngine
import it.danielebufarini.trenify.core.platform.createPlatformServices
import platform.Foundation.NSThread

/** Non-rendering entry. Provider/platform composition stays entirely in Kotlin.
 * Call once per application host lifetime on the main thread, after delegate installation.
 */
object IosNativeApplication {
    fun createSession(): NativeApplicationSession {
        check(NSThread.isMainThread) { "Native application sessions are main-thread owned" }
        check(!IosNotificationLaunch.hasAttachedGraph()) { "An iOS application session is already attached" }
        val graph = AppGraph.create(
            sqlDriver = IosDatabaseDriverFactory().create(),
            httpClientEngine = createPlatformHttpClientEngine(),
            platformServices = createPlatformServices(),
        )
        return createIosNativeSession(graph)
    }
}

internal fun createIosNativeSession(graph: AppGraph, stateKeeper: StateKeeper = StateKeeperDispatcher()): NativeApplicationSession {
    val lifecycle = LifecycleRegistry()
    val projections = NativeProjectionOwner()
    try {
        // Preserve cold buffering before root creation and routing after Decompose restores.
        IosNotificationLaunch.attachGraph(graph)
        val root = graph.createRootComponent(DefaultComponentContext(lifecycle, stateKeeper))
        return NativeApplicationSession(root, lifecycle, projections,
            applicationState = graph::onApplicationStateChanged,
            deliver = graph::deliverNotificationDestination,
            detach = { IosNotificationLaunch.detachGraph(graph) },
            closeGraph = graph::close,
            checkMainThread = { check(NSThread.isMainThread) { "Native application sessions are main-thread owned" } },
        )
    } catch (failure: Throwable) {
        projections.close()
        try { lifecycle.destroy() } finally {
            try { IosNotificationLaunch.detachGraph(graph) } finally { graph.close() }
        }
        throw failure
    }
}
