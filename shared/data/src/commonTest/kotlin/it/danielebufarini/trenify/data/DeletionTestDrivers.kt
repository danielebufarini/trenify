package it.danielebufarini.trenify.data

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Transparent [SqlDriver] wrapper for T7.8 deletion tests. Production code is
 * never modified for testability: all fault injection and synchronization
 * gates live here, in test code, matching on the generated SQL text.
 */
open class HookableTestDriver(private val delegate: SqlDriver) : SqlDriver {
    open fun beforeExecute(sql: String) {}

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = delegate.executeQuery(identifier, sql, mapper, parameters, binders)

    final override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        beforeExecute(sql)
        return delegate.execute(identifier, sql, parameters, binders)
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> = delegate.newTransaction()

    override fun currentTransaction(): Transacter.Transaction? = delegate.currentTransaction()

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        delegate.addListener(*queryKeys, listener = listener)
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        delegate.removeListener(*queryKeys, listener = listener)
    }

    override fun notifyListeners(vararg queryKeys: String) {
        delegate.notifyListeners(*queryKeys)
    }

    override fun close() = delegate.close()
}

/**
 * Fails the first write whose SQL matches [failOnSql], proving that a
 * multi-statement Settings deletion rolls back instead of partially
 * committing. Single-shot: after firing once, every later statement passes,
 * so retries and follow-up assertions observe a healthy driver.
 */
class FailingTestDriver(
    delegate: SqlDriver,
    private val failOnSql: (String) -> Boolean,
) : HookableTestDriver(delegate) {
    @Volatile
    private var armed = true

    override fun beforeExecute(sql: String) {
        if (armed && failOnSql(sql)) {
            armed = false
            throw IllegalStateException("injected T7.8 persistence failure: $sql")
        }
    }
}

/**
 * Blocks the calling thread inside [beforeExecute] while [blockWhen] matches,
 * staging deterministic delete-vs-write interleavings on the real persistence
 * path. [entered] turns true once a statement is parked inside the gate, so
 * the test knows the other operation holds its locks before proceeding. The
 * spin is bounded: a stuck gate fails the test instead of hanging the suite.
 * Only correctness-independent spinning happens here; pass/fail never depends
 * on timing.
 */
class GatingTestDriver(delegate: SqlDriver) : HookableTestDriver(delegate) {
    @Volatile
    var blockWhen: (String) -> Boolean = { false }

    @Volatile
    var entered = false

    override fun beforeExecute(sql: String) {
        if (!blockWhen(sql)) return
        entered = true
        val deadline = Clock.System.now() + 30.seconds
        while (blockWhen(sql)) {
            if (Clock.System.now() > deadline) {
                throw IllegalStateException("T7.8 test gate timed out for: $sql")
            }
        }
    }

    fun open() {
        blockWhen = { false }
    }
}
