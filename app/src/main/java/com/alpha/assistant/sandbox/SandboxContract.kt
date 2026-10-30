package com.alpha.assistant.sandbox

import org.json.JSONObject

internal object SandboxContract {
    const val MAX_BYTES = 65536
    const val EXECUTE = 1
    const val HEAP_BYTES = 16L * 1024 * 1024
    const val STACK_BYTES = 256L * 1024
    const val EVALUATION_MS = 500L
    const val PROCESS_MS = 2000L

    fun objectFrom(json: String): JSONObject {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in json) {
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 24) }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        require(depth == 0 && !quoted)
        return JSONObject(json)
    }

    fun validateRequest(source: String, input: String) {
        require(source.isNotBlank() && source.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val json = objectFrom(input)
        require(json.getString("artifactId").length in 1..128)
        require(json.get("revision") is Int && json.getInt("revision") > 0)
        json.getJSONObject("event")
        json.getJSONObject("state")
        require(json.keys().asSequence().toSet() == setOf("artifactId", "revision", "event", "state"))
    }

    fun validateResult(result: String) {
        val json = objectFrom(result)
        require(json.keys().asSequence().toSet().all { it in setOf("state", "ui", "effects") })
        json.getJSONObject("state")
        if (json.has("ui")) json.getJSONObject("ui")
        if (json.has("effects")) {
            val effects = json.getJSONArray("effects")
            require(effects.length() <= 3)
            repeat(effects.length()) { com.alpha.assistant.capabilities.CapabilityCatalog.request(effects.getJSONObject(it)) }
        }
    }
}

data class SandboxResult(
    val status: String,
    val json: String? = null,
    val uid: Int = -1,
    val pid: Int = -1,
    val networkDenied: Boolean = false,
    val privateDataDenied: Boolean = false,
)
