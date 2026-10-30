package com.alpha.assistant.tools

import com.alpha.assistant.sandbox.SandboxContract
import org.json.JSONArray
import org.json.JSONObject

/** Generated data describes native controls. Generated code never runs in the host process. */
data class ToolSpec(val json: String, val title: String, val description: String, val source: String,
    val schema: JSONObject, val initialState: String, val ui: JSONObject, val capabilities: Set<String>) {
    fun validateState(value: String): JSONObject {
        require(value.toByteArray().size <= 49152) { "Данные инструмента превышают 48 КиБ" }
        val state = SandboxContract.objectFrom(value)
        require(state.keys().asSequence().toSet() == schema.keys().asSequence().toSet()) { "Поля данных инструмента изменились" }
        schema.keys().forEach { key ->
            val contract = schema.get(key)
            val value = state.get(key)
            if (contract is String) require(scalar(contract,value)) { "Неверное поле данных: $key" }
            else {
                val list = value as? JSONArray ?: error("Неверный список записей: $key")
                val definition = contract as JSONObject
                require(list.length() <= definition.getInt("maxItems")) { "В списке слишком много записей: $key" }
                val fields = definition.getJSONObject("fields")
                val ids = mutableSetOf<String>()
                repeat(list.length()) { index ->
                    val record = list.getJSONObject(index)
                    require(record.keys().asSequence().toSet() == fields.keys().asSequence().toSet()) { "Поля записи изменились" }
                    fields.keys().forEach { field -> require(scalar(fields.getString(field),record.get(field))) { "Неверное поле записи: $field" } }
                    val id = record.getString("id")
                    require(id.isNotBlank() && id.length <= 128 && ids.add(id)) { "Неверный или повторяющийся идентификатор записи" }
                }
            }
        }
        return state
    }
    fun validateUi(root: JSONObject): JSONObject {
        var count = 0
        val actions = mutableSetOf<String>()
        fun visit(node: JSONObject, depth: Int) {
            require(++count <= 80 && depth <= 8) { "Интерфейс инструмента превышает допустимые размеры" }
            val type = node.getString("type")
            val allowed = when (type) {
                "column", "row" -> setOf("type", "children")
                "text" -> setOf("type", "text", "bind")
                "button" -> setOf("type", "text", "action")
                "list" -> setOf("type", "text", "bind", "action")
                "input", "toggle" -> setOf("type", "text", "bind", "action")
                else -> error("Недоступный элемент интерфейса: $type")
            }
            require(node.keys().asSequence().all { it in allowed }) { "Неизвестное свойство элемента интерфейса" }
            if (type in listOf("column", "row")) {
                val children = node.getJSONArray("children")
                require(children.length() in 1..30)
                repeat(children.length()) { visit(children.getJSONObject(it), depth + 1) }
            } else {
                require(node.get("text") is String && node.getString("text").length in 1..300)
                if (node.has("bind")) require(node.get("bind") is String && schema.has(node.getString("bind")))
                if(type == "text" && node.has("bind")) require(schema.get(node.getString("bind")) is String)
                if (type == "input") require(schema.get(node.getString("bind")) == "string")
                if (type == "toggle") require(schema.get(node.getString("bind")) == "boolean")
                if (type == "list") require(schema.get(node.getString("bind")) is JSONObject)
                if (type in listOf("button", "input", "toggle") || (type == "list" && node.has("action"))) {
                    val action = node.getString("action")
                    require(action.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,47}")) && actions.add(action)) { "Неверное или повторяющееся действие" }
                }
            }
        }
        visit(root, 0)
        return root
    }
    fun action(ui: JSONObject, id: String): JSONObject? {
        if (ui.optString("action") == id) return ui
        val children = ui.optJSONArray("children") ?: return null
        repeat(children.length()) { action(children.getJSONObject(it), id)?.let { found -> return found } }
        return null
    }
    companion object {
        private fun scalar(type: String, value: Any) = when(type) {
            "string" -> value is String && value.length <= 2048
            "boolean" -> value is Boolean
            "number" -> value is Number && value.toDouble().isFinite() && kotlin.math.abs(value.toDouble()) <= 1e12
            else -> false
        }
        private fun sameContract(a: Any, b: Any): Boolean {
            if(a !is JSONObject || b !is JSONObject) return a == b
            return a.keys().asSequence().toSet() == b.keys().asSequence().toSet() && a.keys().asSequence().all { sameContract(a.get(it),b.get(it)) }
        }
        /** Refinements may add fields, but cannot remove, retype, or reset stored values. */
        fun stateForRevision(previous: ToolSpec, next: ToolSpec, raw: String): String {
            val state = previous.validateState(raw)
            previous.schema.keys().forEach { key ->
                require(next.schema.has(key) && sameContract(previous.schema.get(key),next.schema.get(key))) { "Несовместимая схема данных: $key. Сохранённые данные не изменены." }
            }
            val initial = JSONObject(next.initialState)
            next.schema.keys().forEach { key -> if(!state.has(key)) state.put(key,initial.get(key)) }
            return next.validateState(state.toString()).toString()
        }
        fun parse(raw: String): ToolSpec {
            val j = SandboxContract.objectFrom(raw)
            require(j.keys().asSequence().toSet() == setOf("formatVersion", "title", "description", "capabilities", "stateSchema", "initialState", "ui", "source")) { "Неверные поля инструмента" }
            require(j.get("formatVersion") is Int && j.getInt("formatVersion") == 2)
            require(j.get("title") is String && j.getString("title").length in 1..100)
            require(j.get("description") is String && j.getString("description").length <= 500)
            val declared = j.getJSONArray("capabilities")
            require(declared.length() <= com.alpha.assistant.capabilities.CapabilityCatalog.ids.size)
            val capabilities = (0 until declared.length()).map { declared.getString(it) }.toSet()
            require(capabilities.size == declared.length() && capabilities.all { it in com.alpha.assistant.capabilities.CapabilityCatalog.ids }) { "Неизвестная системная функция" }
            val schema = j.getJSONObject("stateSchema")
            require(schema.length() in 1..40)
            schema.keys().forEach { key ->
                require(key.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,47}")))
                val type = schema.get(key)
                if(type is String) require(type in listOf("string","number","boolean"))
                else {
                    require(type is JSONObject)
                    require(type.keys().asSequence().toSet() == setOf("type","maxItems","fields"))
                    require(type.getString("type") == "list" && type.get("maxItems") is Int && type.getInt("maxItems") in 1..100)
                    val fields = type.getJSONObject("fields")
                    require(fields.length() in 1..12 && fields.optString("id") == "string")
                    fields.keys().forEach { field -> require(field.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,47}")) && fields.get(field) in listOf("string","number","boolean")) }
                }
            }
            val source = j.getString("source")
            require(source.isNotBlank() && source.toByteArray().size <= 32768)
            return ToolSpec(j.toString(), j.getString("title"), j.getString("description"), source, schema,
                j.getJSONObject("initialState").toString(), j.getJSONObject("ui"), capabilities).also {
                it.validateState(it.initialState); it.validateUi(it.ui)
            }
        }
    }
}
