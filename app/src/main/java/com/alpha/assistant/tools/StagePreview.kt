package com.alpha.assistant.tools

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One compiled preview of an app file that is not stored: the definition, the UI tree, the state, the drawing commands, and the kit `holds` value. */
internal data class StagePreviewFrame(val spec: ToolSpec, val ui: String, val state: String, val drawing: String?, val holds: String)

/** The preview of [operationId] on the stage. [toolId] is the changed tool, and [revision] is set when the app is stored. */
internal data class StagePreview(val operationId: String, val frame: StagePreviewFrame, val toolId: String?, val revision: Int?)

/**
 * The one preview that the stage draws. It is Compose state: call each function on the main thread only.
 * An operation ends at [gone] or [committed], and a later [show] of that operation is dropped.
 */
internal class StagePreviews {
    var current by mutableStateOf<StagePreview?>(null); private set
    // shortcut: ended ids stay for the life of the controller (one short id per screen run), prune them if runs reach thousands.
    private val ended = mutableSetOf<String>()

    /** Shows [frame] uncommitted, in place of any preview, also one of another operation. A call for an ended operation does nothing. */
    fun show(operationId: String, frame: StagePreviewFrame, toolId: String? = null) {
        if (operationId in ended) return
        current = StagePreview(operationId, frame, toolId, null)
    }

    /** Ends [operationId] and removes its uncommitted preview. A committed preview stays until [drawn], because it seeds the live drawing. */
    fun gone(operationId: String) {
        ended += operationId
        if (current?.let { it.operationId == operationId && it.revision == null } == true) current = null
    }

    /** Ends [operationId] and marks its preview stored as [revision] of [toolId]. With no current preview of [operationId], only the end applies. */
    fun committed(operationId: String, toolId: String, revision: Int) {
        ended += operationId
        current?.takeIf { it.operationId == operationId }?.let { current = it.copy(toolId = toolId, revision = revision) }
    }

    /** Removes a committed preview of [toolId] after the live app drew. An uncommitted preview stays. */
    fun drawn(toolId: String) {
        if (current?.let { it.toolId == toolId && it.revision != null } == true) current = null
    }
}
