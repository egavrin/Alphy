package com.alpha.assistant.tools.generation

import com.alpha.assistant.courses.CourseSlideKind
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolUiCatalog
import com.alpha.assistant.tools.ToolValueSchema
import org.json.JSONArray
import org.json.JSONObject

internal enum class HoleKind { NEED, SCENE, SCREEN }

internal data class PartHole(
    val kind: HoleKind,
    val needIds: List<String>,
    val rootPath: String,
    val slotFailures: List<PartsContractFailure> = emptyList()
)

internal data class PartPlacement(
    val needId: String,
    val partId: String,
    val instanceId: String,
    val rootPath: String,
    val full: JSONObject,
    val compact: JSONObject,
    val widget: List<JSONObject>,
    val ids: PartFragmentIds,
    val position: Int,
    val config: JSONObject,
    val primaryActionId: String?,
    val secondaryActionIds: Set<String>,
    val behaviorTargets: NativeBehaviorTargets,
)

internal data class PartsAssembly(
    val preview: ToolSpec,
    val holes: List<PartHole>,
    val parts: List<PartPlacement>,
    val blockingQuestions: List<PlanQuestion>,
    val selectorFailures: List<PartsContractFailure>,
    val requiresReadProjection: Boolean = false,
    val readPorts: Set<String> = emptySet(),
    val requiresHostRuntime: Boolean = requiresReadProjection,
) {
    fun fillPositions(): List<Int> {
        val fixed = parts.map(PartPlacement::position).toSet()
        return (0 until parts.size + holes.size).filterNot(fixed::contains)
    }

    fun requireComplete(): ToolSpec {
        requireParts(blockingQuestions.isEmpty(), "$.questions", "answers to the blocking questions")
        requireParts(holes.isEmpty(), "$.holes", "a completed screen")
        requireParts(!requiresHostRuntime, "$.composition", "a sealed host runtime")
        return preview
    }
}

internal object PartsGeneration {
    private const val NO_MATCH = "Use a hole. No offered option covers the entire need."

    private data class PresentationChoice(val profile: NativePresentationProfile, val preset: String, val needId: String)

    suspend fun build(
        plan: NeedsPlan,
        lessonTarget: CourseSlideKind? = null,
        selectPresentation: Boolean = false,
        clock: PartClockSample? = null,
        preparedParts: List<PartRequest>? = null,
        ask: JevAsk,
    ): PartsAssembly {
        if (lessonTarget != null) {
            val requests = preparedParts ?: run {
                plan.validateLesson(lessonTarget)
                ToolParts.requests(plan, lessonTarget)
            }
            // Each typed lesson need has one native part. The plan already supplies its content.
            val selected = requests.associate { request ->
                request.slotFailure?.let { throw it }
                request.id to (request.candidates.singleOrNull()
                    ?: throw PartsContractFailure("$.needs", "one native part per lesson need"))
            }
            return assemble(plan, requests, selected, emptyList(), emptyList(), null)
        }
        val requests = preparedParts ?: ToolParts.requests(plan, clock = clock)
        val sections = requests.filter { it.candidates.isNotEmpty() }.associateWith { request ->
            request.candidates.map(PartCandidate::section).distinct()
        }
        val sectionQuestions = sections.map { (request, options) ->
            JevQuestion("s_${request.id}", request.prompt(),
                options.map { ToolParts.sectionDescriptions.getValue(it) } + NO_MATCH)
        }
        val clarificationQuestions = plan.questions.map { question ->
            JevQuestion("q_${question.id}",
                "Does the answer to this question block a correct build? ${question.ask} ${question.options.joinToString(" / ")}")
        }
        val sectionResult = JevSelector.ask(ask, "sections", plan, sectionQuestions + clarificationQuestions)
        val candidates = sections.mapNotNull { (request, options) ->
            val section = sectionResult.choice("s_${request.id}")?.let(options::getOrNull) ?: return@mapNotNull null
            request to request.candidates.filter { it.section == section }
        }.toMap()
        val partQuestions = candidates.map { (request, options) ->
            JevQuestion("p_${request.id}", request.prompt(), options.map(PartCandidate::description) + NO_MATCH)
        }
        val focalNeeds = if (selectPresentation) candidates.keys.toList() else emptyList()
        val profiles = NativePresentationProfile.entries
        val presets = ToolUiCatalog.presets.sorted()
        val presentationQuestions = if (focalNeeds.isEmpty()) emptyList() else listOf(
            JevQuestion("v_profile", "Which layout best fits the whole screen?", profiles.map { it.guidance }),
            JevQuestion("v_preset", "Which native style best fits this task?", presets),
            JevQuestion("v_focal", "Which need should be the focal part? Choose a need that receives a part in this batch.",
                focalNeeds.map { "${it.label}. ${it.description}" } + "Use no focal part.")
        )
        val partResult = JevSelector.ask(ask, "parts", plan, partQuestions, presentationQuestions)
        val picked = candidates.mapNotNull { (request, options) ->
            partResult.choice("p_${request.id}")?.let(options::getOrNull)?.let { request.id to it }
        }.toMap()
        val needsHostProjection = picked.values.any { it.fragment.readProjection != null }
        // Script projections require the plain parts runtime.
        val compatible = if (picked.size == requests.size && !needsHostProjection) picked
            else picked.filterValues { it.fragment.projectionSource == null }
        // A custom need has no declared independence from the selected parts.
        val numericOutputs = compatible.flatMap { (id, part) ->
            listOfNotNull(id.takeIf { part.numberOutput != null }) + part.aggregates.keys.map { "$id:$it" }
        }.toSet()
        val selected = if (compatible.isNotEmpty() && plan.needs.any { need ->
                val part = compatible[need.id]
                if (part == null) (need.type == NeedType.OTHER && need.reads == null) || need.figure != null ||
                    need.uses.values.any(compatible::containsKey) || need.reads.orEmpty().any { compatible[it]?.numberOutput == null }
                else part.fragment.readProjection?.reads.orEmpty().any { it !in numericOutputs }
            }) emptyMap() else compatible
        val blockers = plan.questions.filter { (sectionResult.probability("q_${it.id}") ?: 1.0) >= 0.5 }
        val focalNeed = partResult.choice("v_focal")?.let(focalNeeds::getOrNull)
        val focalFailure = focalNeed?.takeUnless { it.id in picked }?.let {
            PartsContractFailure("$.selector.parts.v_focal", "a selected focal need")
        }
        val presentation = if (focalNeed == null || focalNeed.id !in selected) null else PresentationChoice(
            profiles[checkNotNull(partResult.choice("v_profile"))],
            presets[checkNotNull(partResult.choice("v_preset"))],
            focalNeed.id
        )
        return assemble(plan, requests, selected, blockers,
            listOfNotNull(sectionResult.failure, partResult.failure, focalFailure), presentation)
    }

    private fun PartRequest.prompt() = "Which option covers this complete need? $label. $description"

    private fun assemble(
        plan: NeedsPlan,
        requests: List<PartRequest>,
        selected: Map<String, PartCandidate>,
        blockers: List<PlanQuestion>,
        failures: List<PartsContractFailure>,
        presentation: PresentationChoice?
    ): PartsAssembly {
        val schema = JSONObject()
        val initial = JSONObject()
        val children = mutableListOf<JSONObject>()
        val holes = mutableListOf<PartHole>()
        val source = StringBuilder("const state = input.state;\nconst event = input.event;\n")
        val pending = mutableListOf<Triple<PartRequest, PartCandidate, Int>>()
        if (selected.isEmpty()) {
            holes += PartHole(HoleKind.SCREEN, requests.map(PartRequest::id), "$.ui", requests.mapNotNull(PartRequest::slotFailure))
        } else {
            requests.forEachIndexed { index, request ->
                val path = "$.ui.children[$index]"
                val part = selected[request.id]
                if (part == null) {
                    val kind = if (request.type == NeedType.SCENE) HoleKind.SCENE else HoleKind.NEED
                    holes += PartHole(kind, listOf(request.id), path, listOfNotNull(request.slotFailure))
                    val prefix = if (plan.russian) "Готовится" else "In progress"
                    children += partNode("text", "$prefix: ${request.label}")
                } else {
                    children += part.fragment.full
                    pending += Triple(request, part, index)
                }
            }
        }
        val identifiers = pending.map { (_, part) ->
            PartFragmentIds(ToolValueSchema.keys(part.fragment.schema), part.fragment.handlers.keys)
        }
        requireUniqueIds(identifiers)
        val hasScriptActions = pending.any { (_, part) -> part.fragment.handlers.values.any { it is PartHandler.Script } }
        if (hasScriptActions) {
            source.append("if (event.type === \"action\") {\n  switch (event.actionId) {\n")
        }
        for ((_, part) in pending) {
            val fragment = part.fragment
            fragment.schema.keys().forEach { key ->
                schema.put(key, fragment.schema.get(key))
                initial.put(key, fragment.initial.get(key))
            }
            fragment.handlers.forEach { (action, handler) ->
                if (handler is PartHandler.Script) {
                    source.append("    case \"$action\": {\n${handler.source}\n      break;\n    }\n")
                }
            }
        }
        if (hasScriptActions) source.append("  }\n}\n")
        if (schema.length() == 0) {
            schema.put("partsReady", "boolean")
            initial.put("partsReady", holes.isEmpty())
        }
        val ui = if (selected.isEmpty()) {
            partNode("text", if (plan.russian) "Экран ещё готовится" else "This screen is in progress")
        } else partContainer("column", children)
        val projections = pending.mapNotNull { (_, part, position) ->
            part.fragment.projectionSource?.let { position to it }
        }
        if (projections.isEmpty()) {
            source.append("return {state};")
        } else {
            source.append("const ui = JSON.parse(${JSONObject.quote(ui.toString())});\n")
            projections.forEach { (position, projection) ->
                source.append("{\nconst view = ui.children[$position];\n$projection\n}\n")
            }
            source.append("return {state, ui};")
        }
        val definition = JSONObject().put("formatVersion", 3)
            .put("title", plan.title).put("description", plan.description).put("capabilities", JSONArray())
            .put("stateSchema", schema).put("initialState", initial).put("ui", ui).put("source", source.toString())
        val preview = if (presentation == null) ToolSpec.parse(definition.toString()) else {
            val (_, part, position) = pending.single { (request) -> request.id == presentation.needId }
            definition.put(NativePresentation.FIELD,
                NativePresentation.json(presentation.profile, presentation.preset, position, part.primaryActionId))
            ToolSpec.parsePersisted(definition.toString())
        }
        val state = JSONObject(preview.initialState)
        val placements = pending.mapIndexed { index, (request, part, position) ->
            val compact = ToolUiCatalog.validate(part.fragment.compact, preview.schema, state)
            PartPlacement(request.id, part.id, partInstanceId(request.id), "$.ui.children[$position]", part.fragment.full, compact,
                ToolUiCatalog.widgetNodes(compact, state), identifiers[index], position, part.config,
                part.primaryActionId, part.secondaryActionIds, part.behaviorTargets(request.id))
        }
        val requiresReadProjection = pending.any { (_, part) -> part.fragment.readProjection != null }
        val readPorts = if (selected.isEmpty()) emptySet() else plan.needs.filter { it.id !in selected }
            .flatMap { it.reads.orEmpty() }.toSet()
        val requiresHostRuntime = requiresReadProjection || pending.any { (_, part) -> part.fragment.handlers.values.any { it is PartHandler.Clocked } }
        return PartsAssembly(preview, holes, placements, blockers, failures, requiresReadProjection, readPorts, requiresHostRuntime)
    }

    internal fun requireUniqueIds(fragments: List<PartFragmentIds>) {
        val state = mutableSetOf<String>()
        val actions = mutableSetOf<String>()
        fragments.forEachIndexed { index, ids ->
            requireParts(ids.state.all { state.add(it) }, "$.parts[$index].state", "unique state identifiers")
            requireParts(ids.actions.all { actions.add(it) }, "$.parts[$index].actions", "unique action identifiers")
        }
    }
}
