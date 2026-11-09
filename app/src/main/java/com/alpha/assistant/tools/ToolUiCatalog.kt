package com.alpha.assistant.tools

import com.alpha.assistant.timer.ForegroundTimerSpec
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/** The native UI contract. Rendering, actions, model guidance, and widget projection share these definitions. */
object ToolUiCatalog {
    const val MAX_RENDER_WORK = 2048
    const val MAX_IMAGES = 4
    const val MAX_IMAGE_EDGE = 1024
    const val MAX_IMAGE_BYTES = MAX_IMAGE_EDGE * MAX_IMAGE_EDGE * 4
    private const val MAX_ROUTES = 16
    private const val MAX_TIMER_TIMESTAMP = 253_402_300_799_999L
    private val resourceHandle=Regex("[0-9a-fA-F]{64}")
    private val videoHandle = Regex("[0-9a-f]{64}")
    enum class Projection { CONTAINER, TEXT, LOCAL_ACTION, SUMMARY, OMIT }
    data class Component(val type: String, val properties: Set<String>, val projection: Projection,
        val binding: Set<String> = emptySet(), val actionRequired: Boolean = false, val container: Boolean = false,
        val styles: Set<String> = setOf("tone","emphasis","align","padding"))
    private val common=setOf("type","visible","enabled","visibleBind","enabledBind","style","layout","interactions","semantics")
    private val scalar=setOf("string","number","boolean")
    private val liveInputTypes = setOf("input", "slider")
    private val timerModes = setOf("countdown", "stopwatch")
    private val optionalBindings=setOf("text","metric","badge","fact","timelineItem","image","progress","progressRing")
    val choiceTypes=setOf("select","tabs","singleChoice","multiChoice","segmented","navigationBar","navigationRail","menu")
    val modalTypes=setOf("dialog","bottomSheet")
    val interactionKeys=setOf("enter","space","escape","up","down","left","right")
    private class MissingBindingPath : IllegalArgumentException("Binding path is missing.")
    internal val tones=setOf("default","muted","accent","success","warning","error")
    val presets=setOf("clean","soft","expressive","editorial","technical","playful")
    val icons=setOf("Menu","History","Plus","Arrow","Close","Back","Cloud","Mic","Grid","Spark","Calendar","Repeat","Settings","File","Link","Chevron","Search","Screen","Camera","Bell","Check")
    private val unlabeledLeaves = setOf("spacer", "divider", "skeleton", "spinner", "backHandler", "navigation", "calendar", "medicine")

    private fun entry(type: String, props: Set<String> = emptySet(), projection: Projection = Projection.SUMMARY,
        binding: Set<String> = emptySet(), action: Boolean = false, container: Boolean = false) =
        Component(type,common+props+(if(type in setOf("column","row","flowRow","box","grid","scroll","theme","spacer","divider","backHandler")) emptySet() else setOf("text")),projection,binding,action,container,
            when(type) {
                "text","metric","timer" -> setOf("tone","emphasis","align","padding")
                "badge" -> setOf("tone","align","padding")
                "column","theme" -> setOf("align","padding","gap","arrangement")
                "box" -> setOf("align","padding")
                "row","flowRow" -> setOf("padding","gap","arrangement","crossAlign")
                "grid","scroll" -> setOf("padding","gap")
                "card","section","listItem","form","listGroup","facts","timeline","banner","emptyState" -> setOf("tone","align","padding","gap")
                "spacer","backHandler" -> emptySet()
                else -> setOf("padding")
            })
    val components: Map<String,Component> = buildList {
        for(type in listOf("column","row","flowRow","box","grid","scroll","theme","card","section","listItem","form","listGroup","facts","timeline","banner","emptyState","motion","transform","stage")) add(entry(type,
            setOf("children") + when(type) {"grid" -> setOf("columns","minCellWidth"); "row" -> setOf("wrap"); "theme" -> setOf("preset","typeScale","cornerRadius"); "listItem" -> setOf("action"); "motion" -> setOf("preset","trigger","animateInitial"); "transform" -> setOf("pose","poseBind","durationMs","size","solid"); "stage" -> setOf("height","perspective","floor"); else -> emptySet()},
            Projection.CONTAINER,container=true))
        for(type in listOf("appBar","bottomBar","dialog","bottomSheet","pager","transition")) add(entry(type,
            setOf("children") + when(type) {
                "dialog","bottomSheet" -> setOf("dismissAction")
                "pager" -> setOf("bind","action","viewportHeight")
                "transition" -> setOf("bind","preset","durationMs")
                else -> emptySet()
            },if(type in setOf("dialog","bottomSheet","pager","transition")) Projection.SUMMARY else Projection.CONTAINER,
            binding=when(type) {"pager" -> setOf("number"); "transition" -> setOf("boolean"); else -> emptySet()},action=type == "pager",container=true))
        add(entry("routeStack", setOf("bind", "routes", "children", "action"), binding = setOf("array"), action = true, container = true))
        add(entry("spacer",setOf("size"),Projection.OMIT))
        add(entry("text",setOf("bind","format","maxLines","overflow","softWrap","lineHeight","selectable","spans"),Projection.TEXT,scalar))
        add(entry("metric",setOf("bind","format"),Projection.TEXT,setOf("number","string")))
        add(entry("timer", setOf("bind", "mode", "action", "foreground"), binding = setOf("number")).let { it.copy(properties = it.properties - "interactions") })
        add(entry("calendar",setOf("action","capability","args"),Projection.SUMMARY,action=true).let {it.copy(properties=it.properties-"text")})
        add(entry("medicine",setOf("action","capability","args"),Projection.SUMMARY,action=true).let {it.copy(properties=it.properties-"text")})
        add(entry("navigation",setOf("action","capability","args"),Projection.SUMMARY,action=true).let {it.copy(properties=it.properties-"text")})
        add(entry("button",setOf("action","icon","variant"),Projection.LOCAL_ACTION,action=true))
        add(entry("actionChip",setOf("action","variant"),Projection.SUMMARY,action=true))
        add(entry("icon",setOf("name","action"),Projection.SUMMARY))
        for(type in listOf("input","numberInput","timeInput","dateInput","toggle","checkbox","slider","select","tabs","chip","singleChoice","multiChoice","segmented","navigationBar","navigationRail","menu","rangeSlider","dateRangeInput","search")) {
            val binding=when(type) {"input","search","dateInput" -> setOf("string"); "rangeSlider","dateRangeInput","multiChoice" -> setOf("array"); "toggle","checkbox","chip" -> setOf("boolean"); "numberInput","timeInput","slider" -> setOf("number"); else -> scalar}
            val props=when(type) {"chip" -> setOf("variant","dismissAction"); "input","search" -> setOf("maxLength","keyboard","imeAction","maxLines"); "numberInput","slider","rangeSlider" -> setOf("min","max","step"); in choiceTypes -> setOf("options"); else -> emptySet()}
            val inputProps = if (type in liveInputTypes) props + "inputMode" else props
            add(entry(type,setOf("bind","action")+inputProps,if(type == "toggle") Projection.LOCAL_ACTION else Projection.SUMMARY,binding,action=true))
        }
        for(type in listOf("list","table","groupedList","lazyList","lazyGrid")) {
            val rowProperties=if(type == "table") emptySet() else setOf("rowActions","rowTemplate","emptyText","groupBy","swipeAction")
            val viewportProperties=if(type in setOf("lazyList","lazyGrid")) setOf("viewportHeight","selectedKeyBind","scrollToKeyBind")+(if(type == "lazyList") setOf("stickyHeaders") else setOf("spanField")) else emptySet()
            val reorderProperties = if (type == "list" || type == "lazyList") setOf("reorder") else emptySet()
            add(entry(type,setOf("bind","action","columns")+rowProperties+viewportProperties+reorderProperties+(if(type == "lazyGrid") setOf("minCellWidth") else emptySet()),binding=setOf("list","array")))
        }
        add(entry("fact",setOf("bind","format"),Projection.TEXT,scalar))
        add(entry("timelineItem",setOf("bind","detail","time","detailBind","timeBind","action"),binding=scalar))
        for(type in listOf("progress","progressRing")) add(entry(type,setOf("bind","min","max","indeterminate"),Projection.TEXT,setOf("number")))
        add(entry("chart",setOf("kind","categories","series")))
        add(entry("image",setOf("bind","resourceId","fit"),binding=setOf("string")))
        add(entry("video", setOf("bind", "action"), binding = setOf("string"), action = true)
            .let { it.copy(properties = it.properties - "interactions") })
        for(type in listOf("divider","skeleton","spinner")) add(entry(type,projection=Projection.OMIT))
        add(entry("badge",setOf("bind"),Projection.TEXT,scalar))
        add(entry("snackbar",setOf("action")))
        add(entry("backHandler",setOf("action"),Projection.OMIT,action=true))
    }.associateBy {it.type}
    fun component(type: String) = requireNotNull(components[type]) {"Unsupported native component: $type"}
    fun isLiveInput(node: JSONObject): Boolean = node.opt("inputMode") == "live" && node.optString("type") in liveInputTypes
    fun visible(node: JSONObject) = node.optBoolean("visible",true)
    fun enabled(node: JSONObject) = node.optBoolean("enabled",true)
    fun visible(node: JSONObject,state: JSONObject) = visible(node) && (!node.has("visibleBind") || value(state,node.getString("visibleBind")) == true)
    fun enabled(node: JSONObject,state: JSONObject) = enabled(node) && (!node.has("enabledBind") || value(state,node.getString("enabledBind")) == true)
    private fun path(value: String): List<String> = value.split('.').also { require(it.isNotEmpty() && it.all(ToolValueSchema::name)) {"Invalid object binding path."} }
    fun value(state: JSONObject, binding: String): Any = value(state,path(binding))
    private fun value(state: JSONObject, parts: List<String>): Any {
        var result: Any=state
        for(part in parts) {
            require(result is JSONObject) {"Binding must traverse state objects."}
            if(!result.has(part)) throw MissingBindingPath()
            result=result.get(part)
        }
        return result
    }
    private fun schema(fields: JSONObject, binding: String): Any = schema(fields,path(binding))
    private fun schema(fields: JSONObject, parts: List<String>): Any {
        var result: Any=JSONObject().put("type","object").put("fields",fields)
        for(part in parts) {
            require(result is JSONObject && result.getString("type") == "object") {"Binding must traverse declared objects."}
            val children=result.getJSONObject("fields"); if(!children.has(part)) throw MissingBindingPath(); result=children.get(part)
        }
        return result
    }
    private fun kind(contract: Any) = if(contract is String) contract else (contract as JSONObject).getString("type")
    private fun collectionItem(contract: Any): JSONObject {
        require(contract is JSONObject)
        return when(contract.getString("type")) {
            "list" -> JSONObject().put("type","object").put("fields",contract.getJSONObject("fields"))
            "array" -> contract.getJSONObject("items").also {require(it.getString("type") == "object")}
            else -> error("Binding must be a record collection.")
        }
    }
    fun records(node: JSONObject, state: JSONObject) = value(state,node.getString("bind")) as JSONArray
    fun stableKey(record: JSONObject): String = if(record.has("key") && record.has("token") && record.has("value")) record.getString("key") else record.getString("id")
    private fun stableRecords(rows: JSONArray) {
        val keys=mutableSetOf<String>()
        repeat(rows.length()) {
            val row=rows.getJSONObject(it); val key=stableKey(row)
            require(key.isNotBlank() && key.length <= 128 && keys.add(key)) {"Record keys must be present and unique."}
            if(row.has("key") && row.has("token") && row.has("value")) require(row.get("token") is String && row.getString("token").isNotBlank() && row.get("value") is JSONObject)
        }
    }
    private fun scalarValue(value: Any) = value is String || value is Boolean || (value is Number && value.toDouble().isFinite())
    private fun numeric(node: JSONObject, name: String, default: Double): Double = if(node.has(name)) {
        val number=node.get(name); require(number is Number && number.toDouble().isFinite() && kotlin.math.abs(number.toDouble()) <= ToolValueSchema.MAX_SAFE_NUMBER); number.toDouble()
    } else default
    fun bounds(node: JSONObject): Pair<Double,Double> = numeric(node,"min",if(node.getString("type") in setOf("progress","progressRing","slider","rangeSlider")) 0.0 else -ToolValueSchema.MAX_SAFE_NUMBER) to
        numeric(node,"max",if(node.getString("type") in setOf("progress","progressRing","slider","rangeSlider")) 100.0 else ToolValueSchema.MAX_SAFE_NUMBER)
    private fun validDate(value: Any?) = value is String && value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) && runCatching {LocalDate.parse(value)}.isSuccess
    private fun options(node: JSONObject): JSONArray = node.getJSONArray("options")
    private fun matches(a: Any, b: Any?) = if(a is Number && b is Number) a.toDouble() == b.toDouble() else a == b
    fun validate(root: JSONObject, fields: JSONObject, state: JSONObject): JSONObject = validate(root,fields,state,null)
    internal fun validate(root: JSONObject,fields: JSONObject,state: JSONObject,trace: ToolUiValidationTrace?, capabilities: Set<String>? = null): JSONObject {
        trace?.at("ui_bytes","$.ui","bytes_at_most_32768",root,root.toString().toByteArray(Charsets.UTF_8).size)
        require(root.toString().toByteArray(Charsets.UTF_8).size <= 32768) {"Native UI exceeds 32 KiB."}
        var count=0; val actions=mutableSetOf<String>()
        var hasRouteStack = false
        var hasBackHandler = false
        fun visit(node: JSONObject, depth: Int, location: String, fields: JSONObject, state: JSONObject, template: Boolean = false, schemaOnly: Boolean = false, register: Boolean = true, motion: Boolean = false, stage: Boolean = false, layerContent: Boolean = false, parent: String = "") {
            trace?.at("node_depth_limit",location,"nodes_256_depth_8",node,count+1)
            require((!register || ++count <= 256) && depth <= 8) {"Native UI exceeds its node or depth limit."}
            trace?.at("component_type","$location.type","catalog_component",node.opt("type"))
            val type=node.getString("type"); val definition=component(type)
            if(template) require(type !in setOf("list","table","groupedList","lazyList","lazyGrid","image","video","calendar","medicine","navigation","backHandler","stage","dialog","bottomSheet","pager","routeStack")) {"Unsupported row-template component."}
            if(stage && layerContent) require(type != "transform") {"Stage transforms must be direct layer children."}
            if(stage) require(!node.has("interactions") && !node.has("dismissAction") && !node.has("action") && !component(type).actionRequired && type !in setOf("motion","stage","list","table","groupedList","lazyList","lazyGrid")) {"Stage content must be noninteractive layers."}
            validateTool(ToolValidationCategory.UI_PROPERTIES) {
                trace?.at("component_properties",location,"catalog_properties",node,node.length())
                if(type == "theme") validateTheme(node)
                require(ToolValueSchema.keys(node).all {it in definition.properties}) {"Unsupported component property."}
                if (node.has("inputMode")) {
                    val mode = node.get("inputMode")
                    trace?.at("input_mode", "$location.inputMode", "apply_or_live", mode)
                    require(mode == "apply" || mode == "live") { "Input mode must be apply or live." }
                }
                for(flag in listOf("visible","enabled")) if(node.has(flag)) {
                    trace?.at("component_flag","$location.$flag","boolean",node.opt(flag))
                    require(node.get(flag) is Boolean)
                }
                for(flag in listOf("visibleBind","enabledBind")) if(node.has(flag)) {
                    require(node.get(flag) is String && schema(fields,node.getString(flag)) == "boolean") {"Visibility and availability need declared boolean bindings."}
                    if(!schemaOnly) require(value(state,node.getString(flag)) is Boolean)
                }
                if(node.has("text")) {
                    trace?.at("component_text","$location.text","string_length_1_300",node.opt("text"),(node.opt("text") as? String)?.length)
                    require(node.get("text") is String && node.getString("text").length in 1..300)
                }
                if(node.has("layout")) validateLayout(node.getJSONObject("layout"),parent)
                if(node.has("style")) {
                    trace?.at("component_style","$location.style","catalog_style",node.opt("style"))
                    val style=node.getJSONObject("style"); require(ToolValueSchema.keys(style).all {it in definition.styles})
                    style.keys().forEach { key -> require(style.get(key) is String && style.getString(key) in when(key) {
                        "tone" -> tones
                        "emphasis" -> setOf("body","title","headline","label","number")
                        "align","crossAlign" -> setOf("start","center","end")
                        "arrangement" -> setOf("start","center","end","between","around","evenly")
                        "padding","gap" -> setOf("none","small","medium","large")
                        else -> emptySet()
                    }) }
                }
            }
            if(!definition.container && type !in unlabeledLeaves) {
                trace?.at("accessible_label","$location.text","label_present",node.opt("text"))
                require(node.has("text")) {"Native controls need an accessible label."}
            }
            var binding: Any?=null
            val managedTimer = type == "timer" && node.has("foreground")
            if(node.has("bind") || (definition.binding.isNotEmpty() && type !in optionalBindings && !managedTimer)) {
                validateTool(ToolValidationCategory.UI_BINDINGS) {
                    trace?.at("component_binding","$location.bind","declared_binding_and_state",node.opt("bind"))
                    try {
                        if(!node.has("bind")) throw MissingBindingPath()
                        require(node.get("bind") is String)
                        val parts=path(node.getString("bind"))
                        trace?.at("component_binding_schema_type","$location.bind","compatible_schema_type",node.opt("bind"))
                        binding=schema(fields,parts)
                        trace?.at("component_binding_schema_type","$location.bind","compatible_schema_type",binding)
                        require(kind(checkNotNull(binding)) in definition.binding)
                        if(!schemaOnly) {
                            trace?.at("component_binding_state_value","$location.bind","schema_valid_state_value")
                            val current=value(state,parts)
                            trace?.at("component_binding_state_value","$location.bind","schema_valid_state_value",current)
                            ToolValueSchema.validate(checkNotNull(binding),current)
                        }
                    } catch(failure: MissingBindingPath) {
                        trace?.at("component_binding_missing_path","$location.bind","declared_path_in_schema_and_state")
                        throw failure
                    }
                }
            }
            if(definition.actionRequired || node.has("action")) validateTool(ToolValidationCategory.UI_ACTIONS) {
                trace?.at("component_action","$location.action","unique_ascii_action",node.opt("action"))
                require(node.get("action") is String); val action=node.getString("action")
                require(action.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,47}")) && (!register || actions.add(action))) {"Action IDs must be unique."}
            }
            validateInteractions(node,actions,register,stage)
            trace?.at("component_contract",location,"catalog_component_contract",node)
            when(type) {
                "dialog","bottomSheet" -> {
                    require(node.has("text") && node.has("visibleBind")) {"Modals require a title and declared visibility."}
                    registerAction(node.get("dismissAction"),actions,register)
                }
                "pager" -> {
                    val pages=node.getJSONArray("children"); require(pages.length() in 1..10)
                    if(node.has("viewportHeight")) require(node.get("viewportHeight") is Int && node.getInt("viewportHeight") in 96..1024)
                    if(!schemaOnly) require(validPage(value(state,node.getString("bind")),pages.length()))
                }
                "routeStack" -> {
                    require(!hasRouteStack && !hasBackHandler) { "Declare one route stack without legacy Back handlers." }
                    hasRouteStack = true
                    val contract = checkNotNull(binding) as JSONObject
                    require(contract.get("items") == "string" && contract.getInt("maxItems") in 1..MAX_ROUTES)
                    require(node.has("text"))
                    val ids = routeIds(node)
                    require(ids.size in 1..MAX_ROUTES && ids.distinct().size == ids.size &&
                        ids.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,47}")) }) { "Route IDs must be unique ASCII names." }
                    require(node.getJSONArray("children").length() == ids.size) { "Each route needs one child." }
                    if (!schemaOnly) routeStack(node, state)
                }
                "backHandler" -> {
                    require(!hasRouteStack) { "A route stack owns Back." }
                    hasBackHandler = true
                }
                "transition" -> {
                    require(node.get("preset") is String && node.getString("preset") in setOf("fade","fadeSlide","expand"))
                    if(node.has("durationMs")) require(node.get("durationMs") is Int && node.getInt("durationMs") in 0..5000)
                }
                "rangeSlider","dateRangeInput" -> {
                    val contract=checkNotNull(binding) as JSONObject
                    require(contract.get("items") == if(type == "rangeSlider") "number" else "string")
                    if(type == "rangeSlider") {val (low,high)=bounds(node);require(low < high);if(node.has("step")) require(numeric(node,"step",1.0) > 0 && numeric(node,"step",1.0) <= high-low)}
                    if(!schemaOnly) require(validRange(node,value(state,node.getString("bind"))))
                }
                "motion" -> {
                    require(!motion) {"Motion must not nest."}
                    require(node.get("preset") is String && node.getString("preset") in motionPresets)
                    require(node.get("trigger") is String && schema(fields,node.getString("trigger")) == "number")
                    if(!schemaOnly) require((value(state,node.getString("trigger")) as? Number)?.toDouble()?.isFinite() == true)
                    if(node.has("animateInitial")) require(node.get("animateInitial") is Boolean)
                }
                "transform" -> {
                    require(node.has("pose") != node.has("poseBind")) {"Transform needs one pose source."}
                    if(node.has("poseBind")) {
                        val contract=schema(fields,node.getString("poseBind"))
                        require(contract is JSONObject && contract.optString("type") == "array" && contract.opt("items") == "number")
                    }
                    if(!schemaOnly || node.has("pose")) validatePose(if(node.has("pose")) node.getJSONArray("pose") else value(state,node.getString("poseBind")) as JSONArray)
                    for(name in listOf("durationMs","size")) if(node.has(name)) require(node.get(name) is Int && node.getInt(name) in if(name == "size") 0..1024 else 0..10000)
                    if(node.has("solid")) require(node.get("solid") is Boolean)
                }
                "stage" -> {
                    require(!stage) {"Stages must not nest."}
                    if(node.has("height")) require(node.get("height") is Int && node.getInt("height") in 64..1024)
                    if(node.has("perspective")) require(numeric(node,"perspective",900.0) in 128.0..4096.0)
                    if(node.has("floor")) require(node.get("floor") is Boolean)
                    val layers=node.getJSONArray("children")
                    repeat(layers.length()) {require(layers.getJSONObject(it).getString("type") == "transform") {"Stage children must be transforms."}}
                }
                "timelineItem" -> {
                    for(name in listOf("detail","time")) if(node.has(name)) require(node.get(name) is String && node.getString(name).length in 1..300)
                    for(name in listOf("detailBind","timeBind")) if(node.has(name)) {
                        require(node.get(name) is String && kind(schema(fields,node.getString(name))) in scalar)
                        if(!schemaOnly) require(scalarValue(value(state,node.getString(name))))
                    }
                }
                "calendar" -> com.alpha.assistant.capabilities.OwnedCalendarContract.request(node)
                "medicine" -> com.alpha.assistant.capabilities.MedicineContract.request(node)
                "navigation" -> com.alpha.assistant.capabilities.NavigationContract.request(node)
                "theme" -> {
                    trace?.at("theme_preset","$location.preset","catalog_preset",node.opt("preset"))
                    require(node.get("preset") is String && node.getString("preset") in presets)
                }
                "row" -> if(node.has("wrap")) require(node.get("wrap") is Boolean)
                "grid" -> {
                    if(node.has("minCellWidth")) require(node.get("minCellWidth") is Int && node.getInt("minCellWidth") in 48..512)
                    trace?.at("grid_columns","$location.columns","integer_1_4",node.opt("columns"))
                    if(node.has("columns")) require(node.get("columns") is Int && node.getInt("columns") in 1..4)
                }
                "spacer" -> {
                    trace?.at("spacer_size","$location.size","integer_0_64",node.opt("size"))
                    if(node.has("size")) require(node.get("size") is Int && node.getInt("size") in 0..64)
                }
                "icon" -> {
                    trace?.at("icon_name","$location.name","catalog_icon",node.opt("name"))
                    require(node.get("name") is String && node.getString("name") in icons)
                }
                "chip" -> {
                    if(node.has("variant")) require(node.get("variant") is String && node.getString("variant") in setOf("filter","input"))
                    if(node.has("dismissAction"))registerAction(node.get("dismissAction"),actions,register)
                }
                "actionChip" -> if(node.has("variant"))require(node.get("variant") is String && node.getString("variant") in setOf("assist","suggestion"))
                "button" -> {
                    if(node.has("variant"))require(node.get("variant") is String && node.getString("variant") in setOf("filled","outlined","text","tonal","elevated","fab","extendedFab"))
                    if(node.optString("variant") == "fab")require(node.has("icon")) {"An icon FAB requires an icon and label."}
                    trace?.at("button_icon","$location.icon","catalog_icon",node.opt("icon"))
                    if(node.has("icon")) require(node.get("icon") is String && node.getString("icon") in icons)
                }
                "input","search" -> {
                    trace?.at("input_length","$location.maxLength","integer_1_2048",node.opt("maxLength"))
                    if(node.has("maxLength")) require(node.get("maxLength") is Int && node.getInt("maxLength") in 1..2048)
                    if(node.has("keyboard")) require(node.get("keyboard") is String && node.getString("keyboard") in setOf("text","email","uri","phone","number","password"))
                    if(node.has("imeAction")) require(node.get("imeAction") is String && node.getString("imeAction") in setOf("done","next","search"))
                    if(node.has("maxLines")) require(node.get("maxLines") is Int && node.getInt("maxLines") in 1..8)
                }
                "timer" -> {
                    val mode = node.opt("mode")
                    trace?.at("timer_mode", "$location.mode", "countdown_or_stopwatch", mode)
                    require(mode is String && mode in timerModes) { "Timer mode must be countdown or stopwatch." }
                    if (managedTimer) {
                        require(!template && !stage) { "Foreground timers require a native app control." }
                        val timer = ForegroundTimerSpec.parse(node)
                        require(capabilities == null || timer.requests().all { it.capability in capabilities }) { "Declare the timer's alert capabilities." }
                    } else if (!schemaOnly) {
                        require(!node.has("action")) { "A clock display cannot send actions." }
                        val timestamp = value(state, node.getString("bind"))
                        trace?.at("timer_timestamp", "$location.bind", "bounded_epoch_milliseconds", timestamp)
                        require(timerTimestamp(timestamp) != null) { "Timer binding must contain bounded integer epoch milliseconds." }
                    }
                }
                "text","metric","fact" -> if(node.has("format")) {
                    trace?.at("text_format","$location.format","number_or_clock_format",node.opt("format"))
                    require(node.get("format") is String && node.getString("format") in setOf("number","clock")); if(node.getString("format") == "clock") require(binding == "number" && (schemaOnly || validTime(value(state,node.getString("bind")))))}
                in choiceTypes -> {
                    trace?.at("selection_options","$location.options","options_1_30",node.opt("options"),node.optJSONArray("options")?.length())
                    val options=options(node); require(options.length() in 1..if(type == "segmented") 6 else 30)
                    val optionContract=if(type == "multiChoice") (checkNotNull(binding) as JSONObject).get("items") else checkNotNull(binding)
                    require(kind(optionContract) in scalar)
                    val seen=mutableListOf<Any>()
                    repeat(options.length()) {
                        trace?.at("selection_option","$location.options[$it]","typed_unique_option",options.opt(it))
                        val option=options.getJSONObject(it); require(ToolValueSchema.keys(option) == setOf("text","value"))
                        require(option.get("text") is String && option.getString("text").length in 1..100)
                        val value=option.get("value"); require(scalarValue(value)); ToolValueSchema.validate(optionContract,value)
                        require(seen.none {previous -> matches(previous,value)}); seen.add(value)
                    }
                    trace?.at("selection_current_value","$location.options","current_value_in_options",node.opt("options"),options.length())
                    if(!schemaOnly) require(validChoice(node,value(state,node.getString("bind"))))
                }
                "list","table","groupedList","lazyList","lazyGrid" -> {
                    trace?.at("record_collection","$location.bind","stable_record_collection",node.opt("bind"))
                    val item=collectionItem(checkNotNull(binding)); val rows=if(schemaOnly) JSONArray() else records(node,state)
                    if(type in setOf("lazyList","lazyGrid")) {
                        if(node.has("viewportHeight")) require(node.get("viewportHeight") is Int && node.getInt("viewportHeight") in 96..1024)
                        for(name in listOf("selectedKeyBind","scrollToKeyBind")) if(node.has(name)) {
                            require(node.get(name) is String && schema(fields,node.getString(name)) == "string")
                            if(!schemaOnly) {
                                val key=value(state,node.getString(name)); require(key is String)
                                require(key.isEmpty() || (0 until rows.length()).any {stableKey(rows.getJSONObject(it)) == key}) {"Collection key must be current or empty."}
                            }
                        }
                        if(type == "lazyGrid") {
                            if(node.has("columns")) require(node.get("columns") is Int && node.getInt("columns") in 1..4)
                            if(node.has("minCellWidth")) require(node.get("minCellWidth") is Int && node.getInt("minCellWidth") in 48..512)
                        }
                    }
                    if(node.has("stickyHeaders")) require(node.get("stickyHeaders") is Boolean && (!node.getBoolean("stickyHeaders") || node.has("groupBy")))
                    if(node.has("spanField")) {
                        require(node.get("spanField") is String && schema(item.getJSONObject("fields"),node.getString("spanField")) == "number")
                        repeat(rows.length()) {val span=value(rows.getJSONObject(it),node.getString("spanField"));require(span is Number && span.toDouble() % 1 == 0.0 && span.toDouble() in 1.0..node.optInt("columns",2).toDouble())}
                    }
                    if(type != "table" || node.has("action")) {
                        val itemFields=item.getJSONObject("fields")
                        require(itemFields.optString("id") == "string" || (itemFields.optString("key") == "string" && itemFields.optString("token") == "string" && itemFields.optJSONObject("value")?.optString("type") == "object"))
                        stableRecords(rows)
                    }
                    if(node.has("emptyText")) require(node.get("emptyText") is String && node.getString("emptyText").length in 1..300)
                    if(type == "groupedList") require(node.has("groupBy"))
                    if(node.has("groupBy")) require(node.get("groupBy") is String && kind(schema(item.getJSONObject("fields"),node.getString("groupBy"))) in scalar)
                    if (node.has("reorder")) validateTool(ToolValidationCategory.UI_ACTIONS) {
                        trace?.at("record_reorder","$location.reorder","ungrouped_stable_key_reorder",node.opt("reorder"))
                        require(!node.has("groupBy")) { "Reorder requires an ungrouped list." }
                        val reorder = node.getJSONObject("reorder")
                        require(ToolValueSchema.keys(reorder).all { it in setOf("action", "labelField", "visibleField", "enabledField") })
                        registerAction(reorder.get("action"), actions, register)
                        val fields = item.getJSONObject("fields")
                        require(reorder.get("labelField") is String)
                        val labelPath = reorder.getString("labelField")
                        require(schema(fields, labelPath) == "string")
                        repeat(rows.length()) { index ->
                            val label = value(rows.getJSONObject(index), labelPath)
                            require(label is String && label.isNotBlank() && label.length <= 300)
                        }
                        for (flag in listOf("visibleField", "enabledField")) if (reorder.has(flag)) {
                            require(reorder.get(flag) is String && schema(fields, reorder.getString(flag)) == "boolean")
                        }
                    }
                    if(node.has("rowTemplate")) {
                        require((!node.has("columns") || type == "lazyGrid") && !node.has("action") && !node.has("rowActions")) {"Row templates own their controls."}
                        val rowTemplate=node.getJSONObject("rowTemplate")
                        if(rows.length() == 0) visit(rowTemplate,depth+1,"$location.rowTemplate",item.getJSONObject("fields"),JSONObject(),true,true,register,motion,stage)
                        repeat(rows.length()) {index -> visit(rowTemplate,depth+1,"$location.rowTemplate",item.getJSONObject("fields"),rows.getJSONObject(index),true,false,register && index==0,motion,stage)}
                    }
                    if(node.has("rowActions")) validateTool(ToolValidationCategory.UI_ACTIONS) {
                        trace?.at("row_actions","$location.rowActions","actions_1_3",node.opt("rowActions"),node.optJSONArray("rowActions")?.length())
                        val rowActions=node.getJSONArray("rowActions"); require(rowActions.length() in 1..3) {"Lists support one to three row actions."}
                        repeat(rowActions.length()) { index ->
                            trace?.at("row_action","$location.rowActions[$index]","catalog_row_action",rowActions.opt(index))
                            val rowAction=rowActions.getJSONObject(index)
                            require(ToolValueSchema.keys(rowAction).all {it in setOf("action","text","emphasis","enabledField")})
                            require(rowAction.get("action") is String)
                            val id=rowAction.getString("action")
                            require(id.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,47}")) && (!register || actions.add(id))) {"Action IDs must be unique."}
                            require(rowAction.get("text") is String && rowAction.getString("text").length in 1..100)
                            if(rowAction.has("emphasis")) require(rowAction.get("emphasis") is String && rowAction.getString("emphasis") in setOf("primary","secondary"))
                            if(rowAction.has("enabledField")) {
                                require(rowAction.get("enabledField") is String && schema(item.getJSONObject("fields"),rowAction.getString("enabledField")) == "boolean")
                                repeat(rows.length()) {require(value(rows.getJSONObject(it),rowAction.getString("enabledField")) is Boolean)}
                            }
                        }
                    }
                    if(node.has("swipeAction")) {
                        require(!node.has("rowTemplate") && node.get("swipeAction") is String)
                        val rowActions=node.getJSONArray("rowActions")
                        require((0 until rowActions.length()).any {rowActions.getJSONObject(it).getString("action") == node.getString("swipeAction")}) {"Swipe must reference a visible row action."}
                    }
                    trace?.at("table_rows","$location.bind","rows_at_most_50",rows,rows.length())
                    if(type == "table") require(rows.length() <= 50)
                    if(type == "table" || (node.has("columns") && type != "lazyGrid")) {
                        trace?.at("table_columns","$location.columns","columns_1_8_cells_400",node.opt("columns"),node.optJSONArray("columns")?.length())
                        val columns=node.getJSONArray("columns"); require(columns.length() in 1..8 && rows.length()*columns.length() <= 400)
                        repeat(columns.length()) { index ->
                            trace?.at("table_column","$location.columns[$index]","scalar_record_column",columns.opt(index))
                            val column=columns.getJSONObject(index); require(ToolValueSchema.keys(column) == setOf("text","field"))
                            require(column.get("text") is String && column.getString("text").length in 1..100)
                            require(column.get("field") is String && kind(schema(item.getJSONObject("fields"),column.getString("field"))) in scalar)
                            repeat(rows.length()) {require(scalarValue(value(rows.getJSONObject(it),column.getString("field"))))}
                        }
                    }
                }
                "chart" -> validateChart(node,fields,state,trace,location,schemaOnly)
                "image" -> {
                    trace?.at("image_handle",location,"opaque_resource_handle",node)
                    require(node.has("bind") != node.has("resourceId"))
                    val handle=if(node.has("bind")) value(state,node.getString("bind")) else node.get("resourceId")
                    require(handle is String && ((node.has("bind") && handle.isEmpty()) || handle.matches(Regex("[0-9a-f]{64}")))) {"Images need an opaque resource handle."}
                    if(node.has("fit")) require(node.get("fit") is String && node.getString("fit") in setOf("fit","crop"))
                }
                "video" -> if (!schemaOnly) {
                    val handle = value(state, node.getString("bind"))
                    trace?.at("video_handle", location, "empty_or_opaque_resource_handle", handle)
                    require(handle is String && (handle.isEmpty() || videoHandle.matches(handle))) {
                        "Videos need an empty binding or an opaque resource handle."
                    }
                }
            }
            if(type in setOf("progress","progressRing")) {
                if(node.has("indeterminate")) require(node.get("indeterminate") is Boolean)
                require(node.has("bind") || node.optBoolean("indeterminate"))
            }
            if(type in setOf("numberInput","slider","progress","progressRing")) {
                trace?.at("numeric_bounds",location,"finite_ordered_bounds_and_value",node)
                val (low,high)=bounds(node); require(low < high)
                if(!schemaOnly && node.has("bind")) {val v=value(state,node.getString("bind")); require(v is Number && v.toDouble() in low..high)}
                if (node.has("step")) {
                    trace?.at("numeric_step", "$location.step", "positive_finite_step_within_bounds", node.opt("step"))
                    val step = numeric(node, "step", 1.0)
                    require(step > 0 && step <= high - low) { "Step must be positive and no larger than the range." }
                }
            }
            if(type == "timeInput") {
                trace?.at("time_value","$location.bind","integer_minutes_0_1439",node.opt("bind"))
                if(!schemaOnly) require(validTime(value(state,node.getString("bind"))))
            }
            if(type == "dateInput" && !schemaOnly) {
                val date=value(state,node.getString("bind"))
                trace?.at("date_value","$location.bind","iso_date",date)
                require(validDate(date))
            }
            if(type == "text") validateText(node,actions,register,stage)
            if(definition.container) {
                trace?.at("container_children","$location.children","children_1_50",node.opt("children"),node.optJSONArray("children")?.length())
                val children=node.getJSONArray("children"); require(children.length() in 1..50)
                repeat(children.length()) {
                    trace?.at("child_object","$location.children[$it]","object",children.opt(it))
                    visit(children.getJSONObject(it),depth+1,"$location.children[$it]",fields,state,template,schemaOnly,register,motion || type == "motion",stage || type == "stage",stage && (layerContent || type != "transform"),if(type == "row" && node.optBoolean("wrap",true)) "flowRow" else type)
                }
            }
        }
        visit(root,0,"$.ui",fields,state); validateRenderedWork(root,state,trace); return root
    }
    /** Bound expanded native work as well as JSON nodes. Repeated bindings consume the budget again. */
    fun validateRenderedWork(root: JSONObject, state: JSONObject) = validateRenderedWork(root,state,null)
    private fun validateRenderedWork(root: JSONObject,state: JSONObject,trace: ToolUiValidationTrace?) {
        var structural=0
        fun structure(node: JSONObject,depth: Int) {
            require(++structural <= 256 && depth <= 8) {"Native UI exceeds its node or depth limit."}
            node.optJSONObject("rowTemplate")?.let {structure(it,depth+1)}
            node.optJSONArray("children")?.let {children -> repeat(children.length()) {structure(children.getJSONObject(it),depth+1)}}
        }
        structure(root,0)
        var work=0; var images=0
        fun visit(node: JSONObject, values: JSONObject, depth: Int, location: String) {
            require(depth <= 8) {"Native UI exceeds its depth limit."}
            work++
            when(node.getString("type")) {
                "list","table","groupedList","lazyList","lazyGrid" -> {
                    val rows=records(node,values); val columns=node.optJSONArray("columns")?.length() ?: 1
                    val template=node.optJSONObject("rowTemplate")
                    if(template != null) repeat(rows.length()) {visit(template,rows.getJSONObject(it),depth+1,"$location.rowTemplate")}
                    else work+=rows.length()*(1+columns+(node.optJSONArray("rowActions")?.length() ?: 0))+columns
                    if(node.has("groupBy")) work+=rows.length()
                    if(node.has("reorder")) work+=rows.length()
                }
                "text" -> work+=node.optJSONArray("spans")?.length() ?: 0
                in choiceTypes -> work+=node.getJSONArray("options").length()
                "chart" -> work+=(value(values,node.getString("categories")) as JSONArray).length()*node.getJSONArray("series").length()
                "image" -> require(++images <= MAX_IMAGES) {"Native UI exceeds its image limit ($MAX_IMAGES)."}
            }
            trace?.at("render_work_limit",location,"render_work_at_most_2048",node,work)
            require(work <= MAX_RENDER_WORK) {"Native UI exceeds its rendered-work limit ($MAX_RENDER_WORK)."}
            node.optJSONArray("children")?.let {children ->
                require(children.length() in 1..50)
                repeat(children.length()) {visit(children.getJSONObject(it),values,depth+1,"$location.children[$it]")}
            }
        }
        visit(root,state,0,"$.ui")
    }
    private fun validateLayout(layout: JSONObject,parent: String) {
        require(ToolValueSchema.keys(layout).all {it in setOf("width","height","minWidth","maxWidth","minHeight","maxHeight","aspectRatio","fillWidth","weight","align","insets")})
        for(axis in listOf("Width","Height")) {
            val size=axis.replaceFirstChar {it.lowercaseChar()}; val limit=if(axis == "Width") 1024 else 2048
            for(name in listOf(size,"min$axis","max$axis")) if(layout.has(name)) require(layout.get(name) is Int && layout.getInt(name) in 0..limit)
            val low=layout.optInt("min$axis",0); val high=layout.optInt("max$axis",limit)
            require(low <= high && (!layout.has(size) || layout.getInt(size) in low..high)) {"Layout constraints must agree."}
        }
        if(layout.has("aspectRatio")) require(numeric(layout,"aspectRatio",1.0) in 0.1..10.0)
        if(layout.has("fillWidth")) require(layout.get("fillWidth") is Boolean)
        if(layout.has("weight")) require(parent in setOf("row","column") && numeric(layout,"weight",1.0) in 0.1..10.0) {"Weight requires a direct column or nonwrapping row parent."}
        if(layout.has("align")) require(parent in setOf("row","column","box") && layout.get("align") is String && layout.getString("align") in setOf("start","center","end"))
        if(layout.has("insets")) require(layout.get("insets") is String && layout.getString("insets") in setOf("none","safeDrawing","ime","safeDrawingAndIme"))
    }
    private fun validateTheme(node: JSONObject) {
        require(!node.has("palette") && !node.has("mode")) {"Theme uses the system color scheme. Remove palette and mode."}
        if(node.has("typeScale")) require(numeric(node,"typeScale",1.0) in 0.85..1.5)
        if(node.has("cornerRadius")) require(node.get("cornerRadius") is Int && node.getInt("cornerRadius") in 0..32)
    }
    private fun validateText(node: JSONObject,actions: MutableSet<String>,register: Boolean,stage: Boolean) {
        if(node.has("maxLines")) require(node.get("maxLines") is Int && node.getInt("maxLines") in 1..100)
        if(node.has("overflow")) require(node.get("overflow") is String && node.getString("overflow") in setOf("clip","ellipsis"))
        for(name in listOf("softWrap","selectable")) if(node.has(name)) require(node.get(name) is Boolean)
        if(node.has("lineHeight")) require(numeric(node,"lineHeight",24.0) in 8.0..96.0)
        if(node.has("spans")) {
            require(!node.has("bind") && !node.has("format")) {"Rich spans require static text."}
            val spans=node.getJSONArray("spans"); require(spans.length() in 1..32)
            var previousEnd=0; val text=node.getString("text")
            repeat(spans.length()) {index ->
                val span=spans.getJSONObject(index)
                require(ToolValueSchema.keys(span).all {it in setOf("start","end","emphasis","tone","action","url")})
                require(span.get("start") is Int && span.get("end") is Int)
                val start=span.getInt("start"); val end=span.getInt("end")
                require(start >= previousEnd && end > start && end <= text.length) {"Span ranges must be ordered, disjoint, and inside text."}
                // UTF-16 offsets must not split a supplementary character.
                for(offset in listOf(start,end)) require(offset == 0 || offset == text.length || !Character.isLowSurrogate(text[offset]) || !Character.isHighSurrogate(text[offset-1]))
                previousEnd=end
                if(span.has("emphasis")) require(span.get("emphasis") is String && span.getString("emphasis") in setOf("bold","italic","code"))
                if(span.has("tone")) require(span.get("tone") is String && span.getString("tone") in tones)
                require(span.has("action") == span.has("url")) {"A link requires action and url."}
                if(span.has("action")) {
                    require(!stage && span.get("action") is String)
                    val id=span.getString("action")
                    require(id.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,47}")) && (!register || actions.add(id))) {"Action IDs must be unique."}
                    com.alpha.assistant.capabilities.CapabilityCatalog.definition("link.open").validate(JSONObject().put("url",span.get("url")))
                }
            }
        }
    }
    private fun textLink(node: JSONObject,id: String): JSONObject? {
        if(node.optString("type") != "text") return null
        val spans=node.optJSONArray("spans") ?: return null
        return (0 until spans.length()).map {spans.getJSONObject(it)}.firstOrNull {it.optString("action") == id}
    }
    private fun registerAction(value: Any,actions: MutableSet<String>,register: Boolean) {
        require(value is String && value.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,47}")) && (!register || actions.add(value))) {"Action IDs must be unique."}
    }
    private fun validateInteractions(node: JSONObject,actions: MutableSet<String>,register: Boolean,stage: Boolean) {
        node.optJSONObject("interactions")?.let {events ->
            require(!stage && events.length() > 0 && ToolValueSchema.keys(events).all {it in setOf("click","longClick","doubleClick","focus","keys")})
            require(node.has("text") || node.optJSONObject("semantics")?.has("label") == true) {"Interactions require an accessible label."}
            require(!node.has("action") && !node.has("dismissAction") && !node.has("rowActions")) {"Use interactions on a separate native node."}
            events.keys().forEach {key -> if(key == "keys") {
                val keys=events.getJSONObject(key);require(keys.length() > 0 && ToolValueSchema.keys(keys).all {it in interactionKeys})
                keys.keys().forEach {registerAction(keys.get(it),actions,register)}
            } else registerAction(events.get(key),actions,register)}
        }
        if(node.has("interactions")) require(node.get("interactions") is JSONObject)
        node.optJSONObject("semantics")?.let {semantics ->
            require(ToolValueSchema.keys(semantics).all {it in setOf("label","stateDescription","heading","liveRegion")})
            for(key in listOf("label","stateDescription")) if(semantics.has(key)) require(semantics.get(key) is String && semantics.getString(key).length in 1..300)
            if(semantics.has("heading")) require(semantics.get("heading") is Boolean)
            if(semantics.has("liveRegion")) require(semantics.get("liveRegion") is String && semantics.getString("liveRegion") in setOf("polite","assertive"))
        }
        if(node.has("semantics")) require(node.get("semantics") is JSONObject)
    }
    fun interactionActions(node: JSONObject): Map<String,String> = buildMap {
        node.optJSONObject("interactions")?.let {events -> events.keys().forEach {key ->
            if(key == "keys") {val keys=events.getJSONObject(key);keys.keys().forEach {put("key:$it",keys.getString(it))}}
            else put(key,events.getString(key))
        }}
    }
    private fun validChoice(node: JSONObject,payload: Any?): Boolean {
        fun member(value: Any?)=scalarValue(value ?: JSONObject.NULL) && (0 until options(node).length()).any {matches(options(node).getJSONObject(it).get("value"),value)}
        if(node.getString("type") != "multiChoice") return member(payload)
        if(payload !is JSONArray || payload.length() > options(node).length()) return false
        return (0 until payload.length()).all {i -> member(payload.get(i)) && (0 until i).none {matches(payload.get(it),payload.get(i))}}
    }
    private fun validPage(value: Any?,count: Int)=value is Number && value.toDouble().isFinite() && value.toDouble() % 1 == 0.0 && value.toDouble() in 0.0..(count-1).toDouble()
    private fun validRange(node: JSONObject,value: Any?): Boolean {
        if(value !is JSONArray || value.length() != 2) return false
        if(node.getString("type") == "dateRangeInput") return validDate(value.get(0)) && validDate(value.get(1)) && value.getString(0) >= "0001-01-01" && value.getString(0) <= value.getString(1)
        val (low,high)=bounds(node)
        return (0..1).all {value.get(it) is Number && value.getDouble(it).isFinite() && value.getDouble(it) in low..high} && value.getDouble(0) <= value.getDouble(1)
    }
    val motionPresets=setOf("tumble","flip","shake","bounce","pop","fade")
    fun validatePose(pose: JSONArray) {
        require(pose.length() == 8) {"Pose needs eight finite numbers."}
        repeat(8) {index ->
            val value=pose.get(index); require(value is Number && value.toDouble().isFinite())
            require(value.toDouble() in when(index) {6 -> 0.01..10.0; 7 -> 0.0..1.0; 0,1,2 -> -360000.0..360000.0; else -> -4096.0..4096.0})
        }
    }
    private fun validTime(value: Any?) = value is Number && value.toDouble().isFinite() && value.toDouble() in 0.0..1439.0 && value.toDouble() % 1.0 == 0.0

    internal data class TimerBinding(val countdown: Boolean, val timestampMillis: Long) {
        fun secondsAt(nowMillis: Long): Long? {
            if (timestampMillis == 0L) return null
            val now = nowMillis.coerceIn(0L, MAX_TIMER_TIMESTAMP)
            return if (countdown) ((timestampMillis - now).coerceAtLeast(0L) + 999L) / 1000L
                else (now - timestampMillis).coerceAtLeast(0L) / 1000L
        }
    }

    internal fun timerBinding(node: JSONObject, state: JSONObject): TimerBinding? {
        val mode = node.opt("mode")
        if (mode !is String || mode !in timerModes) return null
        val timestamp = runCatching { timerTimestamp(value(state, node.getString("bind"))) }.getOrNull() ?: return null
        return TimerBinding(mode == "countdown", timestamp)
    }

    private fun timerTimestamp(value: Any?): Long? {
        val number = (value as? Number)?.toDouble() ?: return null
        return number.takeIf { it in 0.0..MAX_TIMER_TIMESTAMP.toDouble() && it % 1.0 == 0.0 }?.toLong()
    }
    private fun validateChart(node: JSONObject, fields: JSONObject, state: JSONObject,trace: ToolUiValidationTrace?,location: String,schemaOnly: Boolean = false) {
        trace?.at("chart_kind","$location.kind","line_bar_area_or_pie",node.opt("kind"))
        val kind = node.get("kind")
        require(kind is String && kind in setOf("line", "bar", "area", "pie"))
        val pie = kind == "pie"
        trace?.at("chart_categories","$location.categories","scalar_array_binding",node.opt("categories"))
        val categorySchema=schema(fields,node.getString("categories"))
        require(categorySchema is JSONObject && categorySchema.getString("type") == "array" && categorySchema.get("items") in setOf("string","number"))
        val categories=if(schemaOnly) JSONArray() else value(state,node.getString("categories")) as JSONArray
        trace?.at("chart_series","$location.series",if (pie) "series_1_points_256" else "series_1_2_points_256",node.opt("series"),node.optJSONArray("series")?.length())
        val series=node.getJSONArray("series")
        require(series.length() in 1..(if (pie) 1 else 2) && categories.length()*series.length() <= 256)
        repeat(series.length()) {
            trace?.at("chart_series_entry","$location.series[$it]","aligned_numeric_array",series.opt(it))
            val entry=series.getJSONObject(it); require(ToolValueSchema.keys(entry) == setOf("text","bind"))
            require(entry.get("text") is String && entry.getString("text").length in 1..100)
            val contract=schema(fields,entry.getString("bind")); require(contract is JSONObject && contract.getString("type") == "array" && contract.get("items") == "number")
            val values=if(schemaOnly) JSONArray() else value(state,entry.getString("bind")) as JSONArray
            require(values.length() == categories.length()); if(!schemaOnly) ToolValueSchema.validate(contract,values)
            if (pie) {
                trace?.at("chart_pie_values","$location.series[$it].bind","nonnegative_numeric_array",values,values.length())
                require((0 until values.length()).all { index -> values.getDouble(index) >= 0.0 })
            }
        }
        trace?.at("chart_category_values","$location.categories","declared_category_values",categories,categories.length())
        if(!schemaOnly) ToolValueSchema.validate(categorySchema,categories)
    }
    fun chartRange(node: JSONObject, state: JSONObject): Pair<Double,Double> {
        var low=Double.POSITIVE_INFINITY; var high=Double.NEGATIVE_INFINITY
        val series=node.getJSONArray("series")
        repeat(series.length()) { index -> val values=value(state,series.getJSONObject(index).getString("bind")) as JSONArray
            repeat(values.length()) {val v=values.getDouble(it); low=min(low,v); high=max(high,v)} }
        if(!low.isFinite()) return 0.0 to 1.0
        if(node.getString("kind") == "bar") {low=min(0.0,low); high=max(0.0,high)}
        if(low == high) {val margin=max(1.0,kotlin.math.abs(low)*0.05); low-=margin; high+=margin}
        if(node.getString("kind") == "area") {low=min(0.0,low); high=max(0.0,high)}
        return low to high
    }
    private fun routeIds(node: JSONObject): List<String> {
        val ids = node.getJSONArray("routes")
        require(ids.length() in 1..MAX_ROUTES)
        return List(ids.length()) { ids.get(it) as String }
    }
    internal fun routeStack(node: JSONObject, state: JSONObject): List<String> {
        val ids = routeIds(node)
        val stack = value(state, node.getString("bind")) as JSONArray
        require(stack.length() in 1..MAX_ROUTES && stack.get(0) == ids.first()) { "The stack must start at its declared root." }
        return List(stack.length()) { stack.get(it) as String }.also { visits ->
            require(visits.all { it in ids }) { "The stack contains an undeclared route." }
        }
    }
    internal fun routeChild(node: JSONObject, id: String): JSONObject =
        node.getJSONArray("children").getJSONObject(routeIds(node).indexOf(id))

    fun action(root: JSONObject, id: String): JSONObject? {
        if(!visible(root) || !enabled(root)) return null
        if(root.optString("action") == id || root.optString("dismissAction") == id || root.optJSONObject("reorder")?.optString("action") == id || id in interactionActions(root).values || textLink(root,id) != null) return root
        root.optJSONArray("rowActions")?.let {actions ->
            if((0 until actions.length()).any {actions.getJSONObject(it).getString("action") == id}) return root
        }
        root.optJSONObject("rowTemplate")?.let {action(it,id)?.let {found -> return found}}
        root.optJSONArray("children")?.let {children -> repeat(children.length()) {action(children.getJSONObject(it),id)?.let {found -> return found}}}
        return null
    }
    fun validateAction(root: JSONObject, id: String, payload: Any?, state: JSONObject): JSONObject =
        validateAction(root, id, payload, state, ActionDelivery.Ordinary)

    /** Validates a live delivery or its explicit Apply fallback. Both use the restricted event. */
    fun validateLiveInput(root: JSONObject, id: String, payload: Any?, state: JSONObject): JSONObject =
        validateAction(root, id, payload, state, ActionDelivery.LiveInput)

    /** Resolves the active control and its binding. The host must separately validate the lease and media access. */
    internal fun validateVideoAction(root: JSONObject, id: String, resourceId: String, state: JSONObject): JSONObject =
        validateAction(root, id, resourceId, state, ActionDelivery.Video)

    internal fun validateForegroundTimer(root: JSONObject, id: String, state: JSONObject): ForegroundTimerSpec =
        ForegroundTimerSpec.parse(validateAction(root, id, null, state, ActionDelivery.ForegroundTimer))

    private enum class ActionDelivery { Ordinary, LiveInput, Video, ForegroundTimer }

    private fun validateAction(root: JSONObject, id: String, payload: Any?, state: JSONObject, delivery: ActionDelivery): JSONObject {
        fun resolve(node: JSONObject, values: JSONObject, raw: Any?): Triple<JSONObject,JSONObject,Any?>? {
            if(!visible(node,values) || !enabled(node,values)) return null
            if(node.optString("type") == "transform") {
                val pose=NativeLayerMath.target(node,values)
                if(pose[7] == 0.0 || (node.optBoolean("solid") && kotlin.math.cos(pose[0]*Math.PI/180)*kotlin.math.cos(pose[1]*Math.PI/180)<0)) return null
            }
            node.optJSONObject("rowTemplate")?.let {template ->
                if(action(template,id) != null) {
                    require(raw is JSONObject && ToolValueSchema.keys(raw) == setOf("key","value") && raw.get("key") is String) {"Row action needs key and value."}
                    val rows=records(node,values)
                    val row=(0 until rows.length()).map {rows.getJSONObject(it)}.firstOrNull {stableKey(it) == raw.getString("key")}
                        ?: error("Row action is unavailable.")
                    return resolve(template,row,raw.get("value").takeUnless {it == JSONObject.NULL})
                }
            }
            if(node.optString("type") == "transition" && value(values,node.getString("bind")) != true) return null
            if(node.optString("dismissAction") == id || id in interactionActions(node).values) return Triple(node,values,raw)
            if(node.optString("action") == id || node.optJSONObject("reorder")?.optString("action") == id || textLink(node,id) != null || node.optJSONArray("rowActions")?.let {actions -> (0 until actions.length()).any {actions.getJSONObject(it).getString("action") == id}} == true)
                return Triple(node,values,raw)
            node.optJSONArray("children")?.let {children ->
                if(node.optString("type") == "pager") return resolve(children.getJSONObject((value(values,node.getString("bind")) as Number).toInt()),values,raw)
                if (node.optString("type") == "routeStack") {
                    val stack = routeStack(node, values)
                    return resolve(routeChild(node, stack.last()), values, raw)
                }
                repeat(children.length()) {resolve(children.getJSONObject(it),values,raw)?.let {found -> return found}}
            }
            return null
        }
        val (node,currentState,currentPayload)=checkNotNull(resolve(root,state,payload)) {"Action is unavailable."}
        val video = node.getString("type") == "video"
        when (delivery) {
            ActionDelivery.Ordinary -> {
                require(!isLiveInput(node)) { "This control requires live input delivery." }
                require(!video) { "This control requires native video delivery." }
                require(node.getString("type") != "timer") { "This control requires native timer delivery." }
            }
            ActionDelivery.LiveInput -> require(isLiveInput(node)) { "Live input is unavailable." }
            ActionDelivery.Video -> require(video) { "Video control is unavailable." }
            ActionDelivery.ForegroundTimer -> {
                require(node.getString("type") == "timer" && node.optString("action") == id && node.has("foreground"))
                return node
            }
        }
        return validateActionValue(node,id,currentPayload,currentState)
    }
    private fun validateActionValue(node: JSONObject,id: String,payload: Any?,state: JSONObject): JSONObject {
        val type=node.getString("type"); component(type)
        if(node.optString("dismissAction") == id || id in interactionActions(node).values) {
            require(if(interactionActions(node)["focus"] == id) payload is Boolean else payload == null) {"Invalid interaction value."}
            return node
        }
        node.optJSONObject("reorder")?.takeIf { it.getString("action") == id }?.let { reorder ->
            require(payload is JSONObject && ToolValueSchema.keys(payload) == setOf("sourceKey", "destinationKey")) {
                "Reorder needs source and destination keys."
            }
            val source = payload.get("sourceKey")
            val destination = payload.get("destinationKey")
            require(source is String && destination is String && source != destination) { "Reorder keys must be distinct strings." }
            val rows = records(node, state)
            fun availableKey(key: String) = (0 until rows.length()).any { index ->
                val row = rows.getJSONObject(index)
                stableKey(row) == key && rowReorderAvailable(reorder, row)
            }
            require(availableKey(source) && availableKey(destination)) {
                "Reorder source or destination is unavailable."
            }
            return node
        }
        require(when(type) {
            "button","actionChip","icon","listItem","timelineItem","snackbar","backHandler","navigation","calendar","medicine" -> payload == null
            "video" -> payload is String && videoHandle.matches(payload) && payload == value(state, node.getString("bind"))
            "text" -> textLink(node,id)?.let {payload is String && payload == it.getString("url")} == true
            "input","search" -> payload is String && payload.length <= node.optInt("maxLength",2048)
            "toggle","checkbox","chip" -> payload is Boolean
            "timeInput" -> validTime(payload)
            "dateInput" -> validDate(payload)
            "numberInput","slider" -> payload is Number && payload.toDouble().isFinite() && payload.toDouble() in bounds(node).first..bounds(node).second
            in choiceTypes -> validChoice(node,payload)
            "rangeSlider","dateRangeInput" -> validRange(node,payload)
            "pager" -> validPage(payload,node.getJSONArray("children").length())
            "routeStack" -> {
                val stack = routeStack(node, state)
                stack.size > 1 && payload is JSONArray && payload.length() == stack.size &&
                    stack.indices.all { payload.opt(it) == stack[it] }
            }
            "list","table","groupedList","lazyList","lazyGrid" -> {
                val rows=records(node,state)
                val row=if(payload is String) (0 until rows.length()).map {rows.getJSONObject(it)}.firstOrNull {stableKey(it) == payload} else null
                val rowAction=node.optJSONArray("rowActions")?.let {actions ->
                    (0 until actions.length()).map {actions.getJSONObject(it)}.firstOrNull {it.getString("action") == id}
                }
                row != null && (rowAction == null || rowActionEnabled(rowAction,row))
            }
            else -> false
        }) {"Invalid action value."}
        return node
    }
    fun rowActionEnabled(action: JSONObject, record: JSONObject): Boolean =
        !action.has("enabledField") || value(record,action.getString("enabledField")) == true
    internal fun rowReorderVisible(reorder: JSONObject, record: JSONObject): Boolean =
        !reorder.has("visibleField") || value(record, reorder.getString("visibleField")) == true
    internal fun rowReorderAvailable(reorder: JSONObject, record: JSONObject): Boolean =
        rowReorderVisible(reorder, record) && rowActionEnabled(reorder, record)
    fun display(node: JSONObject, state: JSONObject): String {
        val label=node.optString("text")
        if(!node.has("bind")) return label
        val v=value(state,node.getString("bind"))
        val text=if(node.optString("format") == "clock" && v is Number) "%02d:%02d".format(java.util.Locale.ROOT,v.toInt()/60,v.toInt()%60) else v.toString()
        return "$label: $text"
    }
    fun widgetNodes(root: JSONObject,state: JSONObject? = null): List<JSONObject> = buildList {
        fun visit(node: JSONObject, parentEnabled: Boolean) {
            if(!visible(node) || (state != null && !visible(node,state))) return
            val enabled=parentEnabled && enabled(node) && (state == null || enabled(node,state))
            if (node.getString("type") == "routeStack") {
                add(JSONObject().put("type", "routeStack").put("text", node.getString("text")).put("enabled", enabled))
                return
            }
            if (node.getString("type") == "video") {
                add(JSONObject().put("type", "video").put("text", node.getString("text")).put("enabled", false))
                return
            }
            if(node.has("interactions") || node.optString("type") == "transition") {
                add(JSONObject().put("type","text").put("text","${node.optString("text","Interactive content")} · Open in Alphy")); return
            }
            when(component(node.getString("type")).projection) {
                Projection.CONTAINER -> {if(node.optString("type") in setOf("stage","transform")) {add(JSONObject().put("type","text").put("text","Animated content · Open in Alphy")); return}; val children=node.getJSONArray("children"); repeat(children.length()) {visit(children.getJSONObject(it),enabled)}}
                Projection.OMIT -> Unit
                else -> add(JSONObject(node.toString()).put("enabled",enabled))
            }
        }
        visit(root,true)
    }
    fun widgetText(text: String): String = resourceHandle.replace(text,"Selected resource")
    fun widgetLabel(node: JSONObject, state: JSONObject): String {
        if(node.getString("type") == "text" && node.has("spans")) return "${widgetText(node.getString("text"))} · Open in Alphy"
        if(node.getString("type") == "calendar") return "Calendar · Open in Alphy"
        if(node.getString("type") == "medicine") return "Medicine · Open in Alphy"
        if(node.getString("type") == "navigation") return "Navigation · Open in Alphy"
        val label=when(component(node.getString("type")).projection) {
            Projection.SUMMARY -> "${node.optString("text")} · Open in Alphy"
            else -> display(node,state)
        }
        return if(resourceHandle.containsMatchIn(label)) "${widgetText(node.optString("text"))} · Open in Alphy" else label
    }
    fun modelGuidance(): String = """Give every non-container node a nonempty text label, even when the node is hidden. A bind supplies a value. It does not supply the text label. The only non-container exceptions are ${unlabeledLeaves.joinToString(", ")}.
Place allowed tone and emphasis inside the component's style object. For example, use {"type":"metric","text":"Total","bind":"total","style":{"emphasis":"number"}}. Omit style if it is unnecessary.
Every supplied text value must contain 1 to 300 characters. For a checklist, use short, meaningful labels on bound row text, checkboxes, and inputs. Put empty strings in initialState when the data starts empty. Omit text on nodes where the catalog permits an unlabeled node.
Native UI catalog: ${components.keys.joinToString(", ")}. Properties by type: ${components.values.joinToString("; ") {"${it.type}=${(it.properties-common).sorted().joinToString(",")} (style: ${it.styles.sorted().joinToString(",")})"}}.
Binding schema types by component: ${components.values.filter {it.binding.isNotEmpty()}.joinToString("; ") {"${it.type}=${it.binding.sorted().joinToString("|")}"}}.
Required bind: ${components.values.filter {it.binding.isNotEmpty() && it.type !in optionalBindings}.joinToString(", ") {it.type}}. Optional bind: ${components.values.filter {it.binding.isNotEmpty() && it.type in optionalBindings}.joinToString(", ") {it.type}}. Every supplied bind must resolve in both stateSchema and state. Use only a compatible schema type from that component's binding list. Lists and arrays need structured schema objects, not scalar aliases.
Containers require children (1..50). Declared calendar, medicine, and navigation operations follow Android permissions and pickers. Alphy requires no additional device grant. medicine has type, action, capability, args and common style/flags only, without text. Use implemented medicine.* schemas. Native code displays exact selected values. Echo input.event.medicine in one effect. Only its foreground native click authorizes direct execution. Never invent medicine schedules, doses, meals, or intake.  calendar has exactly type, action, capability, args plus common flags/style, with no generated text. Its capability is calendar.select, calendar.createOwned, calendar.readOwned, calendar.deleteOwned, calendar.deleteAllOwned or calendar.open. Args follow the closed capability schema. Native code displays the operation and exact event details. Echo input.event.calendar={capability,args} in exactly one effect. deleteAllOwned takes only version:1. Its native control displays and freezes every owned-event detail. Scripts cannot supply its deletion targets. Only a native foreground click authorizes direct calendar execution. navigation has exactly type, action, capability, args plus common flags/style. Its capability is maps.markReached or maps.route; args exactly follow that capability schema. It has no generated text property. Native code displays the operation, selected stop/coordinates/system and route mode. It sends null and input.event.navigation={capability,args}. Echo that exact selection in one navigation effect. Only this dedicated foreground control can authorize direct navigation. Generic buttons, inputs and row actions do not authorize automatic navigation. Optional visible/enabled are booleans. Hidden nodes still validate. UI maximum 256 structural nodes, depth 8, 32 KiB. Aggregate rendered work is at most $MAX_RENDER_WORK units (each structural node, expanded record row/cell, option, and chart point consumes work). Repeated bindings consume work again. At most $MAX_IMAGES image nodes are allowed, each decoded within ${MAX_IMAGE_EDGE}x$MAX_IMAGE_EDGE pixels. Hidden nodes also consume these limits. Exceeding a limit rejects the UI without truncation. Every actionable control has a unique ASCII action ID. Controls never change state directly. button/icon/listItem/snackbar/backHandler send null. input sends a string with maxLength <=2048. toggle/checkbox send boolean. numberInput and slider send a finite number within min/max. numberInput uses Apply. slider defaults 0..100. Optional positive step must be no greater than max minus min. It sets the slider increment or adds increase and decrease buttons to numberInput. The numberInput buttons change the draft. Apply sends the value. timeInput binds minutes since local midnight (integer 0..1439); dateInput binds ISO YYYY-MM-DD without a timezone. select/tabs require options:[{text:'Label',value:'scalar'}], same type as bind, 1..30 unique values, and send the selected current value.
Only input and slider accept inputMode. Omit it or use apply for an ordinary Apply action. Use live only with a host that supports live input delivery. The live mode and its explicit Apply fallback both use event.type liveInput. This event can update state, UI, and drawing. It cannot request effects, records, frames, timers, or playback. Row template editors send the current stable key and value. In mixed mini-apps, use live only in fill-owned controls. A widget opens the mini-app for input.
Labeled native leaves include {"type":"text","text":"Score","bind":"score"} and {"type":"icon","name":"Plus","text":"Add","action":"add"}. An icon name does not replace its accessible text label.
video requires text, action, and bind to a declared string. Use an empty string until the user selects a video. A selected video uses an opaque owner-scoped resource ID of 64 lowercase hexadecimal characters. URLs, paths, direct handles on the node, row templates, and key interactions are unsupported. Only a native Play control in an interactive host can request playback of the current binding. Previews and widgets show a static summary.
timer requires text, mode countdown or stopwatch, and bind to a declared number. Store an integer Unix timestamp in milliseconds between 0 and $MAX_TIMER_TIMESTAMP. Zero means idle. Bind the end time for countdown or the start time for stopwatch. Countdown shows remaining time and expiry. Stopwatch shows elapsed time from the start. Native code reads the clock while the control is visible, including on ordinary desktop tiles. A timer sends no events and does not change state. Use a separate native action to write a new timestamp. Home-screen widgets show a summary. For a saved foreground countdown with a terminal alert, omit bind and use mode countdown, a unique action ID, and foreground:{durationMs:1000..86400000,vibrationMs:1..2000?,toneMs:1..1000?,notification:{title,text}?}. Supply at least one alert. Declare each alert capability in the mini-app. The host displays Start, Reset, and Resume and owns the timer state. Do not handle this action ID in JavaScript. The host pauses the timer when the control is hidden and waits for an explicit Resume. Preview, chat, desktop, and widgets show a summary.
A dateInput validates the bound state value as a real ISO calendar date. Illustrative example only: stateSchema={"visitDate":"string"}, initialState={"visitDate":"2028-02-29"}, ui={"type":"dateInput","text":"Visit date","bind":"visitDate","action":"visitDate"}. Keep existing saved dates unchanged. Do not invent dates or copy the illustrative date as a default. When the date is unknown, keep an ordinary string field and use a labeled input or text control. Empty strings, impossible dates, and timestamps cannot bind dateInput.
Bindings use declared dotted object paths, never indices, expressions, missing fields, prototype or constructor. text/badge/fact bind scalars. metric binds number or string. metric/text support format number or clock (minute number). list requires stable id records or array page records {key,token,value}. table binds an object array or list and requires columns:[{text:'Label',field:'relative.path'}], at most 8 columns, 50 rows, 400 cells. Optional list columns use the same relative paths. Optional list/table action receives only the currently present stable id/key. Lists also support rowActions:[{action:'searchStop',text:'Search maps',emphasis:'primary',enabledField:'available'},{action:'calendarStop',text:'Open calendar form',emphasis:'secondary'}], at most 3. Every row action ID is unique across the UI, including the optional list selection action. emphasis defaults to secondary. Optional enabledField is a declared relative boolean field in that row. Each row button sends only its current stable id/key in event.value. Resolve the key against current state. Expanded row buttons consume the shared rendered-work budget. Row actions project to a widget summary that opens this mini-app, without record contents. Page values use fields such as value.name. Table arrays without selection do not require keys.
chart requires kind line, bar, area or pie, categories:'arrayPath' (strings or numbers), and series:[{text:'Label',bind:'numericArrayPath'}]. Line, bar and area accept 1..2 series. Pie requires one nonnegative series and shows an empty state when all values are zero. Series lengths must match the category length and total points must not exceed 256. text describes the chart. progress/progressRing bind number within min/max (defaults 0..100). image requires text and either resourceId or string bind containing only an owner-scoped 64-character lowercase hex resource handle (empty bind value shows placeholder), optional fit fit/crop. Images never accept URLs, paths, or URIs. Missing/revoked resources show a placeholder. grid columns 1..4 adapt to width; optional minCellWidth is 48..512 dp. row wraps horizontally by default; wrap:false uses a true Row. flowRow always wraps. scroll provides a bounded viewport. theme preset: ${presets.joinToString(", ")}. icon name/button icon: ${icons.joinToString(", ")}. Use only the style keys listed for that type. Style values: tone default/muted/accent/success/warning/error, padding none/small/medium/large, align start/center/end; gap none/small/medium/large, emphasis body/title/headline/label/number. No arbitrary style properties.
Layout extensions: box overlays children. flowRow wraps children. row wrap:false uses a true horizontal Row. All nodes accept layout with width/minWidth/maxWidth 0..1024 dp, height/minHeight/maxHeight 0..2048 dp, aspectRatio 0.1..10, fillWidth boolean, insets none/safeDrawing/ime/safeDrawingAndIme. Constraints must agree. Only direct column or nonwrapping row children accept weight 0.1..10. Only direct column/nonwrapping row/box children accept layout.align start/center/end. Box alignment means top-start/center/bottom-end. style.arrangement start/center/end/between/around/evenly applies to column/row/flowRow/theme; style.crossAlign start/center/end applies to row/flowRow. Ordered modifiers apply parent weight/alignment, size bounds, width/height, aspect ratio, insets, then padding. They never execute code.
Text supports maxLines 1..100, overflow clip/ellipsis, softWrap boolean, lineHeight 8..96 sp, selectable boolean. Optional spans has 1..32 ordered nonoverlapping ranges with integer start/end inside static text, emphasis bold/italic/code, tone default/muted/accent/success/warning/error. Spans require static text without bind/format. A link span has both action (globally unique) and url, validated by the existing link.open public-HTTPS argument contract. Click sends that exact URL string as an ordinary action value. Return a link.open effect through the existing broker and declared capability. A span never opens a URI or grants privileged authority. Disabled/hidden links reject actions. Rich text uses an exact-app widget summary.
lazyList and lazyGrid bind the same stable record collections as list and accept rowTemplate and rowActions with the same current-key typed events and aggregate work budget. lazyList supports columns and groupBy. lazyGrid columns is an integer 1..4, minCellWidth 48..512 dp; it supports groupBy and spanField, without column descriptors. Both use viewportHeight 96..1024 dp (default 360). selectedKeyBind and scrollToKeyBind refer to declared string fields containing a current stable key or empty string. Selection marks the current row without changing it. A changed scroll target moves the native viewport to that record; empty target issues no command. Host saveable viewport state survives ordinary recomposition/recreation, while a nonempty declared target applies on open. Virtualization does not remove aggregate validation limits. Lazy collections project only an exact-app widget summary without row contents.
Theme requires preset. It uses the system light or dark color scheme. Do not include palette or mode. Optional typeScale is 0.85..1.5 and cornerRadius is 0..32 dp. Font scaling remains active. Shapes and typography apply only inside the theme subtree.
Material controls: button variant filled/outlined/text/tonal/elevated/fab/extendedFab chooses a native button. fab requires icon and text. actionChip variant assist/suggestion sends null. chip variant filter/input binds boolean and sends boolean; optional dismissAction sends null through a separate remove affordance. singleChoice uses radio buttons. segmented uses 1..6 typed options. navigationBar/navigationRail/menu use typed options like select. multiChoice binds an array of scalar option values and sends the complete unique selected array, including empty. Other choice controls send one option value. All options remain finite, typed, unique, and current. rangeSlider binds exactly two ordered finite numbers inside min/max (defaults 0..100), optional positive step, and sends both on Apply. dateRangeInput binds exactly two real ordered ISO dates in years 0001..9999 and sends both on native confirmation. search is a labeled bounded input with Search action. input/search accept keyboard text/email/uri/phone/number/password, imeAction done/next/search, maxLines 1..8. These configure input only and never grant access. progress/progressRing can use indeterminate:true without bind.
appBar/bottomBar contain native children for local actions. dialog/bottomSheet require text, visibleBind to a declared boolean, dismissAction, and children. Dismiss sends null through that action. Child actions are unavailable while hidden. The script controls visibility. pager requires text, numeric bind to integer page index, action, 1..10 children, viewportHeight 96..1024. Page changes send the index. Only current page child actions are available. Native previous/next buttons provide accessible alternatives to swipe. Each page retains its normal node validation and work cost.
routeStack requires text, bind, action, routes and children. Declare one stack per mini-app and use it without backHandler. routes has 1..$MAX_ROUTES distinct ASCII IDs, with the root first, and children has one native subtree per ID in the same order. Bind a declared string array with maxItems at most $MAX_ROUTES. The nonempty stack starts at the root and contains only declared IDs. Repeated visits are valid. Use ordinary active-route actions to push routes. Back is available only above the root and sends the exact current stack array in event.value. Pop the last entry in the script and keep the root. All routes validate and share the native UI limits. Only the active route can send actions. Widgets show a summary and open the exact mini-app.
Use one screen title and open sections with short headings. Use a metric for the focal result and an explicit card to group content that needs a surface. List items and ordinary record lists use divider rows. Use neutral empty states with a short message and a relevant next action. Give a status badge or banner a success, warning or error tone only when its text describes that status. The renderer adds an icon for these tones. Editorial changes heading fonts. Technical changes metric values. Body text and controls use system font families. In a row or flowRow with several visible buttons, the first enabled unstyled button is primary unless an enabled explicit filled, fab or extendedFab button already reserves that emphasis. Other unstyled peers become outlined. Explicit variants keep their style. Use explicit variants when the hierarchy should differ.
All nodes accept optional semantics with label/stateDescription (1..300 chars), heading boolean, liveRegion polite/assertive. Optional interactions maps click/longClick/doubleClick/focus to globally unique action IDs, plus keys:{enter/space/escape/up/down/left/right:actionId}. Use a separate node without action/dismissAction/rowActions. Give it text or semantics.label. Click/long/double/key events send null. Focus changes send boolean. Long/double gestures expose labeled accessibility alternatives. Hidden or disabled ancestors reject all events. Gestures never imply network, files, or native capability authority.
transition wraps children with boolean bind, preset fade/fadeSlide/expand, durationMs 0..5000 (default 250). It animates visibility and size. Hidden transition actions reject immediately, including during exit. Reduced motion uses zero duration. lazyList accepts stickyHeaders:true only with groupBy. lazyGrid supports groupBy and spanField, a relative integer numeric field from 1..columns, clamped only to responsive native column count. Group headers span all columns. Collections can use swipeAction referencing one declared rowActions action; horizontal swipe sends that current stable key. The same visible labeled row button remains available for accessibility. An ungrouped list or lazyList can declare reorder:{action:'move',labelField:'name',visibleField:'shown',enabledField:'available'}. labelField is a relative string path for the native handle label. Optional visibleField and enabledField are relative boolean paths for reorder availability on both records. Drag and the accessible move menu send event.value={sourceKey,destinationKey}, two current distinct stable keys from that list. Move the source to the destination's current position and preserve stable keys. The script owns the state change. One handle per record shares the rendered-work budget. Modals, pager, choices, ranges, search, transitions and interactions use exact-app widget summaries.
The host supplies the mini-app title and description. Do not repeat them in the UI. Prefer neutral rounded sections, short section labels, two-column metric grids, and readable list columns. Use primary row buttons sparingly. maps.open searches for a place. Label it Search maps, not Navigate. calendar.create opens a form. Label it Open calendar form and never claim the event was saved. Show actual operation results and errors. Do not invent routes, images, records, or completed actions.
Compose reference designs with native controls: hero/header/section-header = section + text; adaptive columns/tile grids/metric groups = grid; tiles/cells = card/listItem + text + icon/divider; grouped rows = listGroup or groupedList; chronological entries = timeline + timelineItem; key-value groups = facts + fact; forms = form + typed controls; action bars = row + button/icon; inset banners = banner + icon/text; empty states = emptyState + icon/text/button; stats/number text/number stats/clock text = metric/text with format; number list item/key-value item = listItem + metric/text; TableRow comes from table columns and bound records; Sparkline = chart kind line. All content remains native and accessible. Advanced controls project to compact widget summaries that open the exact mini-app. Native semantic containers form, listGroup, facts, timeline, banner, emptyState require children. fact displays a label and scalar binding in an adaptive key-value row. timelineItem has text and optional scalar bind, detail, time, detailBind, timeBind, action. detailBind/timeBind reference scalar paths. groupedList binds stable rows and requires groupBy, a relative scalar field. list/groupedList can use rowTemplate instead of columns/action/rowActions. A rowTemplate is a native subtree whose bindings, visibleBind, enabledBind, and motion trigger are relative to each row. It supports ordinary native containers and typed controls, but no nested collections, images, stage, backHandler, or privileged calendar/medicine/navigation controls. Template action IDs remain globally unique. A template action sends event.value={key:'current stable key',value:typed control value or null}. Resolve key against current rows before update. Hidden, disabled, removed, or forged row actions fail before execution. Empty rows still require a valid template schema. Expanded template work shares the aggregate budget. All nodes accept visibleBind/enabledBind declared boolean bindings. Hidden nodes still validate and consume limits.
motion wraps a small subtree with preset tumble/flip/shake/bounce/pop/fade and trigger:'numericPath'. A trigger change animates decoration only. Initial render stays still unless animateInitial:true. Motion cannot nest. transform wraps children and uses either pose:[rotateX,rotateY,rotateZ,translateX,translateY,translateZ,scale,opacity] or poseBind:'numericArrayPath', exactly eight finite numbers. Optional durationMs 0..10000 glides property changes without initial animation. Optional size 0..1024 sets a square layer. Optional solid:true hides back faces in a stage. stage requires transform children, optional height 64..1024, perspective 128..4096 (default 900), floor boolean. Stage transforms compose parent * child, share perspective, and sort layers by depth. Stage content is noninteractive. Put accessible controls outside the stage. Reduced motion removes decorative motion and transition delays. Path, Canvas, Motion, Transform, Stage, Surface, and Ticker with uppercase names are not UI node types. The optional top-level surfaces field declares identified drawing surfaces. One artifact source and state serve all native controls and surfaces through the shared runtime contract. When the user requests a playable real-time game, implement the simulation and drawing in that surface. Handle frame, pointer, control, and restart events for actual play, with accessible score and game controls. No HTML, audio, generated Kotlin, Android APIs, network access, or render side effects."""
}
