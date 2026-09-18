package it.danielebufarini.trenify.provider.journey

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Small bounded provider-local in-memory read cache (T8 latency work).
 *
 * Purpose: eliminate repeated identical provider lookups (station
 * resolution, catalogues) across searches in one process. It stores
 * provider-local wire values only; domain identity is rebuilt from the
 * cached wire on every hit through the same mapping, so a hit returns the
 * same domain values a fresh fetch would.
 *
 * - Key semantics: the caller supplies the key, always a normalized
 *   provider-neutral query string (never a provider identifier); different
 *   stations and different adapter instances never share entries.
 * - TTL/invalidation: entries expire after [ttl]; entries matching
 *   [isNegative] (for example "station unknown to this provider") expire
 *   after the shorter [negativeTtl], so a false negative can only hide a
 *   provider briefly. Nothing is persisted: process death clears the cache.
 * - Concurrency: a mutex guards the map; concurrent misses for one key
 *   share a single upstream fetch (one owner fetches, the rest await the
 *   deferred outside the lock). Failures are never cached. A waiter whose
 *   owner dies retries as a potential new owner; a waiter's own
 *   cancellation still propagates.
 * - Bound: at most [maxEntries] entries; the eldest insertion is evicted.
 */
internal class ProviderLocalCache<T>(
    private val clock: Clock,
    private val ttl: Duration,
    private val negativeTtl: Duration = ttl,
    private val isNegative: (T) -> Boolean = { false },
    maxEntries: Int = 128,
) {
    private val bound = maxEntries.coerceAtLeast(1)
    private data class Entry<T>(val value: T, val storedAt: Instant)
    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Entry<T>>()
    private val inflight = mutableMapOf<String, CompletableDeferred<T>>()

    /** Fresh cached value, or null on miss/expiry. Never performs I/O. */
    suspend fun peek(key: String): T? = mutex.withLock { fresh(key) }

    suspend fun getOrFetch(key: String, fetch: suspend () -> T): T {
        while (true) {
            var awaitable: Deferred<T>? = null
            var owned: CompletableDeferred<T>? = null
            mutex.withLock {
                fresh(key)?.let { return it }
                val pending = inflight[key]
                if (pending != null) awaitable = pending
                else {
                    owned = CompletableDeferred()
                    inflight[key] = owned!!
                }
            }
            if (awaitable != null) {
                try {
                    return awaitable.await()
                } catch (cancelled: CancellationException) {
                    // Our own cancellation must propagate; when only the
                    // owner died, a live waiter loops to become the next
                    // owner instead of hanging or adopting the failure.
                    currentCoroutineContext().ensureActive()
                }
            } else {
                try {
                    val value = fetch()
                    mutex.withLock {
                        entries[key] = Entry(value, clock.now())
                        if (entries.size > bound) {
                            val eldest = entries.keys.first()
                            if (eldest != key) entries.remove(eldest)
                        }
                        if (inflight[key] === owned) inflight.remove(key)
                    }
                    owned!!.complete(value)
                    return value
                } catch (failure: Throwable) {
                    mutex.withLock { if (inflight[key] === owned) inflight.remove(key) }
                    owned!!.completeExceptionally(failure)
                    throw failure
                }
            }
        }
    }

    private fun fresh(key: String): T? {
        val entry = entries[key] ?: return null
        val lifetime = if (isNegative(entry.value)) negativeTtl else ttl
        if (clock.now() - entry.storedAt < lifetime) return entry.value
        entries.remove(key)
        return null
    }
}
