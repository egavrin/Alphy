package com.alpha.assistant.capabilities

import android.content.Context
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.CloudTurn
import com.alpha.assistant.cloud.KEY_ERRORS
import com.alpha.assistant.cloud.cloudKey
import com.alpha.assistant.data.SecureKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

internal object MiniAppAi {
    const val ID = "ai.ask"
    const val MAX_PROMPT = 1_500
    const val MAX_ANSWER = 1_500

    fun cancelled() = CapabilityResult("cancelled", "Запрос мини-приложения к DeepSeek остановлен.")

    suspend fun ask(context: Context, provider: ChatProvider, keys: SecureKeyStore,
        prompt: String, active: () -> Boolean): CapabilityResult = coroutineScope {
        val session = CloudSession()
        try {
            suspendCancellableCoroutine { result ->
                val worker = launch(Dispatchers.IO) {
                    result.resumeWith(runCatching {
                        val key = keys.cloudKey(context)
                        ensureActive()
                        if (!active()) return@runCatching cancelled()
                        var answer: String? = null
                        provider.respond(key, CloudRequest(listOf(CloudTurn("user", prompt)), search = false,
                            systemInstructions = "Answer the mini-app request in plain text. Use at most $MAX_ANSWER characters."), session) { event ->
                            if (event is CloudEvent.RoundComplete && !event.hasToolCalls) answer = event.text.trim()
                        }
                        ensureActive()
                        if (!active() || keys.cloudKey(context) != key) return@runCatching cancelled()
                        val text = answer?.takeIf { it.isNotBlank() && it.length <= MAX_ANSWER && '\u0000' !in it }
                        val data = text?.let { JSONObject().put("text", it) }
                        if (data == null || data.toString().toByteArray().size > CapabilityResultEvent.MAX_DATA_BYTES) {
                            CapabilityResult("failed", "DeepSeek не вернул ответ допустимого размера.")
                        } else CapabilityResult("succeeded", "Ответ DeepSeek получен.", data)
                    })
                }
                result.invokeOnCancellation { session.cancel(); worker.cancel() }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: CloudFailure) {
            if (failure.code in KEY_ERRORS) CapabilityResult("denied", failure.message)
            else CapabilityResult("failed", "Запрос мини-приложения к DeepSeek не выполнен.")
        } catch (_: Exception) {
            CapabilityResult("failed", "Запрос мини-приложения к DeepSeek не выполнен.")
        } finally {
            session.cancel()
        }
    }
}
