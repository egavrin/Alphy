package com.alpha.assistant.tools.generation

import org.json.JSONObject

internal data class SceneRequirement(
    val placement: String,
    val brief: String,
    val checks: GenerationBehavior.Scenario,
    val liveInputs: Set<String>,
) {
    companion object {
        val guidance = """
            Put "scene" at the TOP LEVEL beside "language", never inside a needs entry.
            A needs entry of type "scene" keeps the ordinary id, type, label, description, and optional reads fields.
            Put placement, brief, and checks only inside the top-level scene.
            For a full-window moving playfield use this complete plan shape with the requested language and brief:
            {"wholeScreen":true,"language":"en","scene":{"placement":"immersive","brief":"A clear playfield with moving objects and visible feedback.","checks":{"status":"checkable","scenario":{"slots":{"surface":{"kind":"surface"},"object":{"kind":"geometry"}},"steps":[{"op":"frames","slot":"surface","count":4,"observe":"object","changes":3}]}}}}
            For an embedded scene use "inline". Example of the top-level scene value for a native numeric control:
            {"placement":"inline","brief":"Show a clearly labeled shape that grows with the slider.","liveInputs":["slider"],"checks":{"status":"checkable","scenario":{"slots":{"slider":{"kind":"action"},"shape":{"kind":"geometry"}},"steps":[{"op":"capture","slot":"shape","as":"before"},{"op":"action","slot":"slider","value":5},{"op":"assert","slot":"shape","test":"changed","capture":"before"}]}}}
            Use "stage" for the existing standalone drawing above native controls. Every drawing needs a scene check.
            Include in liveInputs only action slots that require continuous input. For a native slider used by those slots set inputMode to "live" in inputs.
            Omit liveInputs when an ordinary Apply action meets the request.
            In brief describe the focal objects, layout position, palette, labels, and visible feedback.
            In checks describe an executable workflow for the requested drawing before seeing the implementation.
            Require visible geometry. For input use capture, the real action or pointer, and assert changed geometry.
            For continuous motion use frames and repeated geometry changes. Static scenes need no frame steps.
            Omit scene for ordinary forms and controls that need no drawing. Checks format:
        """.trimIndent() + "\n" + GenerationBehavior.diagnosticGuidance

        fun read(raw: Any?): SceneRequirement? {
            if (raw == null) return null
            val scene = raw as? JSONObject ?: throw PartsContractFailure("$.scene", "a scene object")
            scene.requireKeys("$.scene", setOf("placement", "brief", "checks"), setOf("liveInputs"))
            val placement = scene.opt("placement")
            requireParts(placement in setOf("inline", "stage", "immersive"), "$.scene.placement", "inline, stage, or immersive")
            val brief = scene.opt("brief")
            requireParts(brief is String && brief.isNotBlank() && brief.length <= 1200, "$.scene.brief", "a visual brief up to 1200 characters")
            val checks = GenerationBehavior.parse(scene.opt("checks"), emptySet()) as? GenerationBehavior.Scenario
                ?: throw PartsContractFailure("$.scene.checks", "an executable drawing check")
            requireParts(checks.steps.any { step ->
                step is GenerationBehavior.Step.Advance && step.observe != null ||
                    step is GenerationBehavior.Step.Assert && checks.slots[step.slot]?.kind == GenerationBehavior.Kind.GEOMETRY
            }, "$.scene.checks", "an assertion on visible geometry")
            val liveInputs = if (scene.has("liveInputs")) scene.items("liveInputs", 0..12, "$.scene.liveInputs").map {
                val slot = planText(it, "$.scene.liveInputs", 24)
                requireParts(checks.steps.any { step -> step is GenerationBehavior.Step.Action && step.slot == slot },
                    "$.scene.liveInputs", "slots used by input actions")
                slot
            } else emptyList()
            requireParts(liveInputs.distinct().size == liveInputs.size, "$.scene.liveInputs", "distinct action slots")
            return SceneRequirement(placement as String, brief as String, checks, liveInputs.toSet())
        }
    }
}
