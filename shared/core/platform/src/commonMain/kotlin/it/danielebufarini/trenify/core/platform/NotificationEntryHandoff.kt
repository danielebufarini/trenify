package it.danielebufarini.trenify.core.platform

/**
 * Small deterministic cold-entry handoff for notification destinations
 * (T7.13-H).
 *
 * There is a period where a native notification callback exists before the
 * shared root component is constructed. The native host offers the validated
 * destination here; the shared root consumes it once it becomes available.
 *
 * Rules, also recorded in tests and T7.13 evidence:
 * - at most one pending destination is retained (a newer offer replaces an
 *   older unconsumed one; the latest tap wins);
 * - [consume] returns the pending destination exactly once and clears it;
 * - duplicate native delivery of an already pending destination is a no-op;
 * - restored navigation is applied first; a consumed pending destination is
 *   routed on top of the restored tree (documented precedence: notification
 *   wins for the visible screen, restoration wins for the Back stack);
 * - this buffer owns no navigation and no components: it is instantiated and
 *   owned by the application graph (or a test), never a global service
 *   locator, and it never outlives its owner.
 */
class NotificationEntryHandoff {
    // Confined to the host UI thread by both platforms (Android main thread,
    // iOS main queue) and to a single test thread in tests; no locking is
    // needed across that discipline, and common code has no @Synchronized.
    private var pending: NotificationDestination? = null

    fun offer(destination: NotificationDestination) {
        if (pending?.routingKey != destination.routingKey) {
            pending = destination
        }
    }

    fun peek(): NotificationDestination? = pending

    fun consume(): NotificationDestination? {
        val value = pending
        pending = null
        return value
    }
}
