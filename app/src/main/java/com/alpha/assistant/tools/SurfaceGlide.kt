package com.alpha.assistant.tools

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** One layer of a glide frame: [commands] drawn with [alpha] from 0 to 1. An [opaque] layer first fills its surface with the background. */
internal class GlideLayer(val commands: JSONArray, val alpha: Float, val opaque: Boolean = false)

/**
 * The smooth change of a template scene on the stage (block 2 plan, M5, M6, and C1). The host draws the frames between two drawings of one
 * surface, with no call into the app. A press glides in [PRESS_NS] with an ease-out. A step of the motion timer glides linearly over the time
 * of one step, so a play scene shows a new picture at each display frame. The same list of shapes glides each number. A shape whose kind,
 * color, or text changes cross-fades alone, and another list of shapes cross-fades as a whole. Times are in ns of one clock.
 * [snapDistance] is the jump of a number in one motion step that shows at once, such as the wrap of a loop from its end to its start.
 */
internal class SurfaceGlide(private val snapDistance: Double) {
    private var from: JSONArray? = null
    private var to: JSONArray? = null
    private var startNs = 0L
    private var endNs = 0L
    private var easeOut = true
    private var whole = false
    private var lastMotionNs = 0L
    private var reported = true

    /** The drawing that the glide ends at, or null before the first one. */
    val target: JSONArray? get() = to

    /** True while a frame at [nowNs] is between two drawings. */
    fun running(nowNs: Long) = from != null && nowNs < endNs

    /**
     * A new drawing [next] at [nowNs], from a step of the motion timer when [motion] is true. The glide starts from the picture that shows at
     * [nowNs] (M6). The first drawing and an equal drawing change nothing in the frames, and a motion step that jumps more than [snapDistance]
     * shows at once. A motion step inside a press glide keeps its end and its ease-out. Gives true when the frames change.
     * Any other change is a press at [pressNs]: its glide ends [PRESS_NS] after the press, so the time of the app call is part of the glide
     * (gate of M5), and it runs at least [MIN_PRESS_NS].
     */
    fun retarget(next: JSONArray, nowNs: Long, motion: Boolean, pressNs: Long = nowNs): Boolean {
        val old = to
        if (old == null) { to = next; return true }
        if (old.toString() == next.toString()) return false
        val shown = if (running(nowNs) && !whole) blend(checkNotNull(from), old, progress(nowNs)) else old
        val step = if (lastMotionNs == 0L) STEP_NS else (nowNs - lastMotionNs).coerceIn(MIN_STEP_NS, MAX_STEP_NS)
        val press = running(nowNs) && easeOut
        if (motion) lastMotionNs = nowNs
        val same = sameList(shown, next)
        from = shown; to = next; whole = !same
        when {
            motion && press -> startNs = nowNs
            motion && same && largestJump(shown, next) > snapDistance -> { from = null; endNs = nowNs }
            motion -> { startNs = nowNs; endNs = nowNs + step; easeOut = false }
            else -> { startNs = nowNs; endNs = maxOf(minOf(pressNs, nowNs) + PRESS_NS, nowNs + MIN_PRESS_NS); easeOut = true; reported = false }
        }
        return true
    }

    /** True once for each press glide, at the first frame at or after its end: the end of the smooth change of a press (gate of M5). */
    fun pressEnded(nowNs: Long): Boolean {
        if (reported || running(nowNs)) return false
        reported = true
        return true
    }

    /** The layers of the frame at [nowNs] in their order. At the end of a glide, the target drawing itself. */
    fun frame(nowNs: Long): List<GlideLayer> {
        val target = to ?: return emptyList()
        val start = from
        if (start == null || !running(nowNs)) return listOf(GlideLayer(target, 1f))
        val p = progress(nowNs)
        if (whole) return listOf(GlideLayer(start, 1f), GlideLayer(target, p, opaque = true))
        val layers = mutableListOf<GlideLayer>()
        var run = JSONArray()
        for (index in 0 until target.length()) {
            val a = start.get(index)
            val b = target.get(index)
            if (compatible(a, b)) { run.put(lerp(a, b, p)); continue }
            if (run.length() > 0) { layers += GlideLayer(run, 1f); run = JSONArray() }
            layers += GlideLayer(JSONArray().put(a), 1f - p)
            layers += GlideLayer(JSONArray().put(b), p)
        }
        if (run.length() > 0) layers += GlideLayer(run, 1f)
        return layers
    }

    private fun progress(nowNs: Long): Float {
        val share = ((nowNs - startNs).toDouble() / (endNs - startNs).coerceAtLeast(1)).coerceIn(0.0, 1.0).toFloat()
        return if (easeOut) 1f - (1f - share) * (1f - share) * (1f - share) else share
    }

    /** The picture between [a] and [b] at [p] as one list: each compatible command glides, and each other command is the one of [b]. */
    private fun blend(a: JSONArray, b: JSONArray, p: Float) = JSONArray().apply {
        for (index in 0 until b.length()) put(if (compatible(a.get(index), b.get(index))) lerp(a.get(index), b.get(index), p) else b.get(index))
    }

    companion object {
        const val PRESS_NS = 250_000_000L
        const val MIN_PRESS_NS = 120_000_000L
        private const val STEP_NS = 50_000_000L
        private const val MIN_STEP_NS = 16_000_000L
        // A step of the motion timer can take about 150 ms on the emulator (the app call of each step), so a step glides up to 250 ms with no stop.
        private const val MAX_STEP_NS = 250_000_000L

        /** The time of the last tap or spoken press of a control of an app, in ns of System.nanoTime: the start of the glide of its change. */
        @Volatile var pressedAtNs = 0L
            private set

        // shortcut: one press time for the whole process, so a press of another app within 1 s can shorten a glide; keep a time per app when two apps take presses at once.
        fun pressed() { pressedAtNs = System.nanoTime() }

        /** The press time of a change at [nowNs]: the last press within the last second, else [nowNs]. */
        fun pressTime(nowNs: Long) = pressedAtNs.takeIf { nowNs - it in 0..1_000_000_000L } ?: nowNs

        /** Two drawings of the same shapes: the same count, and the same kind of shape at each place. */
        fun sameList(a: JSONArray, b: JSONArray): Boolean =
            a.length() == b.length() && (0 until a.length()).all { a.getJSONObject(it).optString("type") == b.getJSONObject(it).optString("type") }

        /** Two values that differ only in their numbers: the same keys, the same lengths, and the same text at each place. */
        fun compatible(a: Any?, b: Any?): Boolean = when {
            a is Number && b is Number -> true
            a is JSONObject && b is JSONObject -> a.length() == b.length() && a.keys().asSequence().all { b.has(it) && compatible(a.get(it), b.get(it)) }
            a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { compatible(a.get(it), b.get(it)) }
            else -> a == b
        }

        /** The value between two compatible values [a] and [b] at [p]: each number moves, and each other value is the one of [b]. */
        fun lerp(a: Any?, b: Any?, p: Float): Any? = when {
            a is Number && b is Number -> a.toDouble() + (b.toDouble() - a.toDouble()) * p
            a is JSONObject && b is JSONObject -> JSONObject().apply { b.keys().forEach { put(it, lerp(a.get(it), b.get(it), p)) } }
            a is JSONArray && b is JSONArray -> JSONArray().apply { for (index in 0 until b.length()) put(lerp(a.get(index), b.get(index), p)) }
            else -> b
        }

        /** The largest change of one number between the compatible commands of [a] and [b]. */
        fun largestJump(a: JSONArray, b: JSONArray): Double {
            fun jump(x: Any?, y: Any?): Double = when {
                x is Number && y is Number -> abs(x.toDouble() - y.toDouble())
                x is JSONObject && y is JSONObject -> x.keys().asSequence().maxOfOrNull { jump(x.get(it), y.opt(it)) } ?: 0.0
                x is JSONArray && y is JSONArray -> (0 until minOf(x.length(), y.length())).maxOfOrNull { jump(x.get(it), y.get(it)) } ?: 0.0
                else -> 0.0
            }
            return (0 until b.length()).filter { compatible(a.get(it), b.get(it)) }.maxOfOrNull { jump(a.get(it), b.get(it)) } ?: 0.0
        }
    }
}
