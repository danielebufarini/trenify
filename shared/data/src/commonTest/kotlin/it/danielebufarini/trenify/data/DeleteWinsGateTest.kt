package it.danielebufarini.trenify.data

import it.danielebufarini.trenify.core.domain.PersonalDataWriteSupersededException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * T7.8 delete-wins ordering of [DeleteWinsGate], proven deterministically
 * with coroutine gates instead of timing: no sleeps, no scheduler luck.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteWinsGateTest {
    @Test fun sequentialWritesAndDeletesComposeInOrder() = runTest {
        val gate = DeleteWinsGate()
        val log = mutableListOf<String>()

        gate.write { log += "write-1" }
        gate.delete { log += "delete" }
        gate.write { log += "write-2" }

        assertEquals(listOf("write-1", "delete", "write-2"), log)
    }

    @Test fun deletionWaitsForInFlightWriteThenRunsAfterIt() = runTest {
        val gate = DeleteWinsGate()
        val log = mutableListOf<String>()
        val releaseWrite = CompletableDeferred<Unit>()

        // The write is parked inside its critical section before the deletion
        // even starts, so the deletion must wait for it.
        val write = async {
            gate.write {
                log += "write-start"
                releaseWrite.await()
                log += "write-commit"
            }
        }
        runCurrent()
        val delete = async {
            gate.delete { log += "delete" }
        }
        runCurrent()
        assertEquals(listOf("write-start"), log)

        releaseWrite.complete(Unit)
        runCurrent()
        write.await()
        delete.await()
        assertEquals(listOf("write-start", "write-commit", "delete"), log)
    }

    @Test fun writeStartedBeforeACompletedDeletionIsDropped() = runTest {
        val gate = DeleteWinsGate()
        val releaseDelete = CompletableDeferred<Unit>()

        // The deletion is parked inside its critical section before the
        // write starts; the bump lands when the deletion completes, so the
        // write must fail instead of committing past it. runCatching keeps
        // the expected failure inside the Deferred result instead of letting
        // the test scheduler report it as uncaught.
        val delete = async {
            gate.delete { releaseDelete.await() }
        }
        runCurrent()
        val write = async { runCatching { gate.write { "committed" } } }
        runCurrent()

        releaseDelete.complete(Unit)
        runCurrent()
        delete.await()
        assertIs<PersonalDataWriteSupersededException>(write.await().exceptionOrNull())

        // A write invoked after the deletion completed is a new action.
        assertEquals("committed", gate.write { "committed" })
    }

    @Test fun historyAndFavoritesGatesAreIndependent() = runTest {
        val history = DeleteWinsGate()
        val favorites = DeleteWinsGate()
        val log = mutableListOf<String>()
        val releaseHistoryDelete = CompletableDeferred<Unit>()

        // A parked history deletion never blocks the favorites domain: there
        // is no unrelated global lock.
        val historyDelete = async { history.delete { releaseHistoryDelete.await() } }
        runCurrent()
        favorites.write { log += "favorite-write" }
        runCurrent()
        assertEquals(listOf("favorite-write"), log)

        releaseHistoryDelete.complete(Unit)
        runCurrent()
        historyDelete.await()
        history.write { log += "history-write" }
        assertEquals(listOf("favorite-write", "history-write"), log)
    }
}
