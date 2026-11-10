package com.alpha.assistant.tools.generation

import com.alpha.assistant.tools.generation.GenerationBehavior.Binding
import com.alpha.assistant.tools.generation.GenerationBehavior.Kind
import com.alpha.assistant.tools.generation.GenerationBehavior.Step
import org.json.JSONObject

internal enum class GeometryScope(val wire: String) {
    WHOLE("whole"), SELECTED("selected");

    companion object {
        fun of(binding: Binding.Drawing, threeScene: Boolean): GeometryScope =
            if (if (threeScene) binding.path == listOf(0) else binding.path.isEmpty()) WHOLE else SELECTED
    }
}

/** The check's observation is fixed by PLAN. Only the writer's binding can change. */
internal class SceneBindingMismatch(val step: Int, val slot: Int, requirement: String) :
    IllegalArgumentException("Step ${step + 1}, slot ${slot + 1} requires $requirement.")

internal class SceneCheckEvidence private constructor(
    private val observations: Map<Int, GeometryScope>,
    private val relations: List<TargetRelation>,
    val visualReview: List<String>,
) {
    private data class TargetRelation(val step: Int, val slot: String, val other: String, val same: Boolean)

    fun validateBindings(scenario: GenerationBehavior.Scenario, bindings: Map<String, Binding>, threeScene: Boolean) {
        for ((index, scope) in observations) {
            val slot = checkNotNull(observedSlot(scenario.steps[index]))
            val binding = bindings.getValue(slot) as Binding.Drawing
            if (GeometryScope.of(binding, threeScene) != scope) {
                throw SceneBindingMismatch(index, scenario.slots.keys.indexOf(slot), "a ${scope.wire} geometry binding")
            }
        }
        for ((step, slot, other, same) in relations) {
            if ((bindings.getValue(slot) == bindings.getValue(other)) != same) {
                val otherNumber = scenario.slots.keys.indexOf(other) + 1
                val target = if (same) "the same geometry target as slot $otherNumber"
                    else "a geometry target different from slot $otherNumber"
                throw SceneBindingMismatch(step, scenario.slots.keys.indexOf(slot), target)
            }
        }
    }

    private enum class Purpose(val wire: String) {
        OUTCOME("outcome"), MOTION("motion"), STABILITY("stability"), PERIODIC_RETURN("periodic_return"),
        PRESERVATION("preservation")
    }

    companion object {
        private const val FIELD = "$.scene.checkEvidence"
        private val excerptSpacing = Regex("""[ \t\r\n]+""")

        internal fun normalizedExcerpt(text: String) = excerptSpacing.replace(text, " ").trim(' ')
        val guidance = """
            Include scene.checkEvidence with one row for every assert and every frames step with observe.
            Example: "checkEvidence":[{"step":1,"excerpt":"animate the orbit","purpose":"motion","scope":"whole"}].
            Step is one-based. Quote the original request. Spaces, tabs, and line breaks are equivalent between words.
            Keep wording, case, and punctuation exact. Use context to clarify the request.
            Purpose is outcome, motion, stability, periodic_return, or preservation.
            Use scope whole or selected for geometry; omit scope for other observations. Use the same scope for each use of a geometry slot.
            For unchanged or equals against a captured image, use stability or periodic_return. Capture the same output you compare.
            To check stability after motion on the same target, stop the motion and take a new capture before comparing.
            A periodic return requires observed motion before the image returns to its original appearance. A same-session capture cannot prove preservation across revisions.
            Use scene.visualReview (an array of original request excerpts) for requested visual details outside the executable checks.
            Preserve all requested outcomes. Do not invent a pixel comparison to stand in for a requested appearance.
        """.trimIndent()

        fun runtimeGuidance(threeScene: Boolean) = if (threeScene) """
            Three.js geometry observes viewport pixels. A slot name does not select a scene object.
            Whole geometry defaults to {"surface":"scene","path":[0]}. For selected geometry explicitly bind path [1] through [4].
            Paused frame steps deliver frame calls to the scene so the host can check that it stays still.
        """.trimIndent() else """
            2D geometry observes drawing commands. Bind path [] for the whole drawing, or use command and group indices for selected geometry.
            A slot name does not select an object. Frame and timer steps require active playback and the corresponding subscription.
        """.trimIndent()

        fun read(scene: JSONObject, scenario: GenerationBehavior.Scenario, request: String, threeScene: Boolean): SceneCheckEvidence {
            val normalizedRequest = normalizedExcerpt(request)
            val steps = scenario.steps
            val observed = steps.indices.filter { observedSlot(steps[it]) != null }
            val rows = scene.items("checkEvidence", observed.size..observed.size, FIELD)
            val scopes = mutableMapOf<Int, GeometryScope>()
            val slotScopes = mutableMapOf<String, GeometryScope>()
            val relations = mutableListOf<TargetRelation>()
            val covered = mutableSetOf<Int>()
            for (item in rows) {
                val row = item as? JSONObject ?: throw PartsContractFailure(FIELD, "an evidence object")
                row.requireKeys(FIELD, setOf("step", "excerpt", "purpose"), setOf("scope"))
                val number = row.opt("step") as? Int
                val index = number?.minus(1)
                requireParts(index != null && index in observed && covered.add(index), FIELD, "one row per observed step")
                val step = steps[checkNotNull(index)]
                val at = "$FIELD[$index]"
                val slot = checkNotNull(observedSlot(step))
                val geometry = scenario.slots.getValue(slot).kind == Kind.GEOMETRY
                excerpt(row.opt("excerpt"), normalizedRequest, at)
                val purpose = Purpose.entries.firstOrNull { it.wire == row.opt("purpose") }
                requireParts(purpose != null, at, "a supported check purpose")
                val scope = GeometryScope.entries.firstOrNull { it.wire == row.opt("scope") }
                requireParts(if (geometry) scope != null else !row.has("scope"), at, "a scope only for geometry")
                if (scope != null) {
                    val prior = slotScopes.put(slot, scope)
                    requireParts(prior == null || prior == scope, at, "one geometry scope per slot")
                    scopes[index] = scope
                }
                if (step is Step.Advance) {
                    requireParts(purpose == Purpose.MOTION, at, "motion purpose for observed frame steps")
                    continue
                }
                check(step is Step.Assert)
                requireParts(purpose != Purpose.MOTION || geometry && step.test == "changed", at,
                    "a changed geometry assertion for motion")
                requireParts(purpose != Purpose.PRESERVATION || step.capture == null, at,
                    "preservation evidence independent of the candidate's captured output")
                val equality = step.test in setOf("unchanged", "equals")
                requireParts(purpose != Purpose.STABILITY || equality && step.capture != null, at,
                    "equality with a capture for stability")
                val imageEquality = geometry && equality && step.capture != null
                requireParts(purpose != Purpose.PERIODIC_RETURN || imageEquality, at, "geometry equality for periodic return")
                if (!geometry || step.capture == null) continue
                val captureIndex = steps.indexOfFirst { it is Step.Capture && it.name == step.capture }
                val captured = steps[captureIndex] as Step.Capture
                if (captured.slot != slot) relations += TargetRelation(index, slot, captured.slot, same = true)
                if (!imageEquality) continue
                requireParts(purpose == Purpose.STABILITY || purpose == Purpose.PERIODIC_RETURN, at,
                    "stability or periodic_return for a captured geometry equality")
                val interval = steps.subList(captureIndex + 1, index)
                val motion = interval.filterIsInstance<Step.Advance>().mapNotNull { it.observe }
                if (purpose == Purpose.PERIODIC_RETURN) {
                    requireParts(motion.isNotEmpty(), at, "observed motion before the periodic return")
                    requireParts(slot in motion || motion.distinct().size == 1, at,
                        "observed motion on the periodic return target")
                    if (slot !in motion) relations += TargetRelation(index, slot, motion.first(), same = true)
                } else if (motion.isNotEmpty()) {
                    requireParts(slot !in motion && (!threeScene || scope != GeometryScope.WHOLE), at,
                        "a stationary observation separate from the observed motion, or a periodic_return")
                    for (moving in motion) relations += TargetRelation(index, slot, moving, same = false)
                }
            }
            if (!threeScene) {
                var paused = false
                for (step in steps) {
                    if (step is Step.Surface) when (step.event) {
                        "pause" -> paused = true
                        "start", "resume", "restart" -> paused = false
                    }
                    requireParts(step !is Step.Advance || !paused, FIELD, "active playback for 2D frame and timer steps")
                }
            }
            for (relation in relations.filter { it.same }) {
                val a = slotScopes[relation.slot]
                val b = slotScopes[relation.other]
                requireParts(a == null || b == null || a == b, FIELD, "the same geometry scope for a captured comparison")
            }
            val visual = if (scene.has("visualReview")) {
                scene.items("visualReview", 1..8, "$.scene.visualReview")
                    .map { excerpt(it, normalizedRequest, "$.scene.visualReview") }.distinct()
            } else emptyList()
            return SceneCheckEvidence(scopes, relations, visual)
        }

        private fun excerpt(value: Any?, normalizedRequest: String, path: String): String {
            requireParts(value is String && value.length in 1..600 && value.isNotBlank() &&
                normalizedExcerpt(value) in normalizedRequest,
                path, "an exact excerpt from the original request, with line breaks and spacing allowed to differ")
            return value as String
        }

        private fun observedSlot(step: Step): String? = when (step) {
            is Step.Advance -> step.observe
            is Step.Assert -> step.slot
            else -> null
        }
    }
}
