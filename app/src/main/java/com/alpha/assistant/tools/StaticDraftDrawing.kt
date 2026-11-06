package com.alpha.assistant.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.alpha.assistant.R
import org.json.JSONObject

@Composable internal fun StaticDraftDrawing(spec: ToolSpec, surfacesJson: String?, maxHeight: Dp) {
    if (spec.surfaces.isEmpty()) return
    if (surfacesJson == null) {
        Text(stringResource(R.string.tool_preview_drawing_unavailable), style = MaterialTheme.typography.bodySmall)
        return
    }

    val placements = remember(spec) { surfacePlacements(spec.surfaces.values) }
    val frames = remember(placements, surfacesJson) {
        val surfaces = JSONObject(surfacesJson)
        placements.mapNotNull { placement ->
            surfaces.optJSONObject(placement.surface.id)?.getJSONArray("commands")?.let { placement to it }
        }
    }
    val stageWidth = placements.maxOf { it.x + it.surface.width }
    val stageHeight = placements.maxOf { it.y + it.surface.height }
    val ratio = stageWidth.toFloat() / stageHeight
    val resources = LocalResources.current
    val labels = frames.joinToString("") { (_, commands) ->
        surfaceLabels(commands, unavailableImage = { label -> resources.getString(R.string.tool_image_unavailable, label) })
    }.trim()
    val drawingLabel = stringResource(R.string.tool_preview_drawing)
    val description = if (labels.isEmpty()) drawingLabel else "$drawingLabel. $labels"
    val scheme = MaterialTheme.colorScheme
    val canvasBackground = scheme.surface
    val roleColors = remember(scheme) { ToolUiCatalog.tones.associateWith { scheme.toolInk(it, canvasBackground).toArgb() } }

    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.width(minOf(maxWidth, maxHeight * ratio)).aspectRatio(ratio).clipToBounds()
            .background(canvasBackground).semantics { contentDescription = description }) {
            val canvas = drawContext.canvas.nativeCanvas
            val stage = canvas.save()
            try {
                canvas.scale(size.width / stageWidth, size.height / stageHeight)
                for ((placement, commands) in frames) {
                    val surface = placement.surface
                    val frame = canvas.save()
                    try {
                        canvas.translate(placement.x.toFloat(), placement.y.toFloat())
                        drawSurfaceFrame(canvas, surface, commands, surface.width.toFloat(), surface.height.toFloat(), roleColors)
                    } finally {
                        canvas.restoreToCount(frame)
                    }
                }
            } finally {
                canvas.restoreToCount(stage)
            }
        }
    }
}
