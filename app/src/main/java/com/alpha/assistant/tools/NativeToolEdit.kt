package com.alpha.assistant.tools

import com.alpha.assistant.automations.AutomationRuntime
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.tools.generation.NativeEditBase
import com.alpha.assistant.tools.generation.NativeEditResult
import com.alpha.assistant.tools.generation.StandaloneNativeEdit
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicReference

internal data class NativeToolEditView(val title: String,val description: String,val uiJson: String,val stateJson: String,val appLanguage: String?)
internal data class NativeToolEditPreview(val requestId: String,val before: NativeToolEditView,val after: NativeToolEditView,
    val stoppedAutomationNames: List<String>,val automationReviewNames: List<String>)

internal fun nativeEditStale() = CloudFailure("tool_native_edit_stale",
    "Мини-приложение или дата изменились. Подготовьте изменение заново.")

internal fun nativeEditUnsupported() = CloudFailure("tool_native_edit_unsupported",
    "Не удалось подготовить это изменение. Уточните запрос. Сохранённые данные не изменились.")

internal fun nativeEditImpactChanged() = CloudFailure("tool_native_edit_impact_changed",
    "Связанные сценарии изменились. Подготовьте изменение заново и проверьте список сценариев.")

/** The preview carries no authority. Only this process-local request can confirm the captured plan. */
internal class NativeToolEditRequest(val id: String) {
    internal sealed interface State
    private enum class Phase : State { NEW, PREPARING, CONFIRMING, CLOSED }
    internal class Proof(val base: NativeEditBase,val createdAt: Long,val edit: StandaloneNativeEdit,
        val plan: String,val result: NativeEditResult,val impact: List<AutomationRuntime.ToolRevisionImpact>) : State

    private val state = AtomicReference<State>(Phase.NEW)
    val cloud = CloudSession()
    val isClosed get() = state.get()==Phase.CLOSED

    fun begin() {
        if(!state.compareAndSet(Phase.NEW,Phase.PREPARING)) throw nativeEditStale()
    }

    fun publish(proof: Proof) {
        if(!state.compareAndSet(Phase.PREPARING,proof)) throw CancellationException("The native edit was canceled.")
    }

    suspend fun confirm(commit: suspend (Proof)->ToolRecord): ToolRecord {
        val proof=state.get() as? Proof ?: throw nativeEditStale()
        if(!state.compareAndSet(proof,Phase.CONFIRMING)) throw nativeEditStale()
        try { return commit(proof) } finally { cancel() }
    }

    /** Cancellation can stop a claimed confirmation until its final write is admitted. */
    fun admitCommit() {
        if(!state.compareAndSet(Phase.CONFIRMING,Phase.CLOSED)) throw CancellationException("The native edit was canceled.")
    }

    fun cancel() {
        state.set(Phase.CLOSED)
        cloud.cancel()
    }
}
