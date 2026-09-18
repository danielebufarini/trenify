package it.danielebufarini.trenify.core.ui

import com.arkivanov.essenty.lifecycle.Lifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Duration
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.RealtimePolicy
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.model.aged
import it.danielebufarini.trenify.core.model.staleTransitionAt
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Shared realtime presentation state (T7.14-A).
 *
 * [failure] preserves the typed [DomainFailure] from the repository instead
 * of collapsing every condition into an undifferentiated flag, so shared UI
 * can distinguish offline-with-cache, temporary service failure, not found
 * and the remaining FS §23 conditions. [failed] remains as the
 * undifferentiated convenience read.
 */
data class RealtimeState<T>(
    val data: T? = null,
    val loading: Boolean = false,
    val failure: DomainFailure? = null,
    val stale: Boolean = false,
    val freshness: DataFreshness? = null,
) {
    val failed: Boolean get() = failure != null
}

/**
 * Display TTL mirroring the repository classification (T7.14-B): terminal
 * trains keep the completed-train TTL, everything else the train TTL, so
 * elapsed-time aging agrees with repository freshness instead of inventing
 * its own boundary.
 */
fun RealtimePolicy.ttlForTrain(status: TrainStatus?): Duration =
    if (status == TrainStatus.ARRIVED || status == TrainStatus.CANCELLED) completedTrainTtl else trainTtl

/**
 * Re-derives the display copy of one [RealtimeState] from [now] without a
 * fetch: a Fresh value past its TTL becomes Stale, everything else is
 * untouched. Returns the state unchanged when no transition occurred.
 */
fun <T> RealtimeState<T>.agedDisplay(now: Instant, ttl: Duration): RealtimeState<T> {
    val base = freshness ?: return this
    val aged = base.aged(now, ttl)
    return if (aged == base) this else copy(freshness = aged, stale = aged !is DataFreshness.Fresh)
}

fun componentScope(lifecycle: Lifecycle, dispatcher: CoroutineDispatcher): CoroutineScope =
    CoroutineScope(SupervisorJob() + dispatcher).also { scope ->
        lifecycle.subscribe(object : Lifecycle.Callbacks {
            override fun onDestroy() { scope.cancel() }
        })
    }

// Visibility comes from Decompose; connectivity/foreground eligibility is supplied by the app graph.
class VisibleRefresh(
    lifecycle: Lifecycle,
    private val scope: CoroutineScope,
    available: StateFlow<Boolean>,
    private val interval: Duration,
    private val maxBackoff: Duration,
    private val terminal: () -> Boolean = { false },
    private val refreshAction: suspend (Boolean) -> Boolean,
) {
    private val resumed = MutableStateFlow(lifecycle.state == Lifecycle.State.RESUMED)
    private var manual: Job? = null

    init {
        lifecycle.subscribe(object : Lifecycle.Callbacks {
            override fun onResume() { resumed.value = true }
            override fun onPause() { resumed.value = false; manual?.cancel() }
        })
        scope.launch {
            combine(resumed, available) { visible, connected -> visible && connected }
                .distinctUntilChanged().collectLatest { active ->
                    if (active) {
                        var failures = 0
                        do {
                            val success = refreshAction(false)
                            failures = if (success) 0 else (failures + 1).coerceAtMost(4)
                            if (terminal()) break
                            delay((interval * (1 shl failures)).coerceAtMost(maxBackoff))
                        } while (currentCoroutineContext().isActive)
                    }
                }
        }
    }

    fun refresh(replace: Boolean = false) {
        if (replace) manual?.cancel() else if (manual?.isActive == true) return
        manual = scope.launch { refreshAction(true) }
    }
}
