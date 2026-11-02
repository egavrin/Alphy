package com.alpha.assistant.sandbox

import org.json.JSONObject

internal object SandboxContract {
    const val MAX_BYTES = 65536
    const val HEAP_BYTES = 16L * 1024 * 1024
    const val EVALUATION_MS = 500L

    fun objectFrom(json: String): JSONObject {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        StrictJson(json).validate()
        return JSONObject(json)
    }

    /** Validate the strict grammar before Android's permissive parser can discard duplicate keys or comments. */
    private class StrictJson(private val input: String) {
        private var position=0
        private val number=Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
        private fun space() { while(position < input.length && input[position] in " \t\r\n") position++ }
        private fun take(char: Char): Boolean {
            space()
            if(position == input.length || input[position] != char) return false
            position++; return true
        }
        private fun string(): String {
            space(); val start=position
            require(position < input.length && input[position++] == '"') { "JSON strings need double quotes." }
            while(position < input.length) {
                val char=input[position++]
                if(char == '"') return org.json.JSONTokener(input.substring(start,position)).nextValue() as String
                require(char.code >= 32) { "Unescaped JSON control character." }
                if(char == '\\') {
                    require(position < input.length)
                    when(input[position++]) {
                        '"','\\','/','b','f','n','r','t' -> Unit
                        'u' -> { repeat(4) {require(position < input.length && input[position++].digitToIntOrNull(16) != null)} }
                        else -> error("Invalid JSON escape.")
                    }
                }
            }
            error("Unterminated JSON string.")
        }
        private fun value(depth: Int) {
            space(); require(position < input.length)
            when(input[position]) {
                '{' -> {
                    require(depth < 24) { "JSON exceeds nesting depth 24." }; position++
                    val keys=mutableSetOf<String>()
                    if(!take('}')) {
                        do {
                            require(keys.add(string())) { "Duplicate JSON key." }; require(take(':'))
                            value(depth+1)
                        } while(take(','))
                        require(take('}'))
                    }
                }
                '[' -> {
                    require(depth < 24) { "JSON exceeds nesting depth 24." }; position++
                    if(!take(']')) {
                        do {value(depth+1)} while(take(','))
                        require(take(']'))
                    }
                }
                '"' -> string()
                't','f','n' -> {
                    val literal=when(input[position]) { 't' -> "true"; 'f' -> "false"; else -> "null" }
                    require(input.startsWith(literal,position)); position+=literal.length
                }
                else -> {
                    val match=number.matchAt(input,position)
                    require(match != null) { "Invalid JSON value." }; position=match.range.last+1
                }
            }
        }
        fun validate() { value(0); space(); require(position == input.length) { "Unexpected content after JSON." } }
    }

    fun validateRequest(source: String, input: String) {
        require(source.isNotBlank() && source.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val json = objectFrom(input)
        require(json.getString("artifactId").length in 1..128)
        require(json.get("revision") is Int && json.getInt("revision") > 0)
        val event=json.getJSONObject("event")
        require(event.getString("type") in setOf("action","preview","render","capabilityResults","recordResults"))
        if(event.getString("type") == "capabilityResults") {
            require(event.keys().asSequence().toSet() == setOf("type","deliveryId","entries"))
            require(event.getString("deliveryId").matches(Regex("[0-9a-f-]{36}")))
            require(event.toString().toByteArray().size <= com.alpha.assistant.capabilities.CapabilityResultEvent.MAX_EVENT_BYTES)
            val entries=event.getJSONArray("entries")
            require(entries.length() in 1..3)
            repeat(entries.length()) {
                val entry=entries.getJSONObject(it)
                require(entry.keys().asSequence().toSet() == setOf("requestId","capability","status","data"))
                require(entry.getString("requestId").matches(Regex("[A-Za-z][A-Za-z0-9_]{0,47}")))
                com.alpha.assistant.capabilities.CapabilityResultEvent.data(entry.getString("capability"),
                    com.alpha.assistant.capabilities.CapabilityResult(entry.getString("status"),"",entry.getJSONObject("data")))
            }
        }
        if(event.getString("type") == "recordResults") com.alpha.assistant.tools.ToolRecords.validateEvent(event)
        json.getJSONObject("state")
        require(json.keys().asSequence().toSet() == setOf("artifactId", "revision", "event", "state"))
    }

    fun validateResult(result: String) {
        val json = objectFrom(result)
        require(json.keys().asSequence().toSet().all { it in setOf("state", "ui", "effects", "records") })
        json.getJSONObject("state")
        if (json.has("ui")) json.getJSONObject("ui")
        if(json.has("records")) com.alpha.assistant.tools.ToolRecords.validateOperations(json.getJSONArray("records"))
        if (json.has("effects")) {
            val effects = json.getJSONArray("effects")
            require(effects.length() <= 3)
            repeat(effects.length()) { com.alpha.assistant.capabilities.CapabilityCatalog.request(effects.getJSONObject(it)) }
        }
    }
}

/** Engine identity describes the implementation, not a proof of independent OS-process isolation. */
data class SandboxResult(val status: String,val json: String?=null,val engine: String="androidx.javascriptengine") {
    val message: String get()=when(status) {
        "UNAVAILABLE" -> "JavaScript execution is unavailable. Update Android System WebView and reopen Alphy. Saved data is unchanged."
        "SANDBOX_DIED","MEMORY_LIMIT" -> "The JavaScript sandbox stopped. Other active mini-app sessions also stopped. Reopen the mini-app. Saved data is unchanged."
        "TIMEOUT" -> "The mini-app exceeded its execution deadline. Its session stopped. Saved data is unchanged."
        "BUSY" -> "Another mini-app operation uses this execution slot. Try again."
        else -> "The mini-app session stopped: $status. Saved data is unchanged."
    }
}
