package com.alpha.assistant.tools.generation

import com.alpha.assistant.tools.ToolValueSchema
import org.json.JSONArray
import org.json.JSONObject

/** Typed local rows expose only numeric aggregates to other parts. */
internal object RecordParts {
    fun build(id: String, label: String, fields: List<PlanRecordField>, russian: Boolean): PartCandidate {
        val builder = FragmentBuilder(id)
        val rowFields = JSONObject().put("id", "string")
        fields.forEach { rowFields.put(it.id, if (it.numeric) "number" else "string") }
        val items = builder.state("items", JSONObject().put("type", "list").put("maxItems", ToolValueSchema.LIST_MAX_ITEMS)
            .put("fields", rowFields), JSONArray())
        val drafts = fields.mapIndexed { index, field ->
            field.id to builder.state("draft_${index + 1}", if (field.numeric) "number" else "string", if (field.numeric) 0 else "")
        }.toMap()
        val controls = fields.mapIndexed { index, field ->
            val draft = drafts.getValue(field.id)
            val action = builder.clockedAction("edit_${index + 1}") { state, value, _ -> state.put(draft, checkNotNull(value)) }
            partNode(if (field.numeric) "numberInput" else "input", field.label).put("bind", draft).put("action", action)
                .apply { if (!field.numeric) put("maxLength", 100) }
        }
        val add = builder.clockedAction("add") { state, _, _ ->
            val rows = state.getJSONArray(items)
            if (rows.length() < ToolValueSchema.LIST_MAX_ITEMS && fields.filterNot { it.numeric }
                    .all { state.getString(drafts.getValue(it.id)).isNotBlank() }) {
                val ids = (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }.toSet()
                val next = (0..rows.length()).first { it.toString() !in ids }.toString()
                val row = JSONObject().put("id", next)
                fields.forEach { field ->
                    val key = drafts.getValue(field.id)
                    row.put(field.id, state.get(key))
                    state.put(key, if (field.numeric) 0 else "")
                }
                rows.put(row)
            }
        }
        val remove = builder.clockedAction("remove") { state, value, _ ->
            val rows = state.getJSONArray(items)
            state.put(items, jsonArray((0 until rows.length()).map(rows::getJSONObject).filter { it.getString("id") != value }))
        }
        val list = partNode("list", label).put("bind", items)
            .put("columns", jsonArray(fields.map { JSONObject().put("field", it.id).put("text", it.label) }))
            .put("rowActions", JSONArray().put(JSONObject().put("text", if (russian) "Удалить" else "Remove").put("action", remove)))
            .put("emptyText", if (russian) "Записей пока нет" else "No records yet")
        val ui = partContainer("column", controls + partNode("button", if (russian) "Добавить" else "Add").put("action", add) + list)
        val config = JSONObject().put("label", label).put("fields", jsonArray(fields.map(PlanRecordField::json))).put("russian", russian)
        val aggregates = mapOf("count" to PartNumberOutput(items, records = true)) + fields.filter { it.numeric }.associate {
            "sum:${it.id}" to PartNumberOutput(items, records = true, member = it.id)
        }
        return PartCandidate("record_log", "records", "Add and remove typed local records with count and numeric totals.", config,
            builder.finish(ui), primaryActionId = add, secondaryActionIds = setOf(remove), aggregates = aggregates,
            observationPaths = nativeObservationPaths(ui, NativeObservation.RECORDS to list))
    }
}
