package com.alpha.assistant.network.livesource

import com.alpha.assistant.network.NetworkOwner
import com.alpha.assistant.network.NetworkResponse
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal object LiveSourceEffect {
    const val MAX_STEPS = 12
    private val limits = LiveSourceLimits(1_024, MAX_STEPS, 256, 2_048)

    fun declaration(args: JSONObject, owner: NetworkOwner, id: String): JsonLiveSource? {
        if (!args.has("extract")) return null
        require(args.getString("method") == "GET" && !args.has("headers") && !args.has("bodyText"))
        val extract = args.getJSONObject("extract")
        require(extract.keys().asSequence().toSet() == setOf("kind", "path"))
        val kind = when (extract.get("kind")) {
            "text" -> LiveValueKind.TEXT
            "number" -> LiveValueKind.NUMBER
            "boolean" -> LiveValueKind.BOOLEAN
            else -> throw IllegalArgumentException("Invalid live value kind.")
        }
        val path = extract.getJSONArray("path")
        require(path.length() <= MAX_STEPS)
        val steps = List(path.length()) { index ->
            when (val step = path.get(index)) {
                is String -> JsonPathStep.Key(step)
                is Int -> JsonPathStep.Index(step)
                else -> throw IllegalArgumentException("Invalid live source path.")
            }
        }
        return requireNotNull(JsonLiveSource.validate(LiveSourceKey(owner, id), args.getString("url"), steps, kind, limits))
    }

    fun sample(source: JsonLiveSource, response: NetworkResponse): JSONObject? {
        val sample = (source.refresh(response, Instant.now(), previous = null) as? LiveSourceState.Fresh)?.lastValid ?: return null
        val primitive = sample.value
        val value: Any = if (primitive.isString) primitive.content else primitive.booleanOrNull
            ?: primitive.doubleOrNull?.takeIf { it.isFinite() } ?: return null
        return JSONObject().put("source", sample.configuredEndpoint).put("dataTimeMs", sample.dataTime.toEpochMilli()).put("value", value)
    }

    fun schema(): JSONObject = JSONObject().put("type", "object").put("additionalProperties", false)
        .put("required", JSONArray(listOf("kind", "path")))
        .put("properties", JSONObject().put("kind", JSONObject().put("enum", JSONArray(listOf("text", "number", "boolean"))))
            .put("path", JSONObject().put("type", "array").put("maxItems", MAX_STEPS).put("items", JSONObject()
                .put("type", JSONArray(listOf("string", "integer"))))))
}
