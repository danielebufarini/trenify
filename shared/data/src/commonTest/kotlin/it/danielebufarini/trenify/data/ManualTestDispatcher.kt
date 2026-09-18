package it.danielebufarini.trenify.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext

/**
 * T7.8 corrective-pass test dispatcher: every dispatched block is parked in
 * a FIFO queue that only the test drains. Unlike sleeps or thread timing,
 * this deterministically separates "repository invocation started" from
 * "dispatched persistence work ran", which is exactly the boundary the
 * delete-wins ticket must respect.
 */
class ManualTestDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.addLast(block)
    }

    fun pending(): Int = queue.size

    fun runNext(): Boolean {
        val next = queue.removeFirstOrNull() ?: return false
        next.run()
        return true
    }

    fun runAll() {
        while (runNext()) {
        }
    }
}
