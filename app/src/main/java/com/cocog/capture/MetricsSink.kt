package com.cocog.capture

import android.util.Log

/**
 * Sink for metrics-only events (never audio, never text — see D16). T1 ships the
 * interface plus a logcat stub; T16 replaces the stub with the JSONL emitter, and
 * no task blocks on that swap.
 */
interface MetricsSink {
    fun event(name: String, fields: Map<String, Any?> = emptyMap())
}

/** Stub implementation that writes each event as a single logcat line. */
class LogcatMetricsSink(private val tag: String = "cocog.metrics") : MetricsSink {
    override fun event(name: String, fields: Map<String, Any?>) {
        val rendered =
            if (fields.isEmpty()) name
            else name + " " + fields.entries.joinToString(" ") { "${it.key}=${it.value}" }
        Log.i(tag, rendered)
    }
}
