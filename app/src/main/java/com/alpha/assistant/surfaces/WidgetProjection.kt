package com.alpha.assistant.surfaces

import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolUiCatalog
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
    return WidgetProjection(spec.title,state,ToolUiCatalog.widgetNodes(ui,state),
        showSurfaceSummary = spec.surfaces.isNotEmpty())
}

internal fun JSONObject.widgetActionId(): String? = when (getString("type")) {
    "button", "toggle" -> getString("action").takeIf { ToolUiCatalog.enabled(this) }
    else -> null
}
