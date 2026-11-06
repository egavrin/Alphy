package com.alpha.assistant.surfaces.widgetauthoring

import android.content.Context
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.CloudTurn
import com.alpha.assistant.cloud.cloudKey
import com.alpha.assistant.data.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject

internal suspend fun generateWidgetLayout(context: Context, provider: ChatProvider, keys: SecureKeyStore,
    request: WidgetDraftRequest, references: WidgetReferences): WidgetPresentation = coroutineScope {
    val session = CloudSession()
    try {
        suspendCancellableCoroutine { result ->
            val worker = launch(Dispatchers.IO) {
                result.resumeWith(runCatching {
                    val key = keys.cloudKey(context)
                    val allowed = JSONObject().put("bindings", JSONArray(references.bindings.sorted()))
                        .put("actions", JSONObject().apply {
                            references.actions.forEach { (id, action) ->
                                put(id, when (action) {
                                    WidgetAction.Button -> JSONObject().put("type", "button")
                                    is WidgetAction.Toggle -> JSONObject().put("type", "toggle").put("binding", action.binding)
                                })
                            }
                        })
                    val input = JSONObject().put("instruction", request.instruction).put("allowed", allowed)
                    val instructions = """
                        Return a JSON object with one rows array of 1 to 6 ordered widget rows.
                        A row is {type:"text",label,binding?}, {type:"button",label,action}, or {type:"toggle",label,binding,action}.
                        Use only supplied binding and action IDs. Keep the supplied binding for each toggle.
                        Labels must be concise. Keep each action at most once. Supply no other keys, state, or code.
                    """.trimIndent()
                    var answer: String? = null
                    provider.respond(key, CloudRequest(listOf(CloudTurn("user", input.toString())), search = false,
                        systemInstructions = instructions, jsonObject = true), session) { event ->
                        if (event is CloudEvent.RoundComplete && !event.hasToolCalls) answer = event.text
                    }
                    ensureActive()
                    check(keys.cloudKey(context) == key)
                    parseWidgetPresentation(checkNotNull(answer))
                })
            }
            result.invokeOnCancellation { session.cancel(); worker.cancel() }
        }
    } finally {
        session.cancel()
    }
}
