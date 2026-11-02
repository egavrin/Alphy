package com.alpha.assistant.tools

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
    else -> MaterialTheme.colorScheme.onSurface
}
@Composable private fun textStyle(style: JSONObject?, metric: Boolean = false) = when(style?.optString("emphasis")) {
    "title" -> MaterialTheme.typography.titleLarge
    "headline" -> MaterialTheme.typography.headlineMedium
    "label" -> MaterialTheme.typography.labelLarge
    "number" -> MaterialTheme.typography.displaySmall
    else -> if(metric) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.bodyLarge
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun NativeNode(node: JSONObject, state: JSONObject, parentEnabled: Boolean, imageVersion: Any?,
    imageLoader: suspend (String) -> Bitmap?, imageAvailable: suspend (String) -> Boolean, calendarHost: OwnedCalendarUiHost?, compact: Boolean, heading: String?, event: (String,Any?) -> Unit) {
    if(!ToolUiCatalog.visible(node)) return
    val type=node.getString("type")
    val definition=ToolUiCatalog.component(type)
    val enabled=parentEnabled && ToolUiCatalog.enabled(node)
    val text=node.optString("text")
    val style=node.optJSONObject("style")
    val binding=node.optString("bind")
    val modifier=(if(compact) Modifier.widthIn(max=300.dp) else Modifier.fillMaxWidth()).padding(space(style?.optString("padding")))
    val gap=space(style?.optString("gap"),12)
    val action=node.optString("action")
    val children=node.optJSONArray("children")
    val content: @Composable () -> Unit = {
        if(children != null) repeat(children.length()) { NativeNode(children.getJSONObject(it),state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading,event) }
    }
    when(definition.type) {
        "theme" -> NativePreset(node.getString("preset")) { Column(modifier,verticalArrangement=Arrangement.spacedBy(gap),horizontalAlignment=alignment(style),content={content()}) }
        "column" -> Column(modifier,verticalArrangement=Arrangement.spacedBy(gap),horizontalAlignment=alignment(style)) {content()}
        "row" -> FlowRow(modifier,horizontalArrangement=Arrangement.spacedBy(gap),verticalArrangement=Arrangement.spacedBy(gap)) {
            repeat(checkNotNull(children).length()) {NativeNode(children.getJSONObject(it),state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,true,heading,event)}
        }
        "grid" -> BoxWithConstraints(modifier) {
            val columns=minOf(node.optInt("columns",2),(maxWidth.value/144).toInt().coerceAtLeast(1))
            Column(verticalArrangement=Arrangement.spacedBy(gap)) {
                val count=checkNotNull(children).length()
                for(start in 0 until count step columns) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(gap)) {
                    repeat(columns) {offset -> Box(Modifier.weight(1f)) {
                        if(start+offset < count) NativeNode(children.getJSONObject(start+offset),state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading,event)
                    }}
                }
            }
        }
        "scroll" -> Column(modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(gap)) {content()}
        "card","section","listItem" -> {
            val cardModifier=if(action.isNotEmpty()) modifier.heightIn(min=48.dp).clickable(enabled=enabled,role=Role.Button) {event(action,null)} else modifier
            Card(cardModifier,shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=when(style?.optString("tone")) {
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
        "spacer" -> Spacer(Modifier.size(node.optInt("size",12).dp))
        "text","metric","badge" -> {
            val label=ToolUiCatalog.display(node,state)
            val align=when(style?.optString("align")) {"center" -> TextAlign.Center; "end" -> TextAlign.End; else -> TextAlign.Start}
            if(type == "badge") Surface(modifier,shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.secondaryContainer) {
                Text(label,Modifier.padding(horizontal=10.dp,vertical=6.dp),style=MaterialTheme.typography.labelLarge,textAlign=align)
            } else if(type == "metric" && node.has("bind")) Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(text,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(label.removePrefix("$text: "),style=textStyle(style,true),color=tone(style),textAlign=align)
            } else if(node.has("bind") || !text.equals(heading,ignoreCase=true)) Text(label,modifier,style=textStyle(style),color=tone(style),textAlign=align)
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
        "button" -> Button(onClick={event(action,null)},enabled=enabled,modifier=modifier.heightIn(min=48.dp)) {
            if(node.has("icon")) {AlphaIcon(Glyph.valueOf(node.getString("icon"))); Spacer(Modifier.width(8.dp))}
            Text(text)
        }
        "icon" -> {
            val icon: @Composable () -> Unit = {AlphaIcon(Glyph.valueOf(node.getString("name")),Modifier.semantics {contentDescription=text})}
            if(action.isNotEmpty()) IconButton(onClick={event(action,null)},enabled=enabled,modifier=Modifier.padding(space(style?.optString("padding"))).sizeIn(minWidth=48.dp,minHeight=48.dp)) {icon()}
            else Box(modifier) {icon()}
        }
        "toggle","checkbox" -> Row(modifier.heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
            val checked=ToolUiCatalog.value(state,binding) as Boolean
            val label=Modifier.semantics {contentDescription=text}
            if(type == "toggle") Switch(checked,{event(action,it)},label,enabled=enabled)
            else Checkbox(checked,{event(action,it)},label,enabled=enabled)
            Text(text,Modifier.padding(start=10.dp).weight(1f))
        }
        "input","numberInput" -> NativeInput(node,state,enabled,modifier,event)
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
            var value by remember(state.toString(),binding) {mutableDoubleStateOf(original)}
            Column(modifier) {
                Text("$text: $value")
                Slider(value=((value-low)/(high-low)).toFloat().coerceIn(0f,1f),onValueChange={ fraction ->
                    val proposed=low+fraction.toDouble()*(high-low)
                    value=if(node.has("step")) (low+round((proposed-low)/node.getDouble("step"))*node.getDouble("step")).coerceIn(low,high) else proposed
                },enabled=enabled,modifier=Modifier.semantics {contentDescription=text})
                TextButton(onClick={event(action,value)},enabled=enabled && value != original) {Text("Apply: $text")}
            }
        }
        "select","tabs" -> {
            val options=node.getJSONArray("options"); val selected=ToolUiCatalog.value(state,binding)
            Column(modifier,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(text,style=MaterialTheme.typography.labelLarge)
                if(type == "tabs") FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    repeat(options.length()) {index -> val option=options.getJSONObject(index); val value=option.get("value")
                        FilterChip(selected=scalarEqual(selected,value),onClick={event(action,value)},label={Text(option.getString("text"))},enabled=enabled,modifier=Modifier.heightIn(min=48.dp))
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
        "list","table" -> NativeRecords(node,state,enabled,modifier,heading,event)
        "progress","progressRing" -> {
            val (low,high)=ToolUiCatalog.bounds(node); val value=(ToolUiCatalog.value(state,binding) as Number).toDouble()
            val progress=((value-low)/(high-low)).toFloat().coerceIn(0f,1f)
            Column(modifier,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(ToolUiCatalog.display(node,state))
                if(type == "progress") LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth().semantics {contentDescription=text})
                else CircularProgressIndicator(progress={progress},modifier=Modifier.size(56.dp).semantics {contentDescription=text})
            }
        }
        "chart" -> NativeChart(node,state,modifier,heading)
        "image" -> {
            val handle=if(binding.isNotEmpty()) ToolUiCatalog.value(state,binding).toString() else node.getString("resourceId")
            val loader by rememberUpdatedState(imageLoader)
            val available by rememberUpdatedState(imageAvailable)
            val lifecycle=LocalLifecycleOwner.current.lifecycle
            key(handle,imageVersion,state.toString(),lifecycle) {
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
private fun scalarEqual(a: Any,b: Any) = if(a is Number && b is Number) a.toDouble() == b.toDouble() else a == b
@Composable private fun NativeInput(node: JSONObject, state: JSONObject, enabled: Boolean, modifier: Modifier, event: (String,Any?) -> Unit) {
    val binding=node.getString("bind"); val original=ToolUiCatalog.value(state,binding)
    val numeric=node.getString("type") == "numberInput"
    var draft by remember(state.toString(),binding) {mutableStateOf(original.toString())}
    val payload=if(numeric) draft.toDoubleOrNull() else draft
    val valid=runCatching {ToolUiCatalog.validateAction(node,node.getString("action"),payload,state)}.isSuccess
    val changed=if(numeric) payload != (original as Number).toDouble() else payload != original
    val apply={if(enabled && valid && changed) event(node.getString("action"),payload)}
    Column(modifier) {
        OutlinedTextField(draft,{if(it.length <= if(numeric) 40 else node.optInt("maxLength",2048)) draft=it},
            enabled=enabled,label={Text(node.getString("text"))},modifier=Modifier.fillMaxWidth(),singleLine=numeric,
            isError=draft.isNotEmpty() && !valid,keyboardOptions=KeyboardOptions(keyboardType=if(numeric) KeyboardType.Decimal else KeyboardType.Text,imeAction=ImeAction.Done),
            keyboardActions=KeyboardActions(onDone={apply()}))
        TextButton(onClick=apply,enabled=enabled && valid && changed) {Text("Apply: ${node.getString("text")}")}
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun NativeRecords(node: JSONObject, state: JSONObject, enabled: Boolean, modifier: Modifier, heading: String?, event: (String,Any?) -> Unit) {
    val rows=ToolUiCatalog.records(node,state); val columns=node.optJSONArray("columns"); val action=node.optString("action")
    Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(!node.getString("text").equals(heading,ignoreCase=true)) Text(node.getString("text"),style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics {heading()})
        if(rows.length() == 0) Text("No records on this page")
        if(node.getString("type") == "table") {
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
        } else repeat(rows.length()) {index -> val record=rows.getJSONObject(index)
            val label=if(columns != null) (0 until columns.length()).joinToString(" · ") {val c=columns.getJSONObject(it); "${c.getString("text")}: ${ToolUiCatalog.value(record,c.getString("field"))}"}
            else {
                val fields=if(record.has("key") && record.has("token") && record.has("value")) record.getJSONObject("value") else record
                fields.keys().asSequence().filter {it != "id"}.joinToString(" · ") {"$it: ${fields.get(it)}"}
            }
            key(ToolUiCatalog.stableKey(record)) {
                val selection=if(action.isNotEmpty()) Modifier.clickable(enabled=enabled,role=Role.Button) {event(action,ToolUiCatalog.stableKey(record))} else Modifier
                Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Column(selection.fillMaxWidth().heightIn(min=48.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            if(columns != null) repeat(columns.length()) { column ->
                                val field=columns.getJSONObject(column)
                                Text(ToolUiCatalog.value(record,field.getString("field")).toString(),
                                    style=if(column == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                                    color=if(column == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            } else Text(label,style=MaterialTheme.typography.bodyLarge)
                        }
                        node.optJSONArray("rowActions")?.let {actions ->
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                repeat(actions.length()) { index ->
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
@Composable private fun NativePreset(preset: String, content: @Composable () -> Unit) {
    val radius=when(preset) {"clean" -> 12; "soft" -> 24; "expressive" -> 28; "editorial" -> 4; "technical" -> 0; else -> 32}.dp
    val family=when(preset) {"editorial" -> FontFamily.Serif; "technical" -> FontFamily.Monospace; else -> FontFamily.Default}
    val typography=MaterialTheme.typography
    val colors=MaterialTheme.colorScheme
    MaterialTheme(colorScheme=if(preset in setOf("expressive","playful")) colors.copy(primary=colors.tertiary,primaryContainer=colors.tertiaryContainer,onPrimary=colors.onTertiary,onPrimaryContainer=colors.onTertiaryContainer) else colors,
        shapes=Shapes(RoundedCornerShape(radius),RoundedCornerShape(radius),RoundedCornerShape(radius),RoundedCornerShape(radius),RoundedCornerShape(radius)),
        typography=typography.copy(bodyLarge=typography.bodyLarge.copy(fontFamily=family),titleLarge=typography.titleLarge.copy(fontFamily=family),headlineMedium=typography.headlineMedium.copy(fontFamily=family)),content=content)
}
