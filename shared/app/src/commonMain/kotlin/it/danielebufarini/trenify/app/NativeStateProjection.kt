package it.danielebufarini.trenify.app

import com.arkivanov.decompose.value.Value
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** One explicit owner; projections never construct components or launch domain work. */
internal class NativeProjectionOwner(parent: Job? = null, val probes: ProjectionProbes = ProjectionProbes()) {
    val job = SupervisorJob(parent)
    fun child() = NativeProjectionOwner(job, probes)
    fun close() = job.cancel()
    fun <T> bind(state: StateFlow<T>): StateFlow<T> = LifetimeStateFlow(state, this)

    /** One screen snapshot from several existing Values; no per-row jobs or domain work. */
    fun <R> projectSnapshot(vararg values: Value<*>, snapshot: () -> R): StateFlow<R> {
        val state = MutableStateFlow(snapshot())
        values.forEach { value ->
            @Suppress("UNCHECKED_CAST")
            project(value as Value<Any>) { state.value = snapshot(); Unit }
        }
        return bind(state)
    }

    /** Value subscribes synchronously. Publication runs on the component's main-thread discipline.
     * StateFlow retains the latest state (normal equality conflation), never reorders updates.
     */
    fun <T : Any, R> project(value: Value<T>, transform: (T) -> R): StateFlow<R> {
        val state = MutableStateFlow(transform(value.value))
        if (job.isActive) {
            val subscription = value.subscribe { if (job.isActive) state.value = transform(it) }
            probes.subscriptions.update { it + 1 }
            job.invokeOnCompletion {
                subscription.cancel()
                probes.subscriptions.update { it - 1 }
            }
        }
        return bind(state)
    }
}

internal class ProjectionProbes {
    val subscriptions = MutableStateFlow(0)
    val collectors = MutableStateFlow(0)
}

/** Cancelling the owner also terminates collectors already suspended in SKIE's iterator.
 * A plain asStateFlow would detach Value but leave those Swift tasks waiting forever.
 */
@OptIn(InternalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
private class LifetimeStateFlow<T>(
    private val state: StateFlow<T>,
    private val owner: NativeProjectionOwner,
) : StateFlow<T> {
    override val value: T get() = state.value
    override val replayCache: List<T> get() = state.replayCache
    override suspend fun collect(collector: FlowCollector<T>): Nothing = coroutineScope {
        val collectingJob = coroutineContext[Job]!!
        val cancellation = owner.job.invokeOnCompletion { collectingJob.cancel() }
        owner.probes.collectors.update { it + 1 }
        try {
            collectingJob.ensureActive()
            state.collect(collector)
        } finally {
            cancellation.dispose()
            owner.probes.collectors.update { it - 1 }
        }
    }
}
