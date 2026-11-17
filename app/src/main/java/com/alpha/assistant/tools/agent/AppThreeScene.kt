package com.alpha.assistant.tools.agent

import com.alpha.assistant.tools.ToolSurface
import com.alpha.assistant.tools.three.ThreeSceneSession
import org.json.JSONObject

/**
 * Runs agent steps on a started 3D scene. Native controls send action events, and "scene" names the viewport.
 * Expectations read the native UI and the state from the latest scene snapshot.
 */
internal class ThreeSceneTarget(private val session: ThreeSceneSession) : AppTarget {
    private var clock = System.currentTimeMillis() / 1000 * 1000
    override val ui: JSONObject get() = JSONObject(session.snapshot.ui)
    override val state: JSONObject get() = JSONObject(session.snapshot.state)
    override val surfaces: JSONObject get() = session.snapshot.hudSurfaces(SCENE)
    override val nowMillis: Long get() = clock
    override val surfaceIds: Set<String> = setOf(SCENE)
    override val surfaceSizes: Map<String, ToolSurface> get() = session.snapshot.hudSizes(SCENE)
    override val requested: List<String> = emptyList()

    override suspend fun control(id: String, payload: Any?) = session.action(id, payload)

    override suspend fun playback(type: String) = session.event(JSONObject().put("type", type))

    override suspend fun pointer(surfaceId: String, path: List<Pair<Double, Double>>) {
        for ((index, point) in path.withIndex()) {
            val type = when (index) { 0 -> "pointerdown"; path.lastIndex -> "pointerup"; else -> "pointermove" }
            session.event(session.pointer(type, 1, point.first, point.second))
        }
    }

    override suspend fun tilt(x: Double, y: Double) =
        throw AppStepError("A 3D scene has no tilt input. Use pointer or drag on \"$SCENE\" or the native controls.")

    override suspend fun wait(milliseconds: Long): Int {
        var frames = 0
        var left = milliseconds.coerceAtMost(MAX_WAIT_MS)
        while (left > 0) {
            val tick = minOf(FRAME_MS, left)
            session.frame(tick.toInt())
            clock += tick
            left -= tick
            frames++
        }
        return frames
    }

    companion object {
        const val SCENE = "scene"
        private const val FRAME_MS = 50L
        private const val MAX_WAIT_MS = 60_000L
    }
}
