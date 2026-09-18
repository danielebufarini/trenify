package it.danielebufarini.trenify.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T7.11 final pass: the Android JobService outcome mapping never swallows
 * coroutine cancellation. A JobService is not directly unit-testable, so the
 * shared mapping it uses is pinned here at the orchestration abstraction.
 */
class BackgroundRefreshOutcomeTest {
    @Test fun successMapsToTrue() = runTest {
        var ran = false
        assertTrue(runBackgroundRefreshOutcome { ran = true })
        assertTrue(ran)
    }

    @Test fun operationalFailureMapsToFalse() = runTest {
        assertFalse(runBackgroundRefreshOutcome { throw IllegalStateException("provider down") })
    }

    @Test fun cancellationPropagatesInsteadOfBecomingAResult() = runTest {
        // runCatching would swallow this into a failure result (and the old
        // call site then reported the stopped job as finished); the mapping
        // must rethrow so the stopped job reports no finished outcome.
        assertFailsWith<CancellationException> {
            runBackgroundRefreshOutcome { throw CancellationException("job stopped") }
        }
    }

    @Test fun outcomeMatchesJobFinishedRescheduleContract() = runTest {
        // jobFinished(params, needsReschedule = !succeeded): success never
        // reschedules, operational failure asks for a retry.
        assertEquals(false, !runBackgroundRefreshOutcome { })
        assertEquals(true, !runBackgroundRefreshOutcome { throw IllegalStateException("down") })
    }
}
