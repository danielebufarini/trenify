package it.danielebufarini.trenify.core.ui

import com.arkivanov.essenty.lifecycle.Lifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Local time-observation for display freshness (T7.14-B).
 *
 * A visible component owns at most one of these and it schedules at most
 * one delayed re-evaluation at a time — never one timer per card/row and
 * never a network poll. The single pending deadline always comes from the
 * currently displayed data ([DataFreshness.staleTransitionAt]); when it
 * passes, [onTick] re-derives display freshness from the injected [clock]
 * with zero provider calls. A deadline that is already past (e.g. a
 * persisted snapshot older than its TTL when observation starts) is
 * applied once and then left alone until [poke] — it never busy-loops.
 * Resume re-evaluates immediately so elapsed age after background/suspend
 * is picked up without a successful refresh, and pausing or destroying the
 * component cancels the scheduling.
 *
 * The watcher never mutates repository state: it only asks the component to
 * refresh its already-held display copy. Late-arriving fresh data restarts
 * the boundary tracking through [poke], which components call whenever they
 * publish a new repository observation.
 */
class FreshnessWatcher(
    private val lifecycle: Lifecycle,
    private val scope: CoroutineScope,
    private val clock: Clock,
) {
    private var loop: Job? = null
    private var next: () -> Instant? = { null }
    private var apply: () -> Unit = {}
    private val signal = MutableStateFlow(0L)

    init {
        lifecycle.subscribe(object : Lifecycle.Callbacks {
            override fun onResume() {
                start()
            }

            override fun onPause() {
                stop()
            }
        })
    }

    /**
     * Starts (or replaces) observation. [nextDeadline] derives the next
     * freshness boundary from the currently displayed state; [onTick]
     * re-derives display freshness from [clock] and runs now (when resumed),
     * on resume and at every boundary.
     */
    fun observe(nextDeadline: () -> Instant?, onTick: () -> Unit) {
        next = nextDeadline
        apply = onTick
        poke()
    }

    /**
     * Restarts boundary tracking after new display data was published (e.g.
     * a fresh repository emission after the previous loop already exited on
     * an all-stale snapshot). No-op while paused: resume re-evaluates.
     */
    fun poke() {
        if (lifecycle.state != Lifecycle.State.RESUMED) return
        if (loop == null) start() else signal.value++
    }

    /** Immediate display recomputation from [clock]; safe to call any time. */
    fun recompute() {
        apply()
    }

    private fun start() {
        stop()
        loop = scope.launch {
            signal.collectLatest {
                apply()
                while (currentCoroutineContext().isActive) {
                    val at = next() ?: break
                    val wait = at - clock.now()
                    if (wait <= Duration.ZERO) {
                        // Boundary already passed (e.g. a persisted
                        // snapshot older than its TTL when observation
                        // starts): the apply() above already re-derived
                        // the display copy and re-applying cannot advance
                        // it further — stop until new data arrives via
                        // poke() instead of busy-looping the Main thread.
                        break
                    }
                    delay(wait)
                    apply()
                }
            }
        }
    }

    private fun stop() {
        loop?.cancel()
        loop = null
    }
}
