package it.danielebufarini.trenify.app

import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.lifecycle.stop
import it.danielebufarini.trenify.core.platform.ApplicationState
import it.danielebufarini.trenify.core.platform.NotificationDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Main-thread host operations. The shared lifecycle/root remain authoritative.
 * Observers may be recreated freely; they never own or recreate this session.
 * close is explicit, idempotent, and cancels suspended native collectors too.
 */
class NativeApplicationSession internal constructor(
    internal val root: RootComponent,
    internal val lifecycle: LifecycleRegistry,
    internal val projections: NativeProjectionOwner,
    private val applicationState: (ApplicationState) -> Unit,
    private val deliver: (NotificationDestination) -> Unit,
    private val detach: () -> Unit,
    private val closeGraph: () -> Unit,
    private val checkMainThread: () -> Unit = {},
) {
    private val mutablePhase = MutableStateFlow(NativeSessionPhase.Created)
    val phase: StateFlow<NativeSessionPhase> = projections.bind(mutablePhase)
    val state: StateFlow<NativeRootState> = NativeRootPresentation(root, projections).state
    val shell: NativeShellPresentation = NativeShellPresentation(root, projections)

    fun foreground() {
        checkMainThread()
        if (mutablePhase.value in setOf(NativeSessionPhase.Active, NativeSessionPhase.Closed)) return
        applicationState(ApplicationState.Foreground)
        lifecycle.resume()
        mutablePhase.value = NativeSessionPhase.Active
    }

    fun background() {
        checkMainThread()
        if (mutablePhase.value in setOf(NativeSessionPhase.Inactive, NativeSessionPhase.Closed)) return
        applicationState(ApplicationState.Background)
        lifecycle.stop()
        mutablePhase.value = NativeSessionPhase.Inactive
    }

    fun deliverNotificationDestination(destination: NotificationDestination) {
        checkMainThread()
        if (mutablePhase.value != NativeSessionPhase.Closed) deliver(destination)
    }

    fun back() {
        checkMainThread()
        if (mutablePhase.value != NativeSessionPhase.Closed) root.back()
    }

    fun close() {
        checkMainThread()
        if (mutablePhase.value == NativeSessionPhase.Closed) return
        mutablePhase.value = NativeSessionPhase.Closed
        // Mark closed first so reentrant/double disposal cannot close resources twice.
        projections.close()
        try {
            lifecycle.destroy()
        } finally {
            try { detach() } finally { closeGraph() }
        }
    }
}

enum class NativeSessionPhase { Created, Active, Inactive, Closed }
