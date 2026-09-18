package it.danielebufarini.trenify.core.network

import kotlinx.coroutines.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Low-overhead diagnostics used only by debug/test builds and focused
 * performance investigations. Implementations must not record payloads.
 */
interface RequestInstrumentation {
    fun onRequest(method: String, url: String)
    fun onResponse(method: String, url: String, statusCode: Int?, failure: Throwable?)

    fun event(name: String, attributes: Map<String, String> = emptyMap()) = Unit
    fun spanStarted(name: String, attributes: Map<String, String> = emptyMap()) = Unit
    fun spanFinished(
        name: String,
        duration: Duration,
        attributes: Map<String, String> = emptyMap(),
    ) = Unit

    data object None : RequestInstrumentation {
        override fun onRequest(method: String, url: String) = Unit

        override fun onResponse(
            method: String,
            url: String,
            statusCode: Int?,
            failure: Throwable?,
        ) = Unit
    }
}

/** A single structured record emitted by [RecordingRequestInstrumentation]. */
data class LatencyEvent(
    val name: String,
    val phase: String,
    val elapsedMillis: Long,
    val durationMillis: Long? = null,
    val attributes: Map<String, String> = emptyMap(),
)

/**
 * Monotonic recorder for debug/test sinks. It keeps no payload and does not
 * write anywhere by itself, so production builds can keep [None].
 */
class RecordingRequestInstrumentation(
    private val sink: (LatencyEvent) -> Unit,
) : RequestInstrumentation {
    private val origin = TimeSource.Monotonic.markNow()

    override fun onRequest(method: String, url: String) {
        emit("http.request", "start", mapOf("method" to method, "url" to diagnosticUrl(url)))
    }

    override fun onResponse(method: String, url: String, statusCode: Int?, failure: Throwable?) {
        emit("http.request", "response", buildMap {
            put("method", method)
            put("url", diagnosticUrl(url))
            statusCode?.let { put("status", it.toString()) }
            failure?.let { put("failure", it::class.simpleName ?: "Throwable") }
        })
    }

    override fun event(name: String, attributes: Map<String, String>) = emit(name, "event", attributes)

    override fun spanStarted(name: String, attributes: Map<String, String>) = emit(name, "start", attributes)

    override fun spanFinished(name: String, duration: Duration, attributes: Map<String, String>) =
        emit(name, "finish", attributes, duration.inWholeMilliseconds)

    private fun emit(name: String, phase: String, attributes: Map<String, String>, durationMillis: Long? = null) {
        sink(LatencyEvent(name, phase, origin.elapsedNow().inWholeMilliseconds, durationMillis, attributes))
    }
}

/** Measures a suspend span without changing cancellation or failure behavior. */
suspend inline fun <T> RequestInstrumentation.span(
    name: String,
    attributes: Map<String, String> = emptyMap(),
    crossinline block: suspend () -> T,
): T {
    val mark = TimeSource.Monotonic.markNow()
    spanStarted(name, attributes)
    var outcome = "success"
    return try {
        block()
    } catch (cancelled: CancellationException) {
        outcome = "cancelled"
        throw cancelled
    } catch (failure: Throwable) {
        outcome = "failed"
        throw failure
    } finally {
        spanFinished(name, mark.elapsedNow(), attributes + ("outcome" to outcome))
    }
}

/**
 * Canonical diagnostic URL sanitization (T8 corrective): exactly one path
 * for every instrumentation event. Query strings never leave the client,
 * and provider operation identifiers (for example Italo booking status
 * ids) collapse to `{operation}`. No payload, token, session or raw
 * operation id may reach a diagnostic sink through any other derivation.
 */
internal fun diagnosticUrl(url: String): String = url.substringBefore('?')
    .replace(Regex("/status/[^/]+$"), "/status/{operation}")
