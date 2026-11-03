package com.alpha.assistant.tools

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import com.alpha.assistant.ui.AlphaIcon
import com.alpha.assistant.ui.Glyph
import com.alpha.assistant.capabilities.OwnedCalendarUiHost
import com.alpha.assistant.capabilities.OwnedCalendarSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.round

@Composable internal fun ToolContent(ui: String, state: String, enabled: Boolean,
    heading: String? = null, imageVersion: Any? = null, imageLoader: suspend (String) -> Bitmap? = {null}, imageAvailable: suspend (String) -> Boolean = {false}, calendarHost: OwnedCalendarUiHost? = null, event: (String,Any?) -> Unit) {
    val root=remember(ui) {JSONObject(ui)}
    val values=remember(state) {JSONObject(state)}
    val withinLimits=remember(ui,state) {runCatching {ToolUiCatalog.validateRenderedWork(root,values)}.isSuccess}
    if(!withinLimits) {Text("Mini-app UI exceeds native rendering limits. Change this mini-app to show less content."); return}
    Column(Modifier.fillMaxWidth()) {
        NativeNode(root,values,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading) { id,payload ->
            if(enabled && runCatching {ToolUiCatalog.validateAction(root,id,payload,values)}.isSuccess) event(id,payload)
        }
    }
}
private fun space(value: String?, default: Int = 0) = when(value) {"none" -> 0; "small" -> 6; "medium" -> 12; "large" -> 20; else -> default}.dp
private fun alignment(style: JSONObject?) = when(style?.optString("align")) {"center" -> Alignment.CenterHorizontally; "end" -> Alignment.End; else -> Alignment.Start}
@Composable private fun tone(style: JSONObject?) = when(style?.optString("tone")) {
    "muted" -> MaterialTheme.colorScheme.onSurfaceVariant
    "accent" -> MaterialTheme.colorScheme.primary
    "success" -> MaterialTheme.colorScheme.tertiary
    "warning" -> MaterialTheme.colorScheme.onTertiaryContainer
    "error" -> MaterialTheme.colorScheme.error
    else -> LocalContentColor.current
}
@Composable private fun textStyle(style: JSONObject?, metric: Boolean = false) = when(style?.optString("emphasis")) {
    "title" -> MaterialTheme.typography.titleLarge
    "headline" -> MaterialTheme.typography.headlineMedium
    "label" -> MaterialTheme.typography.labelLarge
    "number" -> MaterialTheme.typography.displaySmall
    else -> if(metric) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.bodyLarge
}
@Composable private fun nativeLayout(node: JSONObject,placement: Modifier,compact: Boolean): Modifier {
    val layout=node.optJSONObject("layout") ?: JSONObject()
    var result=placement
    result=result.widthIn(min=if(layout.has("minWidth")) layout.getInt("minWidth").dp else Dp.Unspecified,max=if(layout.has("maxWidth")) layout.getInt("maxWidth").dp else Dp.Unspecified)
        .heightIn(min=if(layout.has("minHeight")) layout.getInt("minHeight").dp else Dp.Unspecified,max=if(layout.has("maxHeight")) layout.getInt("maxHeight").dp else Dp.Unspecified)
    if(layout.has("width")) result=result.width(layout.getInt("width").dp)
    else if(layout.optBoolean("fillWidth",!compact)) result=result.fillMaxWidth()
    else if(compact) result=result.widthIn(max=300.dp)
    if(layout.has("height")) result=result.height(layout.getInt("height").dp)
    if(layout.has("aspectRatio")) result=result.aspectRatio(layout.getDouble("aspectRatio").toFloat())
    result=when(layout.optString("insets")) {
        "safeDrawing" -> result.windowInsetsPadding(WindowInsets.safeDrawing)
        "ime" -> result.imePadding()
        "safeDrawingAndIme" -> result.windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
        else -> result
    }
    return result
}
private fun boxAlignment(value: String?)=when(value) {"center" -> Alignment.Center; "end" -> Alignment.BottomEnd; else -> Alignment.TopStart}
private fun verticalAlignment(value: String?)=when(value) {"center" -> Alignment.CenterVertically; "end" -> Alignment.Bottom; else -> Alignment.Top}
private fun horizontalArrangement(style: JSONObject?,gap: Dp): Arrangement.Horizontal=when(style?.optString("arrangement")) {
    "center" -> Arrangement.spacedBy(gap,Alignment.CenterHorizontally)
    "end" -> Arrangement.spacedBy(gap,Alignment.End)
    "between" -> Arrangement.SpaceBetween
    "around" -> Arrangement.SpaceAround
    "evenly" -> Arrangement.SpaceEvenly
    else -> Arrangement.spacedBy(gap)
}
private fun verticalArrangement(style: JSONObject?,gap: Dp): Arrangement.Vertical=when(style?.optString("arrangement")) {
    "center" -> Arrangement.spacedBy(gap,Alignment.CenterVertically)
    "end" -> Arrangement.spacedBy(gap,Alignment.Bottom)
    "between" -> Arrangement.SpaceBetween
    "around" -> Arrangement.SpaceAround
    "evenly" -> Arrangement.SpaceEvenly
    else -> Arrangement.spacedBy(gap)
}
@Composable private fun NativeReadableText(node: JSONObject,label: String,enabled: Boolean,modifier: Modifier,event: (String,Any?) -> Unit) {
    val style=node.optJSONObject("style")
    val spans=node.optJSONArray("spans")
    val annotated=AnnotatedString.Builder(label)
    if(spans != null) repeat(spans.length()) {index ->
        val span=spans.getJSONObject(index); val start=span.getInt("start"); val end=span.getInt("end")
        annotated.addStyle(SpanStyle(color=if(span.has("tone")) tone(JSONObject().put("tone",span.getString("tone"))) else Color.Unspecified,
            fontWeight=if(span.optString("emphasis") == "bold") FontWeight.Bold else null,
            fontStyle=if(span.optString("emphasis") == "italic") FontStyle.Italic else null,
            fontFamily=if(span.optString("emphasis") == "code") FontFamily.Monospace else null),start,end)
        if(span.has("action")) {
            val linkStyle=TextLinkStyles(style=SpanStyle(color=if(enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,textDecoration=TextDecoration.Underline))
            annotated.addLink(LinkAnnotation.Clickable(span.getString("action"),linkStyle) {if(enabled) event(span.getString("action"),span.getString("url"))},start,end)
        }
    }
    val selectable=node.optBoolean("selectable")
    val textModifier=if(selectable) Modifier else modifier
    val content: @Composable () -> Unit = {
        Text(annotated.toAnnotatedString(),if(!enabled && spans != null) textModifier.semantics {disabled()} else textModifier,style=textStyle(style),color=tone(style),
            textAlign=when(style?.optString("align")) {"center" -> TextAlign.Center; "end" -> TextAlign.End; else -> TextAlign.Start},
            maxLines=node.optInt("maxLines",Int.MAX_VALUE),softWrap=node.optBoolean("softWrap",true),
            overflow=if(node.optString("overflow") == "ellipsis") TextOverflow.Ellipsis else TextOverflow.Clip,
            lineHeight=if(node.has("lineHeight")) node.getDouble("lineHeight").sp else androidx.compose.ui.unit.TextUnit.Unspecified)
    }
    if(selectable) SelectionContainer(modifier=modifier) {content()} else content()
}
@OptIn(ExperimentalLayoutApi::class,ExperimentalMaterial3Api::class)
@Composable private fun NativeNode(node: JSONObject, state: JSONObject, parentEnabled: Boolean, imageVersion: Any?,
    imageLoader: suspend (String) -> Bitmap?, imageAvailable: suspend (String) -> Boolean, calendarHost: OwnedCalendarUiHost?, compact: Boolean, heading: String?, placement: Modifier = Modifier, event: (String,Any?) -> Unit) {
    if(!ToolUiCatalog.visible(node,state)) return
    val type=node.getString("type")
    val definition=ToolUiCatalog.component(type)
    val enabled=parentEnabled && ToolUiCatalog.enabled(node,state)
    val text=node.optString("text")
    val style=node.optJSONObject("style")
    val binding=node.optString("bind")
    val modifier=nativeInteractions(node,enabled,event,nativeLayout(node,placement,compact || type in setOf("spacer","icon")).padding(space(style?.optString("padding"))))
    val gap=space(style?.optString("gap"),12)
    val action=node.optString("action")
    val children=node.optJSONArray("children")
    val content: @Composable () -> Unit = {
        if(children != null) repeat(children.length()) { NativeNode(children.getJSONObject(it),state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading,event=event) }
    }
    when(definition.type) {
        "appBar" -> TopAppBar(title={Text(text)},modifier=modifier,actions={content()})
        "bottomBar" -> BottomAppBar(modifier=modifier) {content()}
        "dialog" -> AlertDialog(onDismissRequest={if(enabled)event(node.getString("dismissAction"),null)},title={Text(text)},
            text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {content()}},
            confirmButton={TextButton(onClick={event(node.getString("dismissAction"),null)},enabled=enabled) {Text("Close: $text")}})
        "bottomSheet" -> ModalBottomSheet(onDismissRequest={if(enabled)event(node.getString("dismissAction"),null)},sheetGesturesEnabled=enabled) {
            Column(modifier.heightIn(max=640.dp).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(text,style=MaterialTheme.typography.titleLarge)
                content()
                TextButton(onClick={event(node.getString("dismissAction"),null)},enabled=enabled) {Text("Close: $text")}
            }
        }
        "transition" -> NativeTransition(node,state,modifier) {content()}
        "pager" -> NativePager(node,state,enabled,modifier,event) {page ->
            NativeNode(checkNotNull(children).getJSONObject(page),state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading,event=event)
        }
        "theme" -> NativePreset(node) {
            Surface(modifier,color=MaterialTheme.colorScheme.surface,contentColor=MaterialTheme.colorScheme.onSurface) {
                Column(Modifier.fillMaxWidth(),verticalArrangement=verticalArrangement(style,gap),horizontalAlignment=alignment(style),content={content()})
            }
        }
        "motion" -> NativeMotion(node,state,modifier) {content()}
        "transform" -> NativeTransform(node,state,modifier) {content()}
        "stage" -> NativeStage(node,state,modifier) {leaf -> NativeNode(leaf,state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading,event=event)}
        "column","form" -> Column(modifier,verticalArrangement=verticalArrangement(style,gap),horizontalAlignment=alignment(style)) {
            if(type == "form" && text.isNotEmpty()) Text(text,Modifier.semantics {heading()},style=MaterialTheme.typography.titleMedium)
            repeat(checkNotNull(children).length()) {index ->
                val child=children.getJSONObject(index); val layout=child.optJSONObject("layout")
                var slot: Modifier=Modifier
                if(type == "column") {
                    if(layout?.has("weight") == true) slot=slot.weight(layout.getDouble("weight").toFloat())
                    if(layout?.has("align") == true) slot=slot.align(alignment(JSONObject().put("align",layout.getString("align"))))
                }
                NativeNode(child,state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading,placement=slot,event=event)
            }
        }
        "box" -> Box(modifier,contentAlignment=boxAlignment(style?.optString("align"))) {
            repeat(checkNotNull(children).length()) {index ->
                val child=children.getJSONObject(index)
                val slot=child.optJSONObject("layout")?.optString("align")?.takeIf {it.isNotEmpty()}?.let {Modifier.align(boxAlignment(it))} ?: Modifier
                NativeNode(child,state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,true,heading,placement=slot,event=event)
            }
        }
        "row","flowRow" -> if(type == "row" && !node.optBoolean("wrap",true)) {
            Row(modifier,horizontalArrangement=horizontalArrangement(style,gap),verticalAlignment=verticalAlignment(style?.optString("crossAlign"))) {
                repeat(checkNotNull(children).length()) {index ->
                    val child=children.getJSONObject(index); val layout=child.optJSONObject("layout")
                    var slot: Modifier=Modifier
                    if(layout?.has("weight") == true) slot=slot.weight(layout.getDouble("weight").toFloat())
                    if(layout?.has("align") == true) slot=slot.align(verticalAlignment(layout.getString("align")))
                    NativeNode(child,state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,true,heading,placement=slot,event=event)
                }
            }
        } else FlowRow(modifier,horizontalArrangement=horizontalArrangement(style,gap),verticalArrangement=Arrangement.spacedBy(gap)) {
            repeat(checkNotNull(children).length()) {index ->
                NativeNode(children.getJSONObject(index),state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,true,heading,placement=Modifier.align(verticalAlignment(style?.optString("crossAlign"))),event=event)
            }
        }
        "grid" -> BoxWithConstraints(modifier) {
            val columns=minOf(node.optInt("columns",2),(maxWidth.value/node.optInt("minCellWidth",144)).toInt().coerceAtLeast(1))
            Column(verticalArrangement=Arrangement.spacedBy(gap)) {
                val count=checkNotNull(children).length()
                for(start in 0 until count step columns) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(gap)) {
                    repeat(columns) {offset -> Box(Modifier.weight(1f)) {
                        if(start+offset < count) NativeNode(children.getJSONObject(start+offset),state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading,event=event)
                    }}
                }
            }
        }
        "scroll" -> Column(modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(gap)) {content()}
        "facts" -> BoxWithConstraints(modifier) {
            val columns=if(maxWidth >= 480.dp) 2 else 1
            Column(verticalArrangement=Arrangement.spacedBy(gap)) {
                if(text.isNotEmpty()) Text(text,Modifier.semantics {heading()},style=MaterialTheme.typography.titleMedium)
                val items=checkNotNull(children)
                for(start in 0 until items.length() step columns) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(gap)) {
                    repeat(columns) {offset -> Box(Modifier.weight(1f)) {if(start+offset < items.length()) NativeNode(items.getJSONObject(start+offset),state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading,event=event)}}
                }
            }
        }
        "timeline","listGroup" -> Column(modifier,verticalArrangement=Arrangement.spacedBy(if(type == "listGroup") 0.dp else gap)) {
            if(text.isNotEmpty()) Text(text,Modifier.semantics {heading()},style=MaterialTheme.typography.titleMedium)
            repeat(checkNotNull(children).length()) {index ->
                if(type == "listGroup" && index>0) HorizontalDivider()
                NativeNode(children.getJSONObject(index),state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading,event=event)
            }
        }
        "timelineItem" -> Row(modifier,verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Column(horizontalAlignment=Alignment.CenterHorizontally) {
                Text(if(node.has("timeBind")) ToolUiCatalog.value(state,node.getString("timeBind")).toString() else node.optString("time"),style=MaterialTheme.typography.labelMedium)
                Box(Modifier.padding(top=8.dp).size(10.dp).background(MaterialTheme.colorScheme.primary,RoundedCornerShape(50)))
                Box(Modifier.width(2.dp).height(32.dp).background(MaterialTheme.colorScheme.outlineVariant))
            }
            val selection=if(action.isNotEmpty()) Modifier.clickable(enabled=enabled,role=Role.Button) {event(action,null)} else Modifier
            Column(selection.weight(1f).heightIn(min=48.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(ToolUiCatalog.display(node,state),style=MaterialTheme.typography.titleMedium)
                if(node.has("detail") || node.has("detailBind")) Text(if(node.has("detailBind")) ToolUiCatalog.value(state,node.getString("detailBind")).toString() else node.getString("detail"),color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        "fact" -> BoxWithConstraints(modifier) {
            val value=if(binding.isNotEmpty()) ToolUiCatalog.display(node,state).removePrefix("$text: ") else ""
            if(maxWidth < 220.dp) Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(text,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value,style=MaterialTheme.typography.bodyLarge)
            } else Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(text,Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge,textAlign=TextAlign.End)
            }
        }
        "banner","emptyState" -> Surface(modifier,shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.secondaryContainer) {
            Column(Modifier.padding(if(type == "emptyState") 24.dp else 16.dp),horizontalAlignment=if(type == "emptyState") Alignment.CenterHorizontally else Alignment.Start,verticalArrangement=Arrangement.spacedBy(gap)) {
                if(text.isNotEmpty()) Text(text,style=MaterialTheme.typography.titleMedium)
                content()
            }
        }
        "card","section","listItem" -> {
            val cardModifier=if(action.isNotEmpty()) modifier.heightIn(min=48.dp).clickable(enabled=enabled,role=Role.Button) {event(action,null)} else modifier
            Card(cardModifier,shape=MaterialTheme.shapes.medium,colors=CardDefaults.cardColors(containerColor=when(style?.optString("tone")) {
                "accent" -> MaterialTheme.colorScheme.primaryContainer
                "muted" -> MaterialTheme.colorScheme.surfaceVariant
                "warning" -> MaterialTheme.colorScheme.tertiaryContainer
                "error" -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            })) {
                Column(Modifier.padding(if(type == "listItem") 12.dp else 16.dp),verticalArrangement=Arrangement.spacedBy(gap),horizontalAlignment=alignment(style)) {
                    if(text.isNotEmpty() && !text.equals(heading,ignoreCase=true)) Text(text,Modifier.semantics {heading()},style=MaterialTheme.typography.titleLarge)
                    content()
                }
            }
        }
        "spacer" -> Spacer(modifier.size(node.optInt("size",12).dp))
        "text","metric","badge" -> {
            val label=ToolUiCatalog.display(node,state)
            val align=when(style?.optString("align")) {"center" -> TextAlign.Center; "end" -> TextAlign.End; else -> TextAlign.Start}
            if(type == "badge") Surface(modifier,shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.secondaryContainer) {
                Text(label,Modifier.padding(horizontal=10.dp,vertical=6.dp),style=MaterialTheme.typography.labelLarge,textAlign=align)
            } else if(type == "metric" && node.has("bind")) Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(text,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(label.removePrefix("$text: "),style=textStyle(style,true),color=tone(style),textAlign=align)
            } else if(node.has("bind") || !text.equals(heading,ignoreCase=true)) {
                if(type == "text") NativeReadableText(node,label,enabled,modifier,event)
                else Text(label,modifier,style=textStyle(style),color=tone(style),textAlign=align)
            }
        }
        "calendar" -> {
            if(node.getString("capability")=="calendar.deleteAllOwned") {
                val snapshot by produceState<OwnedCalendarSnapshot?>(null,calendarHost,imageVersion) {value=null;value=calendarHost?.snapshot()}
                Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Delete every calendar event this app added",style=MaterialTheme.typography.titleSmall)
                    if(snapshot==null)Text("Open the current app to review its native owned-event snapshot.")
                    snapshot?.let {selected ->
                        Text("${selected.entries.size} owned events in this snapshot. Events added after this click remain separate. Uncertain events keep their saved ownership records.")
                        if(selected.entries.isNotEmpty())Column(Modifier.fillMaxWidth().heightIn(max=240.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            selected.entries.forEach {entry ->
                                val args=JSONObject(entry.details.toString()).put("version",1).put("eventRef",entry.eventRef)
                                Text(com.alpha.assistant.capabilities.OwnedCalendarContract.label(com.alpha.assistant.capabilities.CapabilityRequest("selected","calendar.deleteOwned",args)))
                            }
                        }
                        Button(onClick={calendarHost?.deleteAll(action,selected)},enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                            Text("Delete all ${selected.entries.size} owned events")
                        }
                    }
                }
            } else Button(onClick={event(action,null)},enabled=enabled,modifier=modifier.heightIn(min=48.dp)) {
                Text(com.alpha.assistant.capabilities.OwnedCalendarContract.label(com.alpha.assistant.capabilities.OwnedCalendarContract.request(node)))
            }
        }
        "medicine" -> Button(onClick={event(action,null)},enabled=enabled,modifier=modifier.heightIn(min=48.dp)) {
            Text(com.alpha.assistant.capabilities.MedicineContract.label(com.alpha.assistant.capabilities.MedicineContract.request(node)))
        }
        "navigation" -> Button(onClick={event(action,null)},enabled=enabled,modifier=modifier.heightIn(min=48.dp)) {
            Text(com.alpha.assistant.capabilities.NavigationContract.label(com.alpha.assistant.capabilities.NavigationContract.request(node)))
        }
        "button" -> {
            val label: @Composable () -> Unit = {
                if(node.has("icon")) {AlphaIcon(Glyph.valueOf(node.getString("icon")));Spacer(Modifier.width(8.dp))}
                Text(text)
            }
            val click={event(action,null)}
            val buttonModifier=modifier.heightIn(min=48.dp)
            when(node.optString("variant")) {
                "outlined" -> OutlinedButton(click,buttonModifier,enabled=enabled) {label()}
                "text" -> TextButton(click,buttonModifier,enabled=enabled) {label()}
                "tonal" -> FilledTonalButton(click,buttonModifier,enabled=enabled) {label()}
                "elevated" -> ElevatedButton(click,buttonModifier,enabled=enabled) {label()}
                "fab","extendedFab" -> if(enabled) {
                    if(node.getString("variant")=="fab") FloatingActionButton(click,modifier.semantics {contentDescription=text}) {AlphaIcon(Glyph.valueOf(node.getString("icon")))}
                    else ExtendedFloatingActionButton(click,buttonModifier) {label()}
                } else FilledTonalButton(click,buttonModifier,enabled=false) {label()}
                else -> Button(click,buttonModifier,enabled=enabled) {label()}
            }
        }
        "actionChip" -> if(node.optString("variant")=="suggestion") SuggestionChip(onClick={event(action,null)},label={Text(text)},enabled=enabled,modifier=modifier.heightIn(min=48.dp))
            else AssistChip(onClick={event(action,null)},label={Text(text)},enabled=enabled,modifier=modifier.heightIn(min=48.dp))
        "icon" -> {
            val icon: @Composable () -> Unit = {AlphaIcon(Glyph.valueOf(node.getString("name")),Modifier.semantics {contentDescription=text})}
            if(action.isNotEmpty()) IconButton(onClick={event(action,null)},enabled=enabled,modifier=modifier.sizeIn(minWidth=48.dp,minHeight=48.dp)) {icon()}
            else Box(modifier) {icon()}
        }
        "toggle","checkbox" -> Row(modifier.heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
            val checked=ToolUiCatalog.value(state,binding) as Boolean
            val label=Modifier.semantics {contentDescription=text}
            if(type == "toggle") Switch(checked,{event(action,it)},label,enabled=enabled)
            else Checkbox(checked,{event(action,it)},label,enabled=enabled)
            Text(text,Modifier.padding(start=10.dp).weight(1f))
        }
        "chip" -> {
            val checked=ToolUiCatalog.value(state,binding) as Boolean
            val dismiss: (@Composable () -> Unit)?=node.optString("dismissAction").takeIf {it.isNotEmpty()}?.let {id -> {IconButton(onClick={event(id,null)},enabled=enabled,modifier=Modifier.size(48.dp)) {AlphaIcon(Glyph.Close,Modifier.semantics {contentDescription="Remove: $text"})}}}
            if(node.optString("variant")=="input") InputChip(selected=checked,onClick={event(action,!checked)},label={Text(text)},trailingIcon=dismiss,enabled=enabled,modifier=modifier.heightIn(min=48.dp))
            else FilterChip(selected=checked,onClick={event(action,!checked)},label={Text(text)},trailingIcon=dismiss,enabled=enabled,modifier=modifier.heightIn(min=48.dp))
        }
        "rangeSlider" -> {
            val original=ToolUiCatalog.value(state,binding) as JSONArray;val (low,high)=ToolUiCatalog.bounds(node)
            var range by remember(original.toString(),binding) {mutableStateOf(original.getDouble(0)..original.getDouble(1))}
            Column(modifier) {
                Text("$text: ${range.start} – ${range.endInclusive}")
                RangeSlider(value=((range.start-low)/(high-low)).toFloat().coerceIn(0f,1f)..((range.endInclusive-low)/(high-low)).toFloat().coerceIn(0f,1f),
                    onValueChange={range=nativeSliderValue(node,it.start)..nativeSliderValue(node,it.endInclusive)},enabled=enabled,modifier=Modifier.semantics {contentDescription=text})
                TextButton(onClick={event(action,JSONArray().put(range.start).put(range.endInclusive))},enabled=enabled && (range.start!=original.getDouble(0) || range.endInclusive!=original.getDouble(1))) {Text("Apply: $text")}
            }
        }
        "dateRangeInput" -> NativeDateRange(node,state,enabled,modifier,event)
        "singleChoice","multiChoice","segmented","navigationBar","navigationRail","menu" -> NativeChoices(node,state,enabled,modifier,event)
        "input","numberInput","search" -> NativeInput(node,state,enabled,modifier,event)
        "timeInput","dateInput" -> {
            val context=LocalContext.current
            val current=ToolUiCatalog.value(state,binding)
            val label=if(type == "timeInput") (current as Number).toInt().let {"%02d:%02d".format(java.util.Locale.ROOT,it/60,it%60)} else current.toString()
            OutlinedButton(onClick={
                if(type == "timeInput") {
                    val minutes=(current as Number).toInt()
                    TimePickerDialog(context,{_,hour,minute -> event(action,hour*60+minute)},minutes/60,minutes%60,android.text.format.DateFormat.is24HourFormat(context)).show()
                } else {
                    val date=LocalDate.parse(current.toString())
                    DatePickerDialog(context,{_,year,month,day -> event(action,LocalDate.of(year,month+1,day).toString())},date.year,date.monthValue-1,date.dayOfMonth).show()
                }
            },enabled=enabled,modifier=modifier.heightIn(min=48.dp)) {Text("$text: $label")}
        }
        "slider" -> {
            val (low,high)=ToolUiCatalog.bounds(node)
            val original=(ToolUiCatalog.value(state,binding) as Number).toDouble()
            var value by remember(original,binding) {mutableDoubleStateOf(original)}
            Column(modifier) {
                Text("$text: $value")
                Slider(value=((value-low)/(high-low)).toFloat().coerceIn(0f,1f),onValueChange={ fraction ->
                    value=nativeSliderValue(node,fraction)
                },enabled=enabled,modifier=Modifier.semantics {contentDescription=text})
                TextButton(onClick={event(action,value)},enabled=enabled && value != original) {Text("Apply: $text")}
            }
        }
        "select","tabs" -> {
            val options=node.getJSONArray("options"); val selected=ToolUiCatalog.value(state,binding)
            Column(modifier,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(text,style=MaterialTheme.typography.labelLarge)
                if(type == "tabs") ScrollableTabRow(
                    selectedTabIndex=(0 until options.length()).first {scalarEqual(selected,options.getJSONObject(it).get("value"))},
                    modifier=Modifier.fillMaxWidth(),edgePadding=0.dp) {
                    repeat(options.length()) {index -> val option=options.getJSONObject(index); val value=option.get("value")
                        Tab(selected=scalarEqual(selected,value),onClick={event(action,value)},text={Text(option.getString("text"))},enabled=enabled,
                            modifier=Modifier.heightIn(min=48.dp).semantics(mergeDescendants=true) {contentDescription=option.getString("text");role=Role.Tab;this.selected=scalarEqual(selected,value)})
                    }
                } else {
                    var expanded by remember {mutableStateOf(false)}
                    Box {
                        OutlinedButton(onClick={expanded=true},enabled=enabled,modifier=Modifier.heightIn(min=48.dp)) {
                            val option=(0 until options.length()).map {options.getJSONObject(it)}.first {scalarEqual(it.get("value"),selected)}
                            Text(option.getString("text"))
                        }
                        DropdownMenu(expanded && enabled,onDismissRequest={expanded=false}) {
                            repeat(options.length()) {index -> val option=options.getJSONObject(index)
                                DropdownMenuItem(text={Text(option.getString("text"))},onClick={expanded=false; event(action,option.get("value"))})
                            }
                        }
                    }
                }
            }
        }
        "list","table","groupedList","lazyList","lazyGrid" -> NativeRecords(node,state,enabled,modifier,heading,event=event) {template,row,dispatch ->
            NativeNode(template,row,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading,event=dispatch)
        }
        "progress","progressRing" -> {
            val (low,high)=ToolUiCatalog.bounds(node)
            val progress=if(binding.isNotEmpty()) (((ToolUiCatalog.value(state,binding) as Number).toDouble()-low)/(high-low)).toFloat().coerceIn(0f,1f) else 0f
            Column(modifier,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(ToolUiCatalog.display(node,state))
                if(node.optBoolean("indeterminate")) {
                    if(type == "progress") LinearProgressIndicator(modifier=Modifier.fillMaxWidth().semantics {contentDescription=text})
                    else CircularProgressIndicator(modifier=Modifier.size(56.dp).semantics {contentDescription=text})
                } else if(type == "progress") LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth().semantics {contentDescription=text})
                else CircularProgressIndicator(progress={progress},modifier=Modifier.size(56.dp).semantics {contentDescription=text})
            }
        }
        "chart" -> NativeChart(node,state,modifier,heading)
        "image" -> {
            val handle=if(binding.isNotEmpty()) ToolUiCatalog.value(state,binding).toString() else node.getString("resourceId")
            val loader by rememberUpdatedState(imageLoader)
            val available by rememberUpdatedState(imageAvailable)
            val lifecycle=LocalLifecycleOwner.current.lifecycle
            key(handle,imageVersion,lifecycle) {
                val image by produceState<Bitmap?>(null) {
                    observeNativeImage(lifecycle,available={available(handle)},load={loader(handle)},display={value=it})
                }
                if(image == null) Surface(modifier.height(160.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
                    Box(contentAlignment=Alignment.Center) {Text("$text · Image unavailable",Modifier.padding(16.dp))}
                } else Image(checkNotNull(image).asImageBitmap(),text,modifier.heightIn(min=80.dp,max=280.dp),contentScale=if(node.optString("fit") == "crop") ContentScale.Crop else ContentScale.Fit)
            }
        }
        "divider" -> HorizontalDivider(modifier)
        "skeleton" -> Box(modifier.height(24.dp).background(MaterialTheme.colorScheme.surfaceVariant,MaterialTheme.shapes.small).semantics {contentDescription=if(text.isEmpty()) "Loading" else text})
        "spinner" -> Box(modifier) {CircularProgressIndicator(modifier=Modifier.size(32.dp).semantics {contentDescription=if(text.isEmpty()) "Loading" else text})}
        "snackbar" -> Snackbar(modifier,action=if(action.isNotEmpty()) {{TextButton(onClick={event(action,null)},enabled=enabled) {Text("Dismiss")}}} else null) {Text(text)}
        "backHandler" -> BackHandler(enabled=enabled) {event(action,null)}
    }
}
/** Cache pixels only while resumed. Access metadata is checked each second without decoding again. */
internal suspend fun observeNativeImage(lifecycle: Lifecycle, available: suspend () -> Boolean,
    load: suspend () -> Bitmap?, display: (Bitmap?) -> Unit) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        var image: Bitmap?=null
        var attempted=false
        display(null)
        try {
            while(true) {
                if(!available()) {image=null; attempted=false; display(null)}
                else if(!attempted) {
                    attempted=true
                    val decoded=load()
                    currentCoroutineContext().ensureActive()
                    val stillAvailable=available()
                    attempted=stillAvailable
                    image=if(stillAvailable) decoded else null
                    display(image)
                }
                delay(1000)
            }
        } finally {display(null)}
    }
}
/** Native sliders use unit fractions. Keep declared endpoints and snapped state in Double precision. */
internal fun nativeSliderValue(node:JSONObject,fraction:Float):Double {
    val (low,high)=ToolUiCatalog.bounds(node)
    if(fraction <= 0f)return low
    if(fraction >= 1f)return high
    val proposed=low+fraction.toDouble()*(high-low)
    return (if(node.has("step")) low+round((proposed-low)/node.getDouble("step"))*node.getDouble("step") else proposed).coerceIn(low,high)
}
private fun scalarEqual(a: Any,b: Any) = if(a is Number && b is Number) a.toDouble() == b.toDouble() else a == b
@Composable private fun NativeInput(node: JSONObject, state: JSONObject, enabled: Boolean, modifier: Modifier, event: (String,Any?) -> Unit) {
    val binding=node.getString("bind"); val original=ToolUiCatalog.value(state,binding)
    val numeric=node.getString("type") == "numberInput"
    val search=node.getString("type") == "search"
    val keyboard=if(numeric) KeyboardType.Decimal else when(node.optString("keyboard")) {"email" -> KeyboardType.Email; "uri" -> KeyboardType.Uri; "phone" -> KeyboardType.Phone; "number" -> KeyboardType.Number; "password" -> KeyboardType.Password; else -> KeyboardType.Text}
    val ime=when(node.optString("imeAction",if(search) "search" else "done")) {"next" -> ImeAction.Next; "search" -> ImeAction.Search; else -> ImeAction.Done}
    var draft by remember(original,binding) {mutableStateOf(original.toString())}
    val payload=if(numeric) draft.toDoubleOrNull() else draft
    val valid=runCatching {ToolUiCatalog.validateAction(node,node.getString("action"),payload,state)}.isSuccess
    val changed=if(numeric) payload != (original as Number).toDouble() else payload != original
    val apply={if(enabled && valid && (changed || search)) event(node.getString("action"),payload)}
    Column(modifier) {
        OutlinedTextField(draft,{if(it.length <= if(numeric) 40 else node.optInt("maxLength",2048)) draft=it},
            enabled=enabled,label={Text(node.getString("text"))},modifier=Modifier.fillMaxWidth(),singleLine=numeric || node.optInt("maxLines",if(search) 1 else 8) == 1,maxLines=if(numeric) 1 else node.optInt("maxLines",if(search) 1 else 8),
            visualTransformation=if(node.optString("keyboard") == "password") PasswordVisualTransformation() else VisualTransformation.None,
            isError=draft.isNotEmpty() && !valid,keyboardOptions=KeyboardOptions(keyboardType=keyboard,imeAction=ime),
            keyboardActions=KeyboardActions(onDone={apply()},onSearch={apply()}))
        TextButton(onClick=apply,enabled=enabled && valid && (changed || search)) {Text("${if(search) "Search" else "Apply"}: ${node.getString("text")}")}
    }
}
private data class NativeRecordEntry(val key: String,val group: String? = null,val record: JSONObject? = null)
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun NativeRecords(node: JSONObject, state: JSONObject, enabled: Boolean, modifier: Modifier, heading: String?, event: (String,Any?) -> Unit, renderTemplate: @Composable (JSONObject,JSONObject,(String,Any?) -> Unit) -> Unit) {
    val rows=ToolUiCatalog.records(node,state); val type=node.getString("type")
    val columns=if(type == "lazyGrid") null else node.optJSONArray("columns"); val action=node.optString("action")
    val selected=if(node.has("selectedKeyBind")) ToolUiCatalog.value(state,node.getString("selectedKeyBind")) as String else ""
    val entries=if(type == "table") emptyList() else buildList {
        val groups=(0 until rows.length()).map {rows.getJSONObject(it)}.groupBy {if(node.has("groupBy")) ToolUiCatalog.value(it,node.getString("groupBy")).toString() else ""}
        groups.forEach {(group,records) ->
            if(node.has("groupBy")) add(NativeRecordEntry("group:$group",group=group))
            records.forEach {add(NativeRecordEntry("row:${ToolUiCatalog.stableKey(it)}",record=it))}
        }
    }
    val rowContent: @Composable (NativeRecordEntry) -> Unit = {entry ->
        if(entry.group != null) Text(entry.group,Modifier.padding(top=8.dp).semantics {heading()},style=MaterialTheme.typography.titleMedium)
        else entry.record?.let {record ->
            val selection=if(node.has("selectedKeyBind")) Modifier.semantics {this.selected=selected == ToolUiCatalog.stableKey(record)} else Modifier
            Box(selection.fillMaxWidth()) {NativeRecordRow(node,record,columns,enabled,event,renderTemplate)}
        }
    }
    Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(!node.getString("text").equals(heading,ignoreCase=true)) Text(node.getString("text"),style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics {heading()})
        if(rows.length() == 0) Text(node.optString("emptyText","No records on this page"))
        if(type == "table") {
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                Row {repeat(checkNotNull(columns).length()) {Text(columns.getJSONObject(it).getString("text"),Modifier.width(160.dp).padding(10.dp),style=MaterialTheme.typography.labelLarge)}}
                repeat(rows.length()) {index -> val record=rows.getJSONObject(index)
                    HorizontalDivider()
                    val rowModifier=if(action.isNotEmpty()) Modifier.clickable(enabled=enabled,role=Role.Button) {event(action,ToolUiCatalog.stableKey(record))} else Modifier
                    Row(rowModifier.heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
                        repeat(checkNotNull(columns).length()) {Text(ToolUiCatalog.value(record,columns.getJSONObject(it).getString("field")).toString(),Modifier.width(160.dp).padding(10.dp))}
                    }
                }
            }
        } else if(type == "lazyList" || type == "lazyGrid") {
            val target=if(node.has("scrollToKeyBind")) ToolUiCatalog.value(state,node.getString("scrollToKeyBind")) as String else ""
            val keys=entries.map {it.key}
            val targetIndex=if(target.isEmpty()) -1 else keys.indexOf("row:$target")
            val viewport=Modifier.fillMaxWidth().height(node.optInt("viewportHeight",360).dp)
            if(type == "lazyList") {
                val scroll=rememberLazyListState()
                LaunchedEffect(target,keys) {if(targetIndex >= 0) scroll.scrollToItem(targetIndex)}
                LazyColumn(viewport,state=scroll,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    entries.forEach {entry ->
                        if(entry.group != null && node.optBoolean("stickyHeaders")) stickyHeader(key=entry.key) {
                            Surface(Modifier.fillMaxWidth()) {rowContent(entry)}
                        } else item(key=entry.key) {rowContent(entry)}
                    }
                }
            } else BoxWithConstraints(viewport) {
                val count=minOf(node.optInt("columns",2),((maxWidth.value+8)/(node.optInt("minCellWidth",144)+8)).toInt().coerceAtLeast(1))
                val scroll=rememberLazyGridState()
                LaunchedEffect(target,keys) {if(targetIndex >= 0) scroll.scrollToItem(targetIndex)}
                LazyVerticalGrid(columns=GridCells.Fixed(count),state=scroll,horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    items(entries.size,key={entries[it].key},span={index ->
                        val entry=entries[index]
                        GridItemSpan(if(entry.group != null) maxLineSpan else if(node.has("spanField")) (ToolUiCatalog.value(checkNotNull(entry.record),node.getString("spanField")) as Number).toInt().coerceIn(1,maxLineSpan) else 1)
                    }) {index -> rowContent(entries[index])}
                }
            }
        } else entries.forEach {entry -> key(entry.key) {rowContent(entry)}}
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun NativeRecordRow(node: JSONObject,record: JSONObject,columns: JSONArray?,enabled: Boolean,event: (String,Any?) -> Unit,renderTemplate: @Composable (JSONObject,JSONObject,(String,Any?) -> Unit) -> Unit) {
    val template=node.optJSONObject("rowTemplate")
    if(template != null) {
        renderTemplate(template,record) {id,value -> event(id,JSONObject().put("key",ToolUiCatalog.stableKey(record)).put("value",value ?: JSONObject.NULL))}
        return
    }
    val action=node.optString("action")
    val selection=if(action.isNotEmpty()) Modifier.clickable(enabled=enabled,role=Role.Button) {event(action,ToolUiCatalog.stableKey(record))} else Modifier
    val swipe=node.optString("swipeAction")
    val latestEvent by rememberUpdatedState(event)
    val swipeEnabled=enabled && swipe.isNotEmpty() && node.optJSONArray("rowActions")?.let {actions -> (0 until actions.length()).map {actions.getJSONObject(it)}.firstOrNull {it.getString("action") == swipe}?.let {ToolUiCatalog.rowActionEnabled(it,record)}} == true
    val swipeModifier=if(swipeEnabled) Modifier.pointerInput(ToolUiCatalog.stableKey(record),swipe,swipeEnabled) {
        var distance=0f
        detectHorizontalDragGestures(onDragStart={distance=0f},onDragCancel={distance=0f},onDragEnd={if(abs(distance) >= 72.dp.toPx()) latestEvent(swipe,ToolUiCatalog.stableKey(record));distance=0f}) {change,delta -> change.consume();distance+=delta}
    } else Modifier
    Card(swipeModifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Column(selection.fillMaxWidth().heightIn(min=48.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if(columns != null) repeat(columns.length()) {column ->
                    val field=columns.getJSONObject(column)
                    Text(ToolUiCatalog.value(record,field.getString("field")).toString(),
                        style=if(column == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                        color=if(column == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val fields=if(record.has("key") && record.has("token") && record.has("value")) record.getJSONObject("value") else record
                    Text(fields.keys().asSequence().filter {it != "id"}.joinToString(" · ") {"$it: ${fields.get(it)}"},style=MaterialTheme.typography.bodyLarge)
                }
            }
            node.optJSONArray("rowActions")?.let {actions ->
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    repeat(actions.length()) {index ->
                        val rowAction=actions.getJSONObject(index)
                        val available=enabled && ToolUiCatalog.rowActionEnabled(rowAction,record)
                        val dispatch={event(rowAction.getString("action"),ToolUiCatalog.stableKey(record))}
                        if(rowAction.optString("emphasis") == "primary") Button(onClick=dispatch,enabled=available,modifier=Modifier.heightIn(min=48.dp)) {Text(rowAction.getString("text"))}
                        else OutlinedButton(onClick=dispatch,enabled=available,modifier=Modifier.heightIn(min=48.dp)) {Text(rowAction.getString("text"))}
                    }
                }
            }
        }
    }
}
@Composable private fun NativeChart(node: JSONObject, state: JSONObject, modifier: Modifier, heading: String?) {
    val categories=ToolUiCatalog.value(state,node.getString("categories")) as JSONArray
    val series=node.getJSONArray("series"); val range=ToolUiCatalog.chartRange(node,state)
    val colors=listOf(MaterialTheme.colorScheme.primary,MaterialTheme.colorScheme.tertiary)
    val axis=MaterialTheme.colorScheme.outlineVariant
    val description=buildString {
        append(node.getString("text")); append(". ")
        if(categories.length() == 0) append("No data.")
        repeat(series.length()) {s -> val entry=series.getJSONObject(s); val values=ToolUiCatalog.value(state,entry.getString("bind")) as JSONArray
            append(entry.getString("text")); append(": ")
            repeat(values.length()) {append(categories.get(it)); append(' '); append(values.get(it)); append("; ")}
        }
    }
    Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(!node.getString("text").equals(heading,ignoreCase=true)) Text(node.getString("text"),style=MaterialTheme.typography.titleMedium)
        if(categories.length() == 0) Text("No chart data")
        else {
            Text("${range.first} — ${range.second}",style=MaterialTheme.typography.labelSmall)
            Canvas(Modifier.fillMaxWidth().height(180.dp).semantics {contentDescription=description}) {
                fun y(value: Double) = ((1.0-(value-range.first)/(range.second-range.first))*size.height).toFloat().coerceIn(0f,size.height)
                val baseline=y(0.0)
                drawLine(axis,Offset(0f,baseline),Offset(size.width,baseline),strokeWidth=1.dp.toPx())
                repeat(series.length()) {s -> val values=ToolUiCatalog.value(state,series.getJSONObject(s).getString("bind")) as JSONArray
                    repeat(values.length()) {i ->
                        val current=y(values.getDouble(i))
                        if(node.getString("kind") == "bar") {
                            val group=size.width/values.length(); val width=group/(series.length()+1)
                            val x=i*group+(s+0.5f)*width
                            drawRect(colors[s],Offset(x,minOf(current,baseline)),androidx.compose.ui.geometry.Size(width*0.85f,maxOf(abs(baseline-current),1f)))
                        } else {
                            val x=if(values.length() == 1) size.width/2 else i.toFloat()/(values.length()-1)*size.width
                            if(i > 0) drawLine(colors[s],Offset((i-1).toFloat()/(values.length()-1)*size.width,y(values.getDouble(i-1))),Offset(x,current),strokeWidth=2.dp.toPx())
                            drawCircle(colors[s],3.dp.toPx(),Offset(x,current))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text(categories.get(0).toString(),Modifier.weight(1f),style=MaterialTheme.typography.labelSmall); if(categories.length()>1) Text(categories.get(categories.length()-1).toString(),Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,textAlign=TextAlign.End)}
            repeat(series.length()) {Text(series.getJSONObject(it).getString("text"),color=colors[it],style=MaterialTheme.typography.labelMedium)}
        }
    }
}
@Composable private fun NativePreset(node: JSONObject, content: @Composable () -> Unit) {
    val preset=node.getString("preset")
    val radius=if(node.has("cornerRadius")) node.getInt("cornerRadius").dp else when(preset) {"clean" -> 12; "soft" -> 24; "expressive" -> 28; "editorial" -> 4; "technical" -> 0; else -> 32}.dp
    val family=when(preset) {"editorial" -> FontFamily.Serif; "technical" -> FontFamily.Monospace; else -> FontFamily.Default}
    val typography=MaterialTheme.typography
    val baseColors=when(node.optString("mode")) {
        "light" -> lightColorScheme()
        "dark" -> darkColorScheme()
        "system" -> if(isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
        else -> MaterialTheme.colorScheme
    }
    val colors=if(preset in setOf("expressive","playful")) baseColors.copy(primary=baseColors.tertiary,primaryContainer=baseColors.tertiaryContainer,onPrimary=baseColors.onTertiary,onPrimaryContainer=baseColors.onTertiaryContainer) else baseColors
    val palette=node.optJSONObject("palette")
    fun color(name: String,fallback: Color)=palette?.optString(name)?.takeIf {it.isNotEmpty()}?.let {Color(android.graphics.Color.parseColor(it))} ?: fallback
    val surface=color("surface",colors.surface); val onSurface=color("onSurface",colors.onSurface)
    val customSurface=palette?.has("surface") == true
    val scale=node.optDouble("typeScale",1.0).toFloat()
    fun scaled(style: androidx.compose.ui.text.TextStyle)=style.copy(fontSize=style.fontSize*scale,lineHeight=style.lineHeight*scale,fontFamily=family)
    MaterialTheme(colorScheme=colors.copy(primary=color("primary",colors.primary),onPrimary=color("onPrimary",colors.onPrimary),primaryContainer=color("primaryContainer",colors.primaryContainer),onPrimaryContainer=color("onPrimaryContainer",colors.onPrimaryContainer),surface=surface,onSurface=onSurface,
        surfaceVariant=if(customSurface) surface else colors.surfaceVariant,onSurfaceVariant=if(customSurface) onSurface else colors.onSurfaceVariant,
        surfaceContainer=if(customSurface) surface else colors.surfaceContainer,surfaceContainerLow=if(customSurface) surface else colors.surfaceContainerLow,
        surfaceContainerLowest=if(customSurface) surface else colors.surfaceContainerLowest,surfaceContainerHigh=if(customSurface) surface else colors.surfaceContainerHigh,
        surfaceContainerHighest=if(customSurface) surface else colors.surfaceContainerHighest),
        shapes=Shapes(RoundedCornerShape(radius),RoundedCornerShape(radius),RoundedCornerShape(radius),RoundedCornerShape(radius),RoundedCornerShape(radius)),
        typography=typography.copy(displayLarge=scaled(typography.displayLarge),displayMedium=scaled(typography.displayMedium),displaySmall=scaled(typography.displaySmall),headlineLarge=scaled(typography.headlineLarge),headlineMedium=scaled(typography.headlineMedium),headlineSmall=scaled(typography.headlineSmall),titleLarge=scaled(typography.titleLarge),titleMedium=scaled(typography.titleMedium),titleSmall=scaled(typography.titleSmall),bodyLarge=scaled(typography.bodyLarge),bodyMedium=scaled(typography.bodyMedium),bodySmall=scaled(typography.bodySmall),labelLarge=scaled(typography.labelLarge),labelMedium=scaled(typography.labelMedium),labelSmall=scaled(typography.labelSmall)),content=content)
}

@Composable private fun nativeInteractions(node:JSONObject,enabled:Boolean,event:(String,Any?)->Unit,base:Modifier):Modifier {
    val interactions=ToolUiCatalog.interactionActions(node)
    val latestEvent by rememberUpdatedState(event)
    var focused by remember {mutableStateOf(false)}
    val semantics=node.optJSONObject("semantics")
    var result=base.semantics {
        semantics?.let {
            if(it.has("label"))contentDescription=it.getString("label")
            if(it.has("stateDescription"))stateDescription=it.getString("stateDescription")
            if(it.optBoolean("heading"))heading()
            if(it.has("liveRegion"))liveRegion=if(it.getString("liveRegion")=="assertive")LiveRegionMode.Assertive else LiveRegionMode.Polite
        }
        if(interactions.isNotEmpty()) {
            if(!enabled)disabled()
            customActions=listOfNotNull(interactions["longClick"]?.let {id->CustomAccessibilityAction("Long press: ${node.optString("text",semantics?.optString("label") ?: "Control")}") {if(enabled)latestEvent(id,null);enabled}},
                interactions["doubleClick"]?.let {id->CustomAccessibilityAction("Double tap: ${node.optString("text",semantics?.optString("label") ?: "Control")}") {if(enabled)latestEvent(id,null);enabled}})
        }
    }
    if(interactions.keys.any {it in setOf("click","longClick","doubleClick")}) result=result.combinedClickable(enabled=enabled,
        onClick={interactions["click"]?.let{latestEvent(it,null)}},onLongClick=interactions["longClick"]?.let{id->{latestEvent(id,null)}},
        onDoubleClick=interactions["doubleClick"]?.let{id->{latestEvent(id,null)}})
    if(interactions.isNotEmpty()) result=result.onFocusChanged {state ->
        if(focused != state.isFocused) {focused=state.isFocused;if(enabled)interactions["focus"]?.let{latestEvent(it,focused)}}
    }.onKeyEvent {key ->
        val name=when(key.key) {Key.Enter,Key.NumPadEnter->"enter";Key.Spacebar->"space";Key.Escape,Key.Back->"escape";Key.DirectionUp->"up";Key.DirectionDown->"down";Key.DirectionLeft->"left";Key.DirectionRight->"right";else->null}
        val id=interactions["key:$name"]
        if(enabled && id != null && key.type==KeyEventType.KeyUp) {latestEvent(id,null);true} else false
    }.focusable(enabled)
    return result
}

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable private fun NativeChoices(node:JSONObject,state:JSONObject,enabled:Boolean,modifier:Modifier,event:(String,Any?)->Unit) {
    val type=node.getString("type");val options=node.getJSONArray("options");val current=ToolUiCatalog.value(state,node.getString("bind"));val action=node.getString("action")
    val values=(0 until options.length()).map {options.getJSONObject(it)}
    fun selected(value:Any)=if(current is JSONArray)(0 until current.length()).any {scalarEqual(current.get(it),value)} else scalarEqual(current,value)
    fun choose(value:Any) {
        if(type != "multiChoice")event(action,value)
        else {val array=current as JSONArray;val next=JSONArray();repeat(array.length()){if(!scalarEqual(array.get(it),value))next.put(array.get(it))};if(!selected(value))next.put(value);event(action,next)}
    }
    Column(modifier,verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(node.getString("text"),style=MaterialTheme.typography.labelLarge)
        when(type) {
            "segmented" -> SingleChoiceSegmentedButtonRow(Modifier.horizontalScroll(rememberScrollState())) {
                values.forEachIndexed {index,option -> SegmentedButton(selected(option.get("value")),{choose(option.get("value"))},SegmentedButtonDefaults.itemShape(index,values.size),enabled=enabled) {Text(option.getString("text"))} }
            }
            "navigationBar" -> NavigationBar(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                values.forEach {option -> NavigationBarItem(selected(option.get("value")),{choose(option.get("value"))},icon={Text(option.getString("text").take(1))},label={Text(option.getString("text"))},enabled=enabled)}
            }
            "navigationRail" -> NavigationRail {
                values.forEach {option -> NavigationRailItem(selected(option.get("value")),{choose(option.get("value"))},icon={Text(option.getString("text").take(1))},label={Text(option.getString("text"))},enabled=enabled)}
            }
            "menu" -> {
                var expanded by remember {mutableStateOf(false)}
                Box {
                    OutlinedButton(onClick={expanded=true},enabled=enabled) {Text(values.first {selected(it.get("value"))}.getString("text"))}
                    DropdownMenu(expanded && enabled,{expanded=false}) {values.forEach {option->DropdownMenuItem(text={Text(option.getString("text"))},onClick={expanded=false;choose(option.get("value"))})}}
                }
            }
            "multiChoice" -> FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                values.forEach {option->FilterChip(selected(option.get("value")),{choose(option.get("value"))},label={Text(option.getString("text"))},enabled=enabled)}
            }
            else -> values.forEach {option->Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(enabled=enabled,role=Role.RadioButton){choose(option.get("value"))},verticalAlignment=Alignment.CenterVertically) {
                RadioButton(selected(option.get("value")),onClick=null,enabled=enabled);Text(option.getString("text"),Modifier.padding(start=8.dp))
            }}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun NativeDateRange(node:JSONObject,state:JSONObject,enabled:Boolean,modifier:Modifier,event:(String,Any?)->Unit) {
    val dates=ToolUiCatalog.value(state,node.getString("bind")) as JSONArray
    var open by remember {mutableStateOf(false)}
    val text=node.getString("text")
    OutlinedButton(onClick={open=true},enabled=enabled,modifier=modifier.heightIn(min=48.dp)) {Text("$text: ${dates.getString(0)} – ${dates.getString(1)}")}
    if(open && enabled) {
        val picker=rememberDateRangePickerState(initialSelectedStartDateMillis=LocalDate.parse(dates.getString(0)).toEpochDay()*86400000,initialSelectedEndDateMillis=LocalDate.parse(dates.getString(1)).toEpochDay()*86400000,yearRange=1..9999)
        androidx.compose.material3.DatePickerDialog(onDismissRequest={open=false},confirmButton={TextButton(onClick={
            val start=picker.selectedStartDateMillis;val end=picker.selectedEndDateMillis
            if(start != null && end != null) {event(node.getString("action"),JSONArray().put(LocalDate.ofEpochDay(Math.floorDiv(start,86400000L)).toString()).put(LocalDate.ofEpochDay(Math.floorDiv(end,86400000L)).toString()));open=false}
        },enabled=picker.selectedStartDateMillis!=null && picker.selectedEndDateMillis!=null) {Text("Apply: $text")}},dismissButton={TextButton(onClick={open=false}){Text("Cancel")}}) {
            DateRangePicker(picker,modifier=Modifier.heightIn(max=500.dp),title={Text(text,Modifier.padding(16.dp))})
        }
    }
}

@Composable private fun NativePager(node:JSONObject,state:JSONObject,enabled:Boolean,modifier:Modifier,event:(String,Any?)->Unit,render:@Composable (Int) -> Unit) {
    val count=node.getJSONArray("children").length();val current=(ToolUiCatalog.value(state,node.getString("bind")) as Number).toInt()
    val pages=rememberPagerState(initialPage=current,pageCount={count})
    val target by rememberUpdatedState(current);val active by rememberUpdatedState(enabled);val dispatch by rememberUpdatedState(event)
    var programmatic by remember {mutableStateOf(false)}
    LaunchedEffect(current,count) {programmatic=true;try {pages.scrollToPage(current)} finally {programmatic=false}}
    LaunchedEffect(pages) {snapshotFlow {pages.settledPage}.distinctUntilChanged().collect {if(active && !programmatic && it!=target)dispatch(node.getString("action"),it)}}
    Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("${node.getString("text")}: ${current+1} / $count",Modifier.semantics {stateDescription="Page ${current+1} of $count"})
        HorizontalPager(pages,Modifier.fillMaxWidth().height(node.optInt("viewportHeight",320).dp),userScrollEnabled=enabled,key={it}) {page->Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {render(page)}}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick={event(node.getString("action"),current-1)},enabled=enabled && current>0) {Text("Previous: ${node.getString("text")}")}
            TextButton(onClick={event(node.getString("action"),current+1)},enabled=enabled && current<count-1) {Text("Next: ${node.getString("text")}")}
        }
    }
}
