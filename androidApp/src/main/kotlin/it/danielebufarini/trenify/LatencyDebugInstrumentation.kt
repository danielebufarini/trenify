package it.danielebufarini.trenify

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import it.danielebufarini.trenify.core.network.LatencyEvent
import it.danielebufarini.trenify.core.network.RecordingRequestInstrumentation
import it.danielebufarini.trenify.core.network.RequestInstrumentation

/** Debug-only structured log sink; release builds keep the instrumentation disabled. */
fun debugLatencyInstrumentation(context: Context): RequestInstrumentation =
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
        RecordingRequestInstrumentation { event -> Log.i("TrenifyLatency", event.toJsonLine()) }
    } else {
        RequestInstrumentation.None
    }

private fun LatencyEvent.toJsonLine(): String = buildString {
    append('{')
    append("\"name\":").append(name.jsonString())
    append(",\"phase\":").append(phase.jsonString())
    append(",\"elapsed_ms\":").append(elapsedMillis)
    durationMillis?.let { append(",\"duration_ms\":").append(it) }
    append(",\"attributes\":{")
    attributes.toSortedMap().entries.joinTo(this, separator = ",") { (key, value) ->
        "${key.jsonString()}:${value.jsonString()}"
    }
    append("}}")
}

private fun String.jsonString(): String = buildString {
    append('"')
    for (character in this@jsonString) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
    append('"')
}
