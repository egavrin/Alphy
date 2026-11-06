package com.alpha.assistant.surfaces

import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolUiCatalog
import com.alpha.assistant.tools.generation.NativeCompactSummary
import org.json.JSONArray
import org.json.JSONObject

internal data class WidgetProjection(
    val title: String,
    val state: JSONObject,
    val nodes: List<JSONObject>,
    val showSurfaceSummary: Boolean,
)

internal fun projectWidget(record: ToolRecord): WidgetProjection {
    val spec = ToolSpec.parsePersisted(record.spec)
    val state = spec.validateState(record.state)
    val ui = spec.validateUi(JSONObject(record.ui),state)
    val recipe = spec.nativePresentation
    val widgetUi = recipe?.let {
        when (it.compactSummary) {
            NativeCompactSummary.FOCAL -> {
                // Keep the root's visibility, enabled state, and interaction rules.
                val rootKeys = ui.keys().asSequence().toList().toTypedArray()
                JSONObject(ui,rootKeys).put("children",JSONArray().put(it.focalNode(ui)))
            }
        }
    } ?: ui
    return WidgetProjection(spec.title,state,ToolUiCatalog.widgetNodes(widgetUi,state),
        showSurfaceSummary = recipe == null && spec.surfaces.isNotEmpty())
}

internal fun JSONObject.widgetActionId(): String? = when (getString("type")) {
    "button", "toggle" -> getString("action").takeIf { ToolUiCatalog.enabled(this) }
    else -> null
}
