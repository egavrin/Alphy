package com.alpha.assistant.surfaces.widgetauthoring

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

internal data class WidgetAppId(val libraryGeneration: String, val id: String)

internal data class WidgetDraftBase(
    val app: WidgetAppId,
    val revision: Int,
    val stateVersion: Int,
    val presentationVersion: Long,
)

internal data class WidgetDraftRequest(val instruction: String)

internal data class WidgetReferences(val bindings: Set<String>, val actions: Map<String, WidgetAction>)

internal sealed interface WidgetAction {
    data object Button : WidgetAction
    data class Toggle(val binding: String) : WidgetAction
}

internal sealed interface WidgetPresentationRow {
    val label: String

    data class Text(override val label: String, val binding: String? = null) : WidgetPresentationRow
    data class Button(override val label: String, val action: String) : WidgetPresentationRow
    data class Toggle(override val label: String, val binding: String, val action: String) : WidgetPresentationRow
}

internal class WidgetPresentation(rows: List<WidgetPresentationRow>) {
    val rows: List<WidgetPresentationRow> = Collections.unmodifiableList(rows.toList())

    internal fun usesOnly(references: WidgetReferences): Boolean {
        if (rows.size !in 1..6) return false
        val actions = mutableSetOf<String>()
        return rows.all { row ->
            row.label.isNotBlank() && row.label.length <= 300 && when (row) {
                is WidgetPresentationRow.Text -> row.binding == null || row.binding in references.bindings
                is WidgetPresentationRow.Button -> references.actions[row.action] == WidgetAction.Button && actions.add(row.action)
                is WidgetPresentationRow.Toggle -> row.binding in references.bindings &&
                    references.actions[row.action] == WidgetAction.Toggle(row.binding) && actions.add(row.action)
            }
        }
    }
}

/** The state is a host-owned read view. The producer receives only the permitted references. */
internal data class WidgetAppSnapshot<State>(
    val base: WidgetDraftBase,
    val references: WidgetReferences,
    val state: State,
)

internal fun interface WidgetDraftProducer {
    /** A null result cancels the request. A failure throws an exception. */
    suspend fun generate(request: WidgetDraftRequest, references: WidgetReferences): WidgetPresentation?
}

internal interface WidgetDraftHost<State> {
    /** Return one consistent snapshot of the saved app, or null if the app is unavailable. */
    suspend fun latest(app: WidgetAppId): WidgetAppSnapshot<State>?

    /** Apply current host rules for bindings, actions, and permitted display. */
    fun validate(snapshot: WidgetAppSnapshot<State>, presentation: WidgetPresentation): Boolean

    /** Use the same snapshot as validation. Return null if preview rendering is unavailable. */
    fun render(snapshot: WidgetAppSnapshot<State>, presentation: WidgetPresentation): WidgetPreviewContent?
}

internal data class WidgetPreviewContent(val title: String, val rows: List<WidgetPreviewRow>)

internal sealed interface WidgetPreviewRow {
    val label: String

    data class Text(override val label: String) : WidgetPreviewRow
    data class Button(override val label: String) : WidgetPreviewRow
    data class Toggle(override val label: String, val checked: Boolean) : WidgetPreviewRow
}

/** The host must compare the full base atomically when it saves the presentation. */
internal data class WidgetSaveProposal(val base: WidgetDraftBase, val presentation: WidgetPresentation)

internal sealed interface WidgetAuthoringResult<out T> {
    data class Ready<T>(val value: T) : WidgetAuthoringResult<T>
    enum class Rejected : WidgetAuthoringResult<Nothing> {
        UNAVAILABLE, INVALID_REQUEST, INVALID_PRESENTATION, STALE, CANCELLED, FAILED,
    }
}

internal class WidgetDraftReview internal constructor(
    val content: WidgetPreviewContent,
    private val confirm: suspend () -> WidgetAuthoringResult<WidgetSaveProposal>,
) {
    private val closed = AtomicBoolean(false)

    fun cancel() {
        closed.set(true)
    }

    suspend fun proposeSave(): WidgetAuthoringResult<WidgetSaveProposal> {
        if (closed.get()) return WidgetAuthoringResult.Rejected.CANCELLED
        val result = confirm()
        return if (result is WidgetAuthoringResult.Ready && closed.compareAndSet(false, true)) result
        else if (closed.get()) WidgetAuthoringResult.Rejected.CANCELLED
        else result
    }
}

internal class WidgetAuthoring<State>(
    private val host: WidgetDraftHost<State>?,
    private val producer: WidgetDraftProducer?,
) {
    suspend fun prepare(request: WidgetDraftRequest, snapshot: WidgetAppSnapshot<State>): WidgetAuthoringResult<WidgetDraftReview> = checked {
        val host = host ?: return@checked WidgetAuthoringResult.Rejected.UNAVAILABLE
        val producer = producer ?: return@checked WidgetAuthoringResult.Rejected.UNAVAILABLE
        if (request.instruction.isBlank()) return@checked WidgetAuthoringResult.Rejected.INVALID_REQUEST
        val base = snapshot.base
        val start = host.latest(base.app) ?: return@checked WidgetAuthoringResult.Rejected.UNAVAILABLE
        if (start.base != base) return@checked WidgetAuthoringResult.Rejected.STALE
        currentCoroutineContext().ensureActive()
        val allowed = WidgetReferences(start.references.bindings.toSet(), start.references.actions.toMap())
        val presentation = producer.generate(request, allowed) ?: return@checked WidgetAuthoringResult.Rejected.CANCELLED
        currentCoroutineContext().ensureActive()
        withCurrent(host, base, presentation) { current ->
            val content = host.render(current, presentation) ?: return@withCurrent WidgetAuthoringResult.Rejected.UNAVAILABLE
            WidgetAuthoringResult.Ready(WidgetDraftReview(content.copy(rows = Collections.unmodifiableList(content.rows.toList()))) {
                checked {
                    withCurrent(host, base, presentation) {
                        WidgetAuthoringResult.Ready(WidgetSaveProposal(base, presentation))
                    }
                }
            })
        }
    }

    private suspend fun <T> withCurrent(
        host: WidgetDraftHost<State>,
        base: WidgetDraftBase,
        presentation: WidgetPresentation,
        accept: (WidgetAppSnapshot<State>) -> WidgetAuthoringResult<T>,
    ): WidgetAuthoringResult<T> {
        val current = host.latest(base.app) ?: return WidgetAuthoringResult.Rejected.UNAVAILABLE
        if (current.base != base) return WidgetAuthoringResult.Rejected.STALE
        currentCoroutineContext().ensureActive()
        if (!presentation.usesOnly(current.references) || !host.validate(current, presentation)) {
            return WidgetAuthoringResult.Rejected.INVALID_PRESENTATION
        }
        return accept(current)
    }

    private suspend inline fun <T> checked(block: () -> WidgetAuthoringResult<T>): WidgetAuthoringResult<T> = try {
        currentCoroutineContext().ensureActive()
        block().also { currentCoroutineContext().ensureActive() }
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        WidgetAuthoringResult.Rejected.FAILED
    }
}
