package com.alpha.assistant.tools.generation

import org.json.JSONObject

/** Native clock transitions keep elapsed time separate from the display projection. */
internal object ClockParts {
    fun build(id: String, label: String, seconds: Int?, russian: Boolean): PartCandidate {
        val builder = FragmentBuilder(id)
        val elapsed = builder.state("elapsed", "number", 0)
        val started = builder.state("started", "number", 0)
        val boot = builder.state("boot", "number", 0)
        val running = builder.state("running", "boolean", false)
        val value = builder.state("value", "number", 0)
        val done = builder.state("done", "boolean", false)
        val limit = seconds?.times(1000L)
        fun isRunning(state: JSONObject, clock: PartClockSample): Boolean = state.getBoolean(running) &&
            state.getInt(boot) == clock.bootCount && state.getLong(started) <= clock.elapsedMillis
        fun time(state: JSONObject, clock: PartClockSample): Long {
            val stored = state.getLong(elapsed)
            val delta = if (isRunning(state, clock)) clock.elapsedMillis - state.getLong(started) else 0
            requireParts(stored in 0..9_007_199_254_740_991L, "$.state", "nonnegative elapsed milliseconds")
            return (stored + delta).let { if (limit == null) it else minOf(it, limit) }
        }
        val start = builder.clockedAction("start") { state, _, clock ->
            val complete = limit != null && time(state, clock) >= limit
            if (!isRunning(state, clock) || complete) {
                if (complete) state.put(elapsed, 0)
                state.put(started, clock.elapsedMillis).put(boot, clock.bootCount).put(running, true)
            }
        }
        val pause = builder.clockedAction("pause") { state, _, clock ->
            state.put(elapsed, time(state, clock)).put(running, false)
        }
        val reset = builder.clockedAction("reset") { state, _, _ ->
            state.put(elapsed, 0).put(started, 0).put(running, false)
        }
        fun button(text: String, action: String) = partNode("button", text).put("action", action)
        val metric = partNode("metric", if (russian) "$label · секунды" else "$label · seconds").put("bind", value)
        val completed = partNode("text", if (russian) "Готово" else "Complete").put("visibleBind", done)
        val controls = partContainer("row", listOf(button(if (russian) "Старт" else "Start", start),
            button(if (russian) "Пауза" else "Pause", pause), button(if (russian) "Сброс" else "Reset", reset)))
        val projection = PartReadProjection(setOf(value, done)) { state, clock, _ ->
            val current = time(state, clock)
            JSONObject().put(value, (limit?.minus(current) ?: current) / 1000.0)
                .put(done, limit != null && current >= limit)
        }
        val config = JSONObject().put("label", label).put("seconds", seconds ?: 0).put("russian", russian)
        return PartCandidate(if (seconds == null) "stopwatch" else "countdown", "controls",
            "Start, pause and reset $label locally. This clock has no completion effect.", config,
            builder.finish(partContainer("column", listOf(metric, completed, controls)), readProjection = projection),
            primaryActionId = start, secondaryActionIds = setOf(pause, reset),
            numberOutput = PartNumberOutput(value), valuePath = listOf(0),
            clockTicks = { state, clock -> isRunning(state, clock) && (limit == null || time(state, clock) < limit) })
    }
}
