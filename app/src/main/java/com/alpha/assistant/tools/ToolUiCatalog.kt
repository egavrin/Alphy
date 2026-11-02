package com.alpha.assistant.tools

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
    private val resourceHandle=Regex("[0-9a-fA-F]{64}")
    enum class Projection { CONTAINER, TEXT, LOCAL_ACTION, SUMMARY, OMIT }
    data class Component(val type: String, val properties: Set<String>, val projection: Projection,
        val binding: Set<String> = emptySet(), val actionRequired: Boolean = false, val container: Boolean = false,
        val styles: Set<String> = setOf("tone","emphasis","align","padding"))
    private val common=setOf("type","visible","enabled","style")
    private val scalar=setOf("string","number","boolean")
    val presets=setOf("clean","soft","expressive","editorial","technical","playful")
    val icons=setOf("Menu","History","Plus","Arrow","Close","Back","Cloud","Mic","Grid","Spark","Calendar","Repeat","Settings","File","Link","Chevron","Search","Screen","Camera","Bell","Check")
    private fun entry(type: String, props: Set<String> = emptySet(), projection: Projection = Projection.SUMMARY,
        binding: Set<String> = emptySet(), action: Boolean = false, container: Boolean = false) =
        Component(type,common+props+(if(type in setOf("column","row","grid","scroll","theme","spacer","divider","backHandler")) emptySet() else setOf("text")),projection,binding,action,container,
            when(type) {
                "text","metric" -> setOf("tone","emphasis","align","padding")
                "badge" -> setOf("align","padding")
                "column","theme" -> setOf("align","padding","gap")
                "row","grid","scroll" -> setOf("padding","gap")
                "card","section","listItem" -> setOf("tone","align","padding","gap")
                "spacer","backHandler" -> emptySet()
                else -> setOf("padding")
            })
    val components: Map<String,Component> = buildList {
        for(type in listOf("column","row","grid","scroll","theme","card","section","listItem")) add(entry(type,
            setOf("children") + when(type) {"grid" -> setOf("columns"); "theme" -> setOf("preset"); "listItem" -> setOf("action"); else -> emptySet()},
            Projection.CONTAINER,container=true))
        add(entry("spacer",setOf("size"),Projection.OMIT))
        add(entry("text",setOf("bind","format"),Projection.TEXT,scalar))
        add(entry("metric",setOf("bind","format"),Projection.TEXT,setOf("number","string")))
        add(entry("calendar",setOf("action","capability","args"),Projection.SUMMARY,action=true).let {it.copy(properties=it.properties-"text")})
        add(entry("medicine",setOf("action","capability","args"),Projection.SUMMARY,action=true).let {it.copy(properties=it.properties-"text")})
        add(entry("navigation",setOf("action","capability","args"),Projection.SUMMARY,action=true).let {it.copy(properties=it.properties-"text")})
        add(entry("button",setOf("action","icon"),Projection.LOCAL_ACTION,action=true))
        add(entry("icon",setOf("name","action"),Projection.SUMMARY))
        for(type in listOf("input","numberInput","timeInput","dateInput","toggle","checkbox","slider","select","tabs")) {
            val binding=when(type) {"input","dateInput" -> setOf("string"); "toggle","checkbox" -> setOf("boolean"); "numberInput","timeInput","slider" -> setOf("number"); else -> scalar}
            val props=when(type) {"input" -> setOf("maxLength"); "numberInput","slider" -> setOf("min","max","step"); "select","tabs" -> setOf("options"); else -> emptySet()}
            add(entry(type,setOf("bind","action")+props,if(type == "toggle") Projection.LOCAL_ACTION else Projection.SUMMARY,binding,action=true))
        }
        for(type in listOf("list","table")) add(entry(type,setOf("bind","action","columns") + (if(type == "list") setOf("rowActions") else emptySet()),binding=setOf("list","array")))
        for(type in listOf("progress","progressRing")) add(entry(type,setOf("bind","min","max"),Projection.TEXT,setOf("number")))
        add(entry("chart",setOf("kind","categories","series")))
        add(entry("image",setOf("bind","resourceId","fit"),binding=setOf("string")))
        for(type in listOf("divider","skeleton","spinner")) add(entry(type,projection=Projection.OMIT))
        add(entry("badge",setOf("bind"),Projection.TEXT,scalar))
        add(entry("snackbar",setOf("action")))
        add(entry("backHandler",setOf("action"),Projection.OMIT,action=true))
    }.associateBy {it.type}
    fun component(type: String) = requireNotNull(components[type]) {"Unsupported native component: $type"}
    fun visible(node: JSONObject) = node.optBoolean("visible",true)
    fun enabled(node: JSONObject) = node.optBoolean("enabled",true)
    private fun path(value: String): List<String> = value.split('.').also { require(it.isNotEmpty() && it.all(ToolValueSchema::name)) {"Invalid object binding path."} }
    fun value(state: JSONObject, binding: String): Any {
        var result: Any=state
        for(part in path(binding)) { require(result is JSONObject && result.has(part)) {"Missing object binding: $binding"}; result=result.get(part) }
        return result
    }
    private fun schema(fields: JSONObject, binding: String): Any {
        var result: Any=JSONObject().put("type","object").put("fields",fields)
        for(part in path(binding)) {
            require(result is JSONObject && result.getString("type") == "object") {"Binding must traverse declared objects."}
            val children=result.getJSONObject("fields"); require(children.has(part)) {"Undeclared binding: $binding"}; result=children.get(part)
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
        val number=node.get(name); require(number is Number && number.toDouble().isFinite() && kotlin.math.abs(number.toDouble()) <= 9007199254740991.0); number.toDouble()
    } else default
    fun bounds(node: JSONObject): Pair<Double,Double> = numeric(node,"min",if(node.getString("type") in setOf("progress","progressRing","slider")) 0.0 else -9007199254740991.0) to
        numeric(node,"max",if(node.getString("type") in setOf("progress","progressRing","slider")) 100.0 else 9007199254740991.0)
    private fun validDate(value: Any?) = value is String && value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) && runCatching {LocalDate.parse(value)}.isSuccess
    private fun options(node: JSONObject): JSONArray = node.getJSONArray("options")
    private fun matches(a: Any, b: Any?) = if(a is Number && b is Number) a.toDouble() == b.toDouble() else a == b
    fun validate(root: JSONObject, fields: JSONObject, state: JSONObject): JSONObject = validate(root,fields,state,null)
    internal fun validate(root: JSONObject,fields: JSONObject,state: JSONObject,trace: ToolUiValidationTrace?): JSONObject {
        trace?.at("ui_bytes","$.ui","bytes_at_most_32768",root,root.toString().toByteArray(Charsets.UTF_8).size)
        require(root.toString().toByteArray(Charsets.UTF_8).size <= 32768) {"Native UI exceeds 32 KiB."}
        var count=0; val actions=mutableSetOf<String>()
        fun visit(node: JSONObject, depth: Int, location: String) {
            trace?.at("node_depth_limit",location,"nodes_256_depth_8",node,count+1)
            require(++count <= 256 && depth <= 8) {"Native UI exceeds its node or depth limit."}
            trace?.at("component_type","$location.type","catalog_component",node.opt("type"))
            val type=node.getString("type"); val definition=component(type)
            validateTool(ToolValidationCategory.UI_PROPERTIES) {
                trace?.at("component_properties",location,"catalog_properties",node,node.length())
                require(ToolValueSchema.keys(node).all {it in definition.properties}) {"Unsupported component property."}
                for(flag in listOf("visible","enabled")) if(node.has(flag)) {
                    trace?.at("component_flag","$location.$flag","boolean",node.opt(flag))
                    require(node.get(flag) is Boolean)
                }
                if(node.has("text")) {
                    trace?.at("component_text","$location.text","string_length_1_300",node.opt("text"),(node.opt("text") as? String)?.length)
                    require(node.get("text") is String && node.getString("text").length in 1..300)
                }
                if(node.has("style")) {
                    trace?.at("component_style","$location.style","catalog_style",node.opt("style"))
                    val style=node.getJSONObject("style"); require(ToolValueSchema.keys(style).all {it in definition.styles})
                    style.keys().forEach { key -> require(style.get(key) is String && style.getString(key) in when(key) {
                        "tone" -> setOf("default","muted","accent","success","warning","error")
                        "emphasis" -> setOf("body","title","headline","label","number")
                        "align" -> setOf("start","center","end")
                        "padding","gap" -> setOf("none","small","medium","large")
                        else -> emptySet()
                    }) }
                }
            }
            if(!definition.container && type !in setOf("spacer","divider","skeleton","spinner","backHandler","navigation","calendar","medicine")) {
                trace?.at("accessible_label","$location.text","label_present",node.opt("text"))
                require(node.has("text")) {"Native controls need an accessible label."}
            }
            var binding: Any?=null
            if(node.has("bind") || (definition.binding.isNotEmpty() && type !in setOf("text","metric","badge","image"))) {
                validateTool(ToolValidationCategory.UI_BINDINGS) {
                    trace?.at("component_binding","$location.bind","declared_binding_and_state",node.opt("bind"))
                    require(node.get("bind") is String); binding=schema(fields,node.getString("bind"))
                    require(kind(checkNotNull(binding)) in definition.binding)
                    ToolValueSchema.validate(checkNotNull(binding),value(state,node.getString("bind")))
                }
            }
            if(definition.actionRequired || node.has("action")) validateTool(ToolValidationCategory.UI_ACTIONS) {
                trace?.at("component_action","$location.action","unique_ascii_action",node.opt("action"))
                require(node.get("action") is String); val action=node.getString("action")
                require(action.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,47}")) && actions.add(action)) {"Action IDs must be unique."}
            }
            trace?.at("component_contract",location,"catalog_component_contract",node)
            when(type) {
                "calendar" -> com.alpha.assistant.capabilities.OwnedCalendarContract.request(node)
                "medicine" -> com.alpha.assistant.capabilities.MedicineContract.request(node)
                "navigation" -> com.alpha.assistant.capabilities.NavigationContract.request(node)
                "theme" -> {
                    trace?.at("theme_preset","$location.preset","catalog_preset",node.opt("preset"))
                    require(node.get("preset") is String && node.getString("preset") in presets)
                }
                "grid" -> {
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
                "button" -> {
                    trace?.at("button_icon","$location.icon","catalog_icon",node.opt("icon"))
                    if(node.has("icon")) require(node.get("icon") is String && node.getString("icon") in icons)
                }
                "input" -> {
                    trace?.at("input_length","$location.maxLength","integer_1_2048",node.opt("maxLength"))
                    if(node.has("maxLength")) require(node.get("maxLength") is Int && node.getInt("maxLength") in 1..2048)
                }
                "text","metric" -> if(node.has("format")) {
                    trace?.at("text_format","$location.format","number_or_clock_format",node.opt("format"))
                    require(node.get("format") is String && node.getString("format") in setOf("number","clock")); if(node.getString("format") == "clock") require(binding == "number" && validTime(value(state,node.getString("bind"))))}
                "select","tabs" -> {
                    trace?.at("selection_options","$location.options","options_1_30",node.opt("options"),node.optJSONArray("options")?.length())
                    val options=options(node); require(options.length() in 1..30)
                    val seen=mutableListOf<Any>()
                    repeat(options.length()) {
                        trace?.at("selection_option","$location.options[$it]","typed_unique_option",options.opt(it))
                        val option=options.getJSONObject(it); require(ToolValueSchema.keys(option) == setOf("text","value"))
                        require(option.get("text") is String && option.getString("text").length in 1..100)
                        val value=option.get("value"); require(scalarValue(value)); ToolValueSchema.validate(checkNotNull(binding),value)
                        require(seen.none {previous -> matches(previous,value)}); seen.add(value)
                    }
                    trace?.at("selection_current_value","$location.options","current_value_in_options",node.opt("options"),options.length())
                    require((0 until options.length()).any {matches(options.getJSONObject(it).get("value"),value(state,node.getString("bind")))})
                }
                "list","table" -> {
                    trace?.at("record_collection","$location.bind","stable_record_collection",node.opt("bind"))
                    val item=collectionItem(checkNotNull(binding)); val rows=records(node,state)
                    if(type == "list" || node.has("action")) {
                        val itemFields=item.getJSONObject("fields")
                        require(itemFields.optString("id") == "string" || (itemFields.optString("key") == "string" && itemFields.optString("token") == "string" && itemFields.optJSONObject("value")?.optString("type") == "object"))
                        stableRecords(rows)
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
                            require(id.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,47}")) && actions.add(id)) {"Action IDs must be unique."}
                            require(rowAction.get("text") is String && rowAction.getString("text").length in 1..100)
                            if(rowAction.has("emphasis")) require(rowAction.get("emphasis") is String && rowAction.getString("emphasis") in setOf("primary","secondary"))
                            if(rowAction.has("enabledField")) {
                                require(rowAction.get("enabledField") is String && schema(item.getJSONObject("fields"),rowAction.getString("enabledField")) == "boolean")
                                repeat(rows.length()) {require(value(rows.getJSONObject(it),rowAction.getString("enabledField")) is Boolean)}
                            }
                        }
                    }
                    trace?.at("table_rows","$location.bind","rows_at_most_50",rows,rows.length())
                    if(type == "table") require(rows.length() <= 50)
                    if(type == "table" || node.has("columns")) {
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
                "chart" -> validateChart(node,fields,state,trace,location)
                "image" -> {
                    trace?.at("image_handle",location,"opaque_resource_handle",node)
                    require(node.has("bind") != node.has("resourceId"))
                    val handle=if(node.has("bind")) value(state,node.getString("bind")) else node.get("resourceId")
                    require(handle is String && ((node.has("bind") && handle.isEmpty()) || handle.matches(Regex("[0-9a-f]{64}")))) {"Images need an opaque resource handle."}
                    if(node.has("fit")) require(node.get("fit") is String && node.getString("fit") in setOf("fit","crop"))
                }
            }
            if(type in setOf("numberInput","slider","progress","progressRing")) {
                trace?.at("numeric_bounds",location,"finite_ordered_bounds_and_value",node)
                val (low,high)=bounds(node); require(low < high)
                val v=value(state,node.getString("bind")); require(v is Number && v.toDouble() in low..high)
                if(node.has("step")) require(numeric(node,"step",1.0) > 0 && numeric(node,"step",1.0) <= high-low)
            }
            if(type == "timeInput") {
                trace?.at("time_value","$location.bind","integer_minutes_0_1439",node.opt("bind"))
                require(validTime(value(state,node.getString("bind"))))
            }
            if(type == "dateInput") {
                val date=value(state,node.getString("bind"))
                trace?.at("date_value","$location.bind","iso_date",date)
                require(validDate(date))
            }
            if(definition.container) {
                trace?.at("container_children","$location.children","children_1_50",node.opt("children"),node.optJSONArray("children")?.length())
                val children=node.getJSONArray("children"); require(children.length() in 1..50)
                repeat(children.length()) {
                    trace?.at("child_object","$location.children[$it]","object",children.opt(it))
                    visit(children.getJSONObject(it),depth+1,"$location.children[$it]")
                }
            }
        }
        visit(root,0,"$.ui"); validateRenderedWork(root,state,trace); return root
    }
    /** Bound expanded native work as well as JSON nodes. Repeated bindings consume the budget again. */
    fun validateRenderedWork(root: JSONObject, state: JSONObject) = validateRenderedWork(root,state,null)
    private fun validateRenderedWork(root: JSONObject,state: JSONObject,trace: ToolUiValidationTrace?) {
        var nodes=0; var work=0; var images=0
        fun visit(node: JSONObject, depth: Int, location: String) {
            trace?.at("render_node_depth_limit",location,"nodes_256_depth_8",node,nodes+1)
            require(++nodes <= 256 && depth <= 8) {"Native UI exceeds its node or depth limit."}
            work++
            when(node.getString("type")) {
                "list","table" -> {
                    val rows=records(node,state).length(); val columns=node.optJSONArray("columns")?.length() ?: 1
                    work+=rows*(1+columns+(node.optJSONArray("rowActions")?.length() ?: 0))+columns
                }
                "select","tabs" -> work+=node.getJSONArray("options").length()
                "chart" -> work+=(value(state,node.getString("categories")) as JSONArray).length()*node.getJSONArray("series").length()
                "image" -> {
                    trace?.at("render_image_limit",location,"images_at_most_4",node,images+1)
                    require(++images <= MAX_IMAGES) {"Native UI exceeds its image limit ($MAX_IMAGES)."}
                }
            }
            trace?.at("render_work_limit",location,"render_work_at_most_2048",node,work)
            require(work <= MAX_RENDER_WORK) {"Native UI exceeds its rendered-work limit ($MAX_RENDER_WORK)."}
            node.optJSONArray("children")?.let { children ->
                trace?.at("render_children","$location.children","children_1_50",children,children.length())
                require(children.length() in 1..50)
                repeat(children.length()) {visit(children.getJSONObject(it),depth+1,"$location.children[$it]")}
            }
        }
        visit(root,0,"$.ui")
    }
    private fun validTime(value: Any?) = value is Number && value.toDouble().isFinite() && value.toDouble() in 0.0..1439.0 && value.toDouble() % 1.0 == 0.0
    private fun validateChart(node: JSONObject, fields: JSONObject, state: JSONObject,trace: ToolUiValidationTrace?,location: String) {
        trace?.at("chart_kind","$location.kind","line_or_bar",node.opt("kind"))
        require(node.get("kind") is String && node.getString("kind") in setOf("line","bar"))
        trace?.at("chart_categories","$location.categories","scalar_array_binding",node.opt("categories"))
        val categorySchema=schema(fields,node.getString("categories"))
        require(categorySchema is JSONObject && categorySchema.getString("type") == "array" && categorySchema.get("items") in setOf("string","number"))
        val categories=value(state,node.getString("categories")) as JSONArray
        trace?.at("chart_series","$location.series","series_1_2_points_256",node.opt("series"),node.optJSONArray("series")?.length())
        val series=node.getJSONArray("series"); require(series.length() in 1..2 && categories.length()*series.length() <= 256)
        repeat(series.length()) {
            trace?.at("chart_series_entry","$location.series[$it]","aligned_numeric_array",series.opt(it))
            val entry=series.getJSONObject(it); require(ToolValueSchema.keys(entry) == setOf("text","bind"))
            require(entry.get("text") is String && entry.getString("text").length in 1..100)
            val contract=schema(fields,entry.getString("bind")); require(contract is JSONObject && contract.getString("type") == "array" && contract.get("items") == "number")
            val values=value(state,entry.getString("bind")) as JSONArray
            require(values.length() == categories.length()); ToolValueSchema.validate(contract,values)
        }
        trace?.at("chart_category_values","$location.categories","declared_category_values",categories,categories.length())
        ToolValueSchema.validate(categorySchema,categories)
    }
    fun chartRange(node: JSONObject, state: JSONObject): Pair<Double,Double> {
        var low=Double.POSITIVE_INFINITY; var high=Double.NEGATIVE_INFINITY
        val series=node.getJSONArray("series")
        repeat(series.length()) { index -> val values=value(state,series.getJSONObject(index).getString("bind")) as JSONArray
            repeat(values.length()) {val v=values.getDouble(it); low=min(low,v); high=max(high,v)} }
        if(!low.isFinite()) return 0.0 to 1.0
        if(node.getString("kind") == "bar") {low=min(0.0,low); high=max(0.0,high)}
        if(low == high) {val margin=max(1.0,kotlin.math.abs(low)*0.05); return low-margin to high+margin}
        return low to high
    }
    fun action(root: JSONObject, id: String): JSONObject? {
        if(!visible(root) || !enabled(root)) return null
        if(root.optString("action") == id) return root
        root.optJSONArray("rowActions")?.let {actions ->
            if((0 until actions.length()).any {actions.getJSONObject(it).getString("action") == id}) return root
        }
        root.optJSONArray("children")?.let {children -> repeat(children.length()) {action(children.getJSONObject(it),id)?.let {found -> return found}}}
        return null
    }
    fun validateAction(root: JSONObject, id: String, payload: Any?, state: JSONObject): JSONObject {
        val node=checkNotNull(action(root,id)) {"Action is unavailable."}
        val type=node.getString("type"); component(type)
        require(when(type) {
            "button","icon","listItem","snackbar","backHandler","navigation","calendar","medicine" -> payload == null
            "input" -> payload is String && payload.length <= node.optInt("maxLength",2048)
            "toggle","checkbox" -> payload is Boolean
            "timeInput" -> validTime(payload)
            "dateInput" -> validDate(payload)
            "numberInput","slider" -> payload is Number && payload.toDouble().isFinite() && payload.toDouble() in bounds(node).first..bounds(node).second
            "select","tabs" -> scalarValue(payload ?: JSONObject.NULL) && (0 until options(node).length()).any {matches(options(node).getJSONObject(it).get("value"),payload)}
            "list","table" -> {
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
    fun display(node: JSONObject, state: JSONObject): String {
        val label=node.optString("text")
        if(!node.has("bind")) return label
        val v=value(state,node.getString("bind"))
        val text=if(node.optString("format") == "clock" && v is Number) "%02d:%02d".format(java.util.Locale.ROOT,v.toInt()/60,v.toInt()%60) else v.toString()
        return "$label: $text"
    }
    fun widgetNodes(root: JSONObject): List<JSONObject> = buildList {
        fun visit(node: JSONObject, parentEnabled: Boolean) {
            if(!visible(node)) return
            val enabled=parentEnabled && enabled(node)
            when(component(node.getString("type")).projection) {
                Projection.CONTAINER -> {val children=node.getJSONArray("children"); repeat(children.length()) {visit(children.getJSONObject(it),enabled)}}
                Projection.OMIT -> Unit
                else -> add(JSONObject(node.toString()).put("enabled",enabled))
            }
        }
        visit(root,true)
    }
    fun widgetText(text: String): String = resourceHandle.replace(text,"Selected resource")
    fun widgetLabel(node: JSONObject, state: JSONObject): String {
        if(node.getString("type") == "calendar") return "Calendar · Open in Alphy"
        if(node.getString("type") == "medicine") return "Medicine · Open in Alphy"
        if(node.getString("type") == "navigation") return "Navigation · Open in Alphy"
        val label=when(component(node.getString("type")).projection) {
            Projection.SUMMARY -> "${node.optString("text")} · Open in Alphy"
            else -> display(node,state)
        }
        return if(resourceHandle.containsMatchIn(label)) "${widgetText(node.optString("text"))} · Open in Alphy" else label
    }
    fun modelGuidance(): String = """Native UI catalog: ${components.keys.joinToString(", ")}. Properties by type: ${components.values.joinToString("; ") {"${it.type}=${(it.properties-common).sorted().joinToString(",")} (style: ${it.styles.sorted().joinToString(",")})"}}.
Containers require children (1..50). medicine has type, action, capability, args and common style/flags only, without text. Use implemented medicine.* schemas. Native code displays exact selected values. Echo input.event.medicine in one effect. Only its foreground native click and scoped grant authorize direct execution. Never invent medicine schedules, doses, meals, or intake.  calendar has exactly type, action, capability, args plus common flags/style, with no generated text. Its capability is calendar.select, calendar.createOwned, calendar.readOwned, calendar.deleteOwned, calendar.deleteAllOwned or calendar.open. Args follow the closed capability schema. Native code displays the operation and exact event details. Echo input.event.calendar={capability,args} in exactly one effect. deleteAllOwned takes only version:1. Its native control displays and freezes every owned-event detail. Scripts cannot supply its deletion targets. Only a native foreground click with its scoped grant authorizes direct calendar execution. Every displayed leaf requires text, except spacer/divider/skeleton/spinner/navigation/calendar/medicine. navigation has exactly type, action, capability, args plus common flags/style. Its capability is maps.markReached or maps.route; args exactly follow that capability schema. It has no generated text property. Native code displays the operation, selected stop/coordinates/system and route mode. It sends null and input.event.navigation={capability,args}. Echo that exact selection in one navigation effect. Only this dedicated foreground control can use its scoped grant without another dialog. Generic buttons, inputs and row actions do not authorize automatic navigation. Optional visible/enabled are booleans. Hidden nodes still validate. UI maximum 256 structural nodes, depth 8, 32 KiB. Aggregate rendered work is at most $MAX_RENDER_WORK units (each structural node, expanded record row/cell, option, and chart point consumes work). Repeated bindings consume work again. At most $MAX_IMAGES image nodes are allowed, each decoded within ${MAX_IMAGE_EDGE}x$MAX_IMAGE_EDGE pixels. Hidden nodes also consume these limits. Exceeding a limit rejects the UI without truncation. Every actionable control has a unique ASCII action ID. Controls never change state directly. button/icon/listItem/snackbar/backHandler send null. input sends a string on Apply, maxLength <=2048. toggle/checkbox send boolean. numberInput/slider send a finite number within min/max on Apply. slider defaults 0..100. Optional positive step is a slider increment. timeInput binds minutes since local midnight (integer 0..1439); dateInput binds ISO YYYY-MM-DD without a timezone. select/tabs require options:[{text:'Label',value:'scalar'}], same type as bind, 1..30 unique values, and send the selected current value.
Labeled native leaves include {"type":"text","text":"Score","bind":"score"} and {"type":"icon","name":"Plus","text":"Add","action":"add"}. An icon name does not replace its accessible text label.
A dateInput validates the bound state value as a real ISO calendar date. Illustrative example only: stateSchema={"visitDate":"string"}, initialState={"visitDate":"2028-02-29"}, ui={"type":"dateInput","text":"Visit date","bind":"visitDate","action":"visitDate"}. Keep existing saved dates unchanged. Do not invent dates or copy the illustrative date as a default. When the date is unknown, keep an ordinary string field and use a labeled input or text control. Empty strings, impossible dates, and timestamps cannot bind dateInput.
Bindings use declared dotted object paths, never indices, expressions, missing fields, prototype or constructor. text/metric/badge bind scalars. metric/text support format number or clock (minute number). list requires stable id records or array page records {key,token,value}. table binds an object array or list and requires columns:[{text:'Label',field:'relative.path'}], at most 8 columns, 50 rows, 400 cells. Optional list columns use the same relative paths. Optional list/table action receives only the currently present stable id/key. Lists also support rowActions:[{action:'searchStop',text:'Search maps',emphasis:'primary',enabledField:'available'},{action:'calendarStop',text:'Open calendar form',emphasis:'secondary'}], at most 3. Every row action ID is unique across the UI, including the optional list selection action. emphasis defaults to secondary. Optional enabledField is a declared relative boolean field in that row. Each row button sends only its current stable id/key in event.value. Resolve the key against current state. Expanded row buttons consume the shared rendered-work budget. Row actions project to a widget summary that opens this mini-app, without record contents. Page values use fields such as value.name. Table arrays without selection do not require keys.
chart requires kind line or bar, categories:'arrayPath' (strings or numbers), series:[{text:'Label',bind:'numericArrayPath'}], 1..2 series with equal category lengths and <=256 total points. text describes the chart. progress/progressRing bind number within min/max (defaults 0..100). image requires text and either resourceId or string bind containing only an owner-scoped 64-character lowercase hex resource handle (empty bind value shows placeholder), optional fit fit/crop. Images never accept URLs, paths, or URIs. Missing/revoked resources show a placeholder. grid columns 1..4 adapt to width. row wraps horizontally. scroll provides a bounded viewport. theme preset: ${presets.joinToString(", ")}. icon name/button icon: ${icons.joinToString(", ")}. Use only the style keys listed for that type. Style values: tone default/muted/accent/success/warning/error, padding none/small/medium/large, align start/center/end; gap none/small/medium/large, emphasis body/title/headline/label/number. No arbitrary style properties.
The host supplies the mini-app title and description. Do not repeat them in the UI. Prefer neutral rounded sections, short section labels, two-column metric grids, and readable list columns. Use primary row buttons sparingly. maps.open searches for a place. Label it Search maps, not Navigate. calendar.create opens a form. Label it Open calendar form and never claim the event was saved. Show actual operation results and errors. Do not invent routes, images, records, or completed actions.
Compose reference designs with native controls: hero/header/section-header = section + text; adaptive columns/tile grids/metric groups = grid; tiles/cells/list groups/timeline items/key-value groups = card/listItem/column + text + icon/divider; action bars = row + button/icon; inset banners/empty states = card + icon/text; stats/number text/number stats/clock text = metric/text with format; number list item/key-value item = listItem + metric/text; TableRow comes from table columns and bound records; Sparkline = chart kind line. All content remains native and accessible. Advanced controls project to compact widget summaries that open the exact mini-app. Path, Canvas, Motion, Transform, Stage, Surface, and Ticker are not UI node types. An optional top-level surface has a separate closed drawing/event contract. When the user requests a playable real-time game, implement the simulation and drawing in that surface. Handle frame, pointer, control, and restart events for actual play, with accessible score and game controls. No HTML, audio, 3D, network access, or render side effects."""
}
