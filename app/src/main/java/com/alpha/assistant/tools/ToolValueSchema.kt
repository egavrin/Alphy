package com.alpha.assistant.tools

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Shared bounded values for UI state and native-owned collection records. */
internal object ToolValueSchema {
    const val LIST_MAX_ITEMS=100
    const val ARRAY_MAX_ITEMS=512
    private val scalars=setOf("string","number","boolean")
    private val forbidden=setOf("__proto__","prototype","constructor")
    fun name(value: String) = value.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,47}")) && value !in forbidden
    fun keys(value: JSONObject) = value.keys().asSequence().toSet()
    fun validateSchemas(state: JSONObject, collections: JSONObject) {
        var nodes=0
        var structuredNodes=0
        fun check(valid: Boolean, rule: String, path: String, expected: String, observed: String) =
            validateTool(ToolValidationCategory.STATE_SCHEMA,ToolValidationDetails(rule,path,expected,observed)) { require(valid) }
        // Only maxItems diagnostics retain numeric payloads. Other schema values report their type.
        fun kind(value: Any?, bound: Boolean = false): String = when(value) {
            null -> "missing"
            JSONObject.NULL -> "null"
            is Int, is Long -> if(bound) "integer $value" else "integer"
            is Number -> "non-integer number"
            is String -> "string"
            is Boolean -> "boolean"
            is JSONObject -> "object"
            is JSONArray -> "array"
            else -> "unsupported value"
        }
        fun path(parent: String, field: String, index: Int) = if(name(field)) "$parent.$field" else "$parent[field#$index]"
        fun checkName(field: String, at: String) = check(name(field),"schema.name",at,
            "ASCII letter followed by up to 47 letters, digits, or underscores; no prototype names","invalid field name")
        fun visit(schema: Any, depth: Int, structured: Boolean, at: String) {
            check(++nodes <= 1024,"schema.nodes",at,"at most 1024 nodes","$nodes nodes")
            if(structured) check(++structuredNodes <= 256,"schema.structuredNodes",at,"at most 256 structured nodes","$structuredNodes structured nodes")
            check(depth <= 6,"schema.depth",at,"depth at most 6","depth $depth")
            if(schema is String) { check(schema in scalars,"schema.scalar",at,"number, string, or boolean","string"); return }
            check(schema is JSONObject,"schema.constructor",at,"scalar name or schema object",kind(schema))
            val definition=schema as JSONObject
            val type=definition.opt("type")
            check(type is String && type in setOf("object","list","array"),"schema.type","$at.type","object, list, or array",kind(type))
            when(type) {
                "object", "list" -> {
                    val list=type == "list"
                    check(keys(definition) == if(list) setOf("type","maxItems","fields") else setOf("type","fields"),
                        "$type.keys",at,if(list) "exactly type, maxItems, fields" else "exactly type, fields","missing or extra constructor keys")
                    val rawFields=definition.opt("fields")
                    check(rawFields is JSONObject,"$type.fields","$at.fields","object of named fields",kind(rawFields))
                    val fields=rawFields as JSONObject
                    check(fields.length() in 1..if(list) 12 else 32,"$type.fields","$at.fields",if(list) "1..12 fields" else "1..32 fields","${fields.length()} fields")
                    if(list) {
                        val maxItems=definition.opt("maxItems")
                        check(maxItems is Int && maxItems in 1..LIST_MAX_ITEMS,"list.maxItems","$at.maxItems","integer 1..$LIST_MAX_ITEMS",kind(maxItems,bound=true))
                        check(fields.optString("id") == "string","list.id","$at.fields.id","scalar string",kind(fields.opt("id")))
                    }
                    fields.keys().asSequence().forEachIndexed { index, field ->
                        val child=path("$at.fields",field,index)
                        checkName(field,child)
                        if(list) check(fields.get(field) is String,"list.field",child,"scalar number, string, or boolean",kind(fields.get(field)))
                        visit(fields.get(field),depth+1,structured,child)
                    }
                }
                "array" -> {
                    check(keys(definition) == setOf("type","maxItems","items"),"array.keys",at,"exactly type, maxItems, items","missing or extra constructor keys")
                    val maxItems=definition.opt("maxItems")
                    check(maxItems is Int && maxItems in 1..ARRAY_MAX_ITEMS,"array.maxItems","$at.maxItems","integer 1..$ARRAY_MAX_ITEMS",kind(maxItems,bound=true))
                    visit(definition.get("items"),depth+1,structured,"$at.items")
                }
            }
        }
        check(state.length() in 1..40,"state.fields","stateSchema","1..40 fields","${state.length()} fields")
        check(collections.length() <= 8,"collections.count","collections","at most 8 collections","${collections.length()} collections")
        state.keys().asSequence().forEachIndexed { index, field ->
            val at=path("stateSchema",field,index)
            checkName(field,at)
            visit(state.get(field),1,state.get(field) !is String && state.optJSONObject(field)?.optString("type") != "list",at)
        }
        collections.keys().asSequence().forEachIndexed { index, field ->
            val at=path("collections",field,index)
            checkName(field,at)
            visit(collections.get(field),1,true,at)
        }
    }
    fun validate(schema: Any, value: Any, rootState: Boolean = false) {
        var values=0
        var structuredValues=0
        fun visit(contract: Any, item: Any, structured: Boolean, root: Boolean = false) {
            require(++values <= 60000 && (!structured || ++structuredValues <= 4096)) { "Too many state values." }
            if(contract is String) {
                require(when(contract) {
                    "string" -> item is String && item.length <= 2048
                    "boolean" -> item is Boolean
                    "number" -> item is Number && item.toDouble().isFinite() && abs(item.toDouble()) <= 9007199254740991.0
                    else -> false
                }) { "Invalid scalar value." }
                return
            }
            val definition=contract as JSONObject
            when(definition.getString("type")) {
                "object" -> {
                    require(item is JSONObject)
                    val fields=definition.getJSONObject("fields")
                    require(keys(item) == keys(fields)) { "Object fields differ from the schema." }
                    fields.keys().forEach {
                        // Top-level flat lists keep their original bounded traversal and stable-ID contract.
                        val flatList=root && fields.optJSONObject(it)?.optString("type") == "list"
                        visit(fields.get(it),item.get(it),structured && !flatList)
                    }
                }
                "array", "list" -> {
                    require(item is JSONArray && item.length() <= definition.getInt("maxItems"))
                    val list=definition.getString("type") == "list"
                    val child=if(list) JSONObject().put("type","object").put("fields",definition.getJSONObject("fields")) else definition.get("items")
                    val ids=mutableSetOf<String>()
                    repeat(item.length()) {
                        visit(child,item.get(it),structured)
                        if(list) {
                            val id=item.getJSONObject(it).getString("id")
                            require(id.isNotBlank() && id.length <= 128 && ids.add(id)) { "Invalid or duplicate stable record ID." }
                        }
                    }
                }
            }
        }
        visit(schema,value,true,rootState)
    }
    fun state(schema: JSONObject, value: JSONObject) = validate(JSONObject().put("type","object").put("fields",schema),value,true)
    fun same(a: Any, b: Any): Boolean {
        if(a !is JSONObject || b !is JSONObject) return a == b
        return keys(a) == keys(b) && a.keys().asSequence().all { same(a.get(it),b.get(it)) }
    }
}
