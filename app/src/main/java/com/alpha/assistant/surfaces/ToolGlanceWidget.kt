package com.alpha.assistant.surfaces

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.ButtonDefaults
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.Switch
import androidx.glance.appwidget.SwitchDefaults
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.alpha.assistant.R
import com.alpha.assistant.scheduling.SourceRefresh
import com.alpha.assistant.scheduling.SourceStatus
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolLibraryStorage
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.tools.ToolUiCatalog
import com.alpha.assistant.surfaces.widgetauthoring.WidgetLayoutStore
import com.alpha.assistant.surfaces.widgetauthoring.WidgetPresentation
import com.alpha.assistant.surfaces.widgetauthoring.projectRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import kotlin.math.ceil

internal class ToolGlanceWidget private constructor(private val preview: WidgetInput?) : GlanceAppWidget() {
    constructor() : this(null)

    override val sizeMode = SizeMode.Exact
    override val stateDefinition = null

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        preview?.let { sample ->
            provideContent { WidgetContent(sample, widgetId = null) }
            return
        }
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val generation = ToolWidgets.refreshes.value
        val initial = readWidget(context,widgetId)
        provideContent {
            val input by produceState(initial,widgetId) {
                ToolWidgets.refreshes.collect { version ->
                    if (version != generation) value = readWidget(context,widgetId)
                }
            }
            WidgetContent(input,widgetId)
        }
    }

    companion object {
        fun previewSize(prefs: WidgetPreferences): DpSize =
            if (prefs.format == "wide") DpSize(320.dp,320.dp) else DpSize(220.dp,280.dp)

        suspend fun preview(
            context: Context,
            tool: ToolRecord?,
            prefs: WidgetPreferences,
            size: DpSize = previewSize(prefs),
            deleted: Boolean = false,
        ): RemoteViews {
            val input = WidgetInput(prefs, tool, deleted = deleted)
            val display = withContext(Dispatchers.IO) { tool?.let { record ->
                runCatching { ToolStore(context).use { store -> input.withLibraryState(store, record) } }.getOrNull()
            } }
            return ToolGlanceWidget(display ?: input).compose(context,size = size)
        }
    }
}

private data class WidgetInput(
    val preferences: WidgetPreferences,
    val tool: ToolRecord?,
    val locked: Boolean = false,
    val deleted: Boolean = false,
    val generation: String = ToolLibraryStorage.ORIGINAL,
    val layout: WidgetPresentation? = null,
    val source: SourceStatus? = null,
)

private fun WidgetInput.withLibraryState(store: ToolStore, record: ToolRecord) = copy(
    tool = record, layout = WidgetLayoutStore(store).presentation(record),
    source = SourceRefresh(store).status(record.id)?.takeIf { it.offer.matches(record) },
)

private suspend fun readWidget(context: Context, widgetId: Int): WidgetInput = withContext(Dispatchers.IO) {
    val locked = ToolWidgets.isLocked(context)
    val generation=runCatching {ToolLibraryStorage.current(context).generation}.getOrDefault(ToolLibraryStorage.ORIGINAL)
    val prefs = runCatching {ToolWidgets.preferences(context,widgetId)}.getOrDefault(WidgetPreferences())
    val input = WidgetInput(prefs, null, locked, runCatching {ToolWidgets.deleted(context,widgetId)}.getOrDefault(false), generation)
    if (locked) return@withContext input
    runCatching { ToolWidgets.toolId(context,widgetId)?.let { owner ->
        ToolStore(context).use { store ->
            if (store.library.generation != generation) null
            else store.tool(owner)?.let { input.withLibraryState(store, it) }
        }
    }}.getOrNull() ?: input
}

private fun WidgetInput.project(context: Context): WidgetProjection? {
    if (locked) return null
    val record = tool?.takeIf { it.saved && !it.archived } ?: return null
    return runCatching {
        val projection = projectWidget(record)
        val sourceRows = source?.sample?.let { sample ->
            val origin = Uri.parse(sample.getString("source")).encodedAuthority.orEmpty()
            val timestamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(sample.getLong("dataTimeMs")))
            val metadata = context.getString(
                if (source.failed) R.string.live_source_widget_stale else R.string.live_source_widget_fresh, origin, timestamp)
            listOf(sample.get("value").toString(), metadata).map { JSONObject().put("type", "text").put("text", it) }
        }.orEmpty()
        layout?.let { return@runCatching projection.copy(title = widgetText(context, projection.title), nodes = sourceRows + projectRows(projection, it), showSurfaceSummary = false) }
        val nodes = projection.nodes.filter { node ->
            node.getString("type") != "text" || node.has("bind") || node.has("spans") ||
                (preferences.details && !node.getString("text").equals(projection.title,ignoreCase = true))
        }
        val ordered = (listOfNotNull(nodes.firstOrNull { it.has("bind") },
            nodes.firstOrNull { it.getString("type") in listOf("button","toggle") }) +
            nodes.filter { it.has("bind") } + nodes).distinct()
        val withSurface = if (projection.showSurfaceSummary) ordered +
            JSONObject().put("type","text").put("text",context.getString(R.string.widget_surface)) else ordered
        projection.copy(title = widgetText(context,projection.title),nodes = sourceRows + withSurface)
    }.getOrNull()
}

@Composable
private fun WidgetContent(input: WidgetInput, widgetId: Int?) {
    val context = LocalContext.current
    val size = LocalSize.current
    val colors = widgetColors(context)
    val projection = input.project(context)
    val fontScale = context.resources.configuration.fontScale
    val rowHeight = maxOf(48,ceil(22 * fontScale + 12).toInt())
    val titleHeight = maxOf(24,ceil(24 * fontScale).toInt())
    val wide = input.preferences.format == "wide" ||
        (input.preferences.format == "adaptive" && size.width >= 250.dp)
    val availableHeight = (size.height.value - 72).toInt()
    val minimumRows = if (projection == null) 1 else 2
    val showTitle = availableHeight >= titleHeight + minimumRows * rowHeight
    val rowCapacity = ((availableHeight - if (showTitle) titleHeight else 0) / rowHeight)
        .coerceIn(0,if (wide) 6 else 3)
    val selected = projection?.nodes.orEmpty().take(rowCapacity)
    val hidden = (projection?.nodes?.size ?: 0) - selected.size
    val open = actionStartActivity(ToolWidgets.openIntent(context,input.tool?.id,input.generation))
    val clickableOpen = open.takeIf { widgetId != null }
    val titleStyle = TextStyle(color = colors.onSurface,fontSize = 18.sp,fontWeight = FontWeight.Bold)
    val textStyle = TextStyle(color = colors.onSurface,fontSize = 14.sp)
    Column(GlanceModifier.fillMaxSize().appWidgetBackground().widgetBackground(colors).padding(12.dp)) {
        if (showTitle) {
            Text(projection?.title ?: context.getString(R.string.app_name),style = titleStyle,maxLines = 1,
                modifier = GlanceModifier.fillMaxWidth().height(titleHeight.dp).clickIf(clickableOpen))
        }
        Column(GlanceModifier.fillMaxWidth().defaultWeight()) {
            if (projection == null) {
                val message = when {
                    input.locked -> R.string.widget_locked
                    input.tool?.archived == true -> R.string.widget_archived
                    input.deleted -> R.string.widget_deleted
                    else -> R.string.widget_unavailable
                }
                Text(context.getString(message),style = textStyle,maxLines = 2)
            } else {
                selected.forEach { node ->
                    WidgetNode(node,projection.state,checkNotNull(input.tool),widgetId,rowHeight,clickableOpen,colors,input.generation)
                }
            }
        }
        Row(GlanceModifier.fillMaxWidth().height(48.dp),verticalAlignment = Alignment.CenterVertically) {
            Button(
                text = if (hidden > 0) context.getString(R.string.widget_open_more,hidden) else context.getString(R.string.widget_open),
                onClick = open,
                modifier = GlanceModifier.defaultWeight().height(48.dp).padding(horizontal = 6.dp).widgetControlShape(),
                enabled = widgetId != null,
                colors = ButtonDefaults.buttonColors(colors.secondaryContainer,colors.onSecondaryContainer),
                maxLines = 1,
            )
            if (widgetId != null && !input.locked && (size.width >= 220.dp || projection == null)) {
                val configure = Intent(context,WidgetConfigurationActivity::class.java)
                    .setData(Uri.parse("alpha-widget://configure/$widgetId"))
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widgetId)
                Button("⚙",actionStartActivity(configure),
                    modifier = GlanceModifier.width(48.dp).height(48.dp).widgetControlShape()
                        .semantics { contentDescription = context.getString(R.string.widget_settings) },
                    colors = ButtonDefaults.buttonColors(colors.secondaryContainer,colors.onSecondaryContainer),
                    maxLines = 1)
            }
        }
    }
}

@Composable
private fun WidgetNode(
    node: JSONObject,
    state: JSONObject,
    tool: ToolRecord,
    widgetId: Int?,
    rowHeight: Int,
    open: Action?,
    colors: WidgetColors,
    generation: String,
) {
    val context = LocalContext.current
    val row = GlanceModifier.fillMaxWidth().height(rowHeight.dp)
    val style = TextStyle(color = colors.onSurface,fontSize = 14.sp)
    val enabled = widgetId != null && node.widgetActionId() != null
    val label = widgetText(context,node.optString("text"))
    when (node.getString("type")) {
        "button" -> Button(label,
            actionSendBroadcast(ToolWidgets.actionIntent(context,widgetId ?: 0,tool,node.getString("action"),null,generation)),
            modifier = row.widgetControlShape(),enabled = enabled,maxLines = 1,
            colors = ButtonDefaults.buttonColors(colors.primary,colors.onPrimary))
        "toggle" -> {
            val checked = ToolUiCatalog.value(state,node.getString("bind")) as Boolean
            val description = context.getString(if (checked) R.string.widget_toggle_on else R.string.widget_toggle_off,label)
            if (enabled) {
                val action = actionSendBroadcast(
                    ToolWidgets.actionIntent(context,checkNotNull(widgetId),tool,node.getString("action"),!checked,generation))
                // A plain click keeps the toggle payload in the immutable action token.
                Switch(checked,onCheckedChange = null,modifier = row.clickable(action).semantics {
                    contentDescription = description
                },text = label,style = style,maxLines = 1,
                    colors = SwitchDefaults.colors(colors.onPrimary,colors.onSurface,colors.primary,colors.secondaryContainer))
            } else {
                Column(row,verticalAlignment = Alignment.CenterVertically) { Text(description,style = style,maxLines = 2) }
            }
        }
        "progress", "progressRing" -> {
            Column(row.clickIf(open),verticalAlignment = Alignment.CenterVertically) {
                val bar = GlanceModifier.fillMaxWidth().height(6.dp)
                if (node.optBoolean("indeterminate")) {
                    Text(label,style = style,maxLines = 1)
                    LinearProgressIndicator(bar,color = colors.primary,backgroundColor = colors.secondaryContainer)
                } else {
                    val (min,max) = ToolUiCatalog.bounds(node)
                    val value = ToolUiCatalog.value(state,node.getString("bind")) as Number
                    Text(ToolUiCatalog.widgetLabel(node,state),style = style,maxLines = 1)
                    LinearProgressIndicator(((value.toDouble() - min) / (max - min)).toFloat(),bar,
                        color = colors.primary,backgroundColor = colors.secondaryContainer)
                }
            }
        }
        else -> Column(row.clickIf(open),verticalAlignment = Alignment.CenterVertically) {
            Text(widgetLabel(context,node,state),style = style,maxLines = 2)
        }
    }
}

private fun GlanceModifier.clickIf(action: Action?): GlanceModifier =
    if (action == null) this else clickable(action)

private fun widgetText(context: Context, text: String): String {
    val safe = ToolUiCatalog.widgetText(text)
    return if (safe == text) text else safe.replace("Selected resource",context.getString(R.string.widget_selected_resource))
}

private fun widgetLabel(context: Context, node: JSONObject, state: JSONObject): String {
    val label = ToolUiCatalog.widgetLabel(node,state)
    val detail = label.removeSuffix(" · Open in Alphy")
    if (detail == label || (node.has("enabled") && label == ToolUiCatalog.display(node,state))) return label
    val localized = when (node.getString("type")) {
        "calendar" -> context.getString(R.string.widget_calendar)
        "medicine" -> context.getString(R.string.widget_medicine)
        "navigation" -> context.getString(R.string.widget_navigation)
        else -> when {
            !node.has("enabled") && detail == "Interactive content" -> context.getString(R.string.widget_interactive_content)
            !node.has("enabled") && detail == "Animated content" -> context.getString(R.string.widget_animated_content)
            else -> widgetText(context,if (node.has("enabled")) node.optString("text") else detail)
        }
    }
    return context.getString(R.string.widget_open_content,localized)
}
