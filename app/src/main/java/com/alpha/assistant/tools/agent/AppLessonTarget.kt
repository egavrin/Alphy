package com.alpha.assistant.tools.agent

import com.alpha.assistant.images.ImageIntent
import com.alpha.assistant.tools.ToolExecutionPolicy
import com.alpha.assistant.tools.ToolPreview
import com.alpha.assistant.tools.ToolSpec

/** A course slide request. The course code supplies its rules, and the agent loop runs them on each version. */
internal class AppLessonTarget(
    /** The lesson rules for this slide kind, added to the agent instructions. */
    val guide: String,
    /** The course policy. The smoke test also gives it each event result. */
    val policy: ToolExecutionPolicy,
    /** Throws with a message for the model when the slide breaks the course contract. */
    val checkSlide: (ToolSpec, ToolPreview) -> Unit,
    val allowedSourceIds: Set<String>,
    /** The slide kind permits a scene. */
    val scene: Boolean,
    /** The language of the reached slide goal. The model can use a different language that the course requests. */
    val languageHint: String?,
)

/** What a passing lesson finish adds to the slide: the cited sources and an optional picture request. */
internal class AppLessonResult(val sourceIds: List<String>, val illustration: ImageIntent?)
