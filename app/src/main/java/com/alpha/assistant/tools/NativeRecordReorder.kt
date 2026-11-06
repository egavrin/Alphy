package com.alpha.assistant.tools

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R
import com.alpha.assistant.ui.AlphaIcon
import com.alpha.assistant.ui.Glyph
import org.json.JSONObject

/** Tracks placement and hover in this list only. A move is an ordinary native action. */
internal class NativeRecordReorder {
    val rows = mutableMapOf<String, LayoutCoordinates>()
    var highlight by mutableStateOf<String?>(null)
        private set
    private var source: String? = null

    fun begin(key: String): Boolean {
        if (source != null) return false
        source = key
        return true
    }

    fun hover(key: String, point: Offset): String? {
        if (source != key) return null
        val target = rows.entries.firstOrNull { (candidate, coordinates) ->
            candidate != key && coordinates.isAttached && coordinates.boundsInRoot().contains(point)
        }?.key
        highlight = target
        return target
    }

    fun cancel(key: String) {
        if (source == key) {
            source = null
            highlight = null
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun NativeReorderRow(
    reorder: JSONObject,
    record: JSONObject,
    enabled: Boolean,
    eligibleKeys: List<String>,
    tracker: NativeRecordReorder,
    event: (String, Any?) -> Unit,
    content: @Composable () -> Unit,
) {
    val key = ToolUiCatalog.stableKey(record)
    val index = eligibleKeys.indexOf(key)
    val available = enabled && index >= 0 && eligibleKeys.size > 1
    val label = stringResource(R.string.tool_reorder_handle, ToolUiCatalog.value(record, reorder.getString("labelField")))
    val action = reorder.getString("action")
    val latestEvent by rememberUpdatedState(event)
    var menuOpen by remember(eligibleKeys) { mutableStateOf(false) }
    var handleCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    fun payload(target: String) = JSONObject().put("sourceKey", key).put("destinationKey", target)
    fun dispatch(target: String) {
        menuOpen = false
        event(action, payload(target))
    }

    val placement = if (available) {
        DisposableEffect(tracker, key) { onDispose { tracker.rows.remove(key) } }
        Modifier.onGloballyPositioned { tracker.rows[key] = it }
    } else Modifier
    val highlight = if (tracker.highlight == key) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
    } else Modifier
    val gesture = if (available) {
        Modifier.onGloballyPositioned { handleCoordinates = it }.pointerInput(tracker, key, action, eligibleKeys) {
            var startedWith: ((String, Any?) -> Unit)? = null
            fun target(point: Offset) = handleCoordinates?.takeIf { it.isAttached }?.let {
                tracker.hover(key, it.localToRoot(point))
            }
            try {
                detectDragGestures(
                    orientationLock = Orientation.Vertical,
                    onDragStart = { _, _, _ ->
                        // Keep the action's artifact and revision from the start of this drag.
                        startedWith = if (tracker.begin(key)) latestEvent else null
                    },
                    onDragCancel = { tracker.cancel(key) },
                    onDragEnd = { change ->
                        val destination = if (startedWith != null) target(change.position) else null
                        tracker.cancel(key)
                        if (destination != null) startedWith?.invoke(action, payload(destination))
                    },
                ) { change, _ ->
                    change.consume()
                    if (startedWith != null) target(change.position)
                }
            } finally { tracker.cancel(key) }
        }
    } else Modifier

    Row(Modifier.fillMaxWidth().then(placement).then(highlight), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { content() }
        Box {
            IconButton(onClick = { menuOpen = true }, enabled = available,
                modifier = Modifier.size(48.dp).then(gesture).semantics { contentDescription = label }) {
                AlphaIcon(Glyph.Menu, color = LocalContentColor.current)
            }
            if (available) DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                eligibleKeys.getOrNull(index - 1)?.let { target ->
                    DropdownMenuItem(text = { Text(stringResource(R.string.tool_reorder_move_up)) }, onClick = { dispatch(target) })
                }
                eligibleKeys.getOrNull(index + 1)?.let { target ->
                    DropdownMenuItem(text = { Text(stringResource(R.string.tool_reorder_move_down)) }, onClick = { dispatch(target) })
                }
            }
        }
    }
}
