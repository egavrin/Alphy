package com.alpha.assistant.surfaces.widgetauthoring

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers canceled and failed drafts, stale identities and versions, and invalid presentation authority. */
class WidgetAuthoringTest {
    private val request = WidgetDraftRequest("Show the count and the reset control")
    private val count = WidgetPresentationRow.Text("Count", binding = "count")
    private val reset = WidgetPresentationRow.Button("Reset", "reset")
    private val references = WidgetReferences(
        bindings = setOf("count", "active", "otherFlag"),
        actions = mapOf("reset" to WidgetAction.Button, "activate" to WidgetAction.Toggle("active")),
    )

    @Test fun previewReadsSharedStateAndConfirmationOnlyProposesWidgetPresentation() = runBlocking {
        val host = Host()
        val original = host.current
        val mutableRows = mutableListOf(count, reset)
        val authoring = WidgetAuthoring(host, WidgetDraftProducer { actualRequest, allowed ->
            assertEquals(request, actualRequest)
            assertEquals(references, allowed)
            WidgetPresentation(mutableRows)
        })
        val first = authoring.prepare(request, original).ready()
        assertEquals(listOf("Count: 4", "Reset"), first.content.rows.map { it.label })
        assertEquals(original, host.current)
        mutableRows.clear()
        val proposal = first.proposeSave().ready()
        assertEquals(original.base, proposal.base)
        assertEquals(listOf(count, reset), proposal.presentation.rows)
        assertEquals(original, host.current)

        host.current = original.copy(base = original.base.copy(stateVersion = 5), state = 9)
        val second = WidgetAuthoring(host, WidgetDraftProducer { _, _ -> WidgetPresentation(listOf(count, reset)) })
            .prepare(request, host.current).ready()
        assertEquals("Count: 9", second.content.rows.first().label)
        second.cancel()
        assertEquals(WidgetAuthoringResult.Rejected.CANCELLED, second.proposeSave())
        assertEquals(9, host.current.state)
    }

    @Test fun staleRequestAndStaleSaveNeverProduceAProposal() = runBlocking {
        val host = Host()
        val original = host.current
        var generated = false
        val authoring = WidgetAuthoring(host, WidgetDraftProducer { _, _ ->
            generated = true
            WidgetPresentation(listOf(count))
        })
        val staleBases = listOf(
            original.base.copy(stateVersion = 7),
            original.base.copy(revision = 2),
            original.base.copy(presentationVersion = 2),
            original.base.copy(app = original.base.app.copy(libraryGeneration = "different-library")),
            original.base.copy(app = original.base.app.copy(id = "different-app")),
        )
        host.current = original.copy(base = staleBases.first())
        assertEquals(WidgetAuthoringResult.Rejected.STALE, authoring.prepare(request, original))
        assertFalse(generated)
        for (base in staleBases) {
            host.current = original
            val review = authoring.prepare(request, original).ready()
            host.current = original.copy(base = base)
            assertEquals(WidgetAuthoringResult.Rejected.STALE, review.proposeSave())
        }
    }

    @Test fun appChangeDuringGenerationRejectsTheDraftBeforePreview() = runBlocking {
        val host = Host()
        val authoring = WidgetAuthoring(host, WidgetDraftProducer { _, _ ->
            host.current = host.current.copy(base = host.current.base.copy(stateVersion = 5), state = 9)
            WidgetPresentation(listOf(count))
        })
        assertEquals(WidgetAuthoringResult.Rejected.STALE, authoring.prepare(request, host.current))
        assertEquals(0, host.previews)
    }

    @Test fun invalidReferencesAndPayloadsDoNotReachPreview() = runBlocking {
        val host = Host()
        val invalid = listOf(
            listOf(WidgetPresentationRow.Text("Foreign state", "private")),
            listOf(WidgetPresentationRow.Button("Foreign action", "foreign")),
            listOf(WidgetPresentationRow.Button("Wrong payload", "activate")),
            listOf(WidgetPresentationRow.Toggle("Wrong binding", "otherFlag", "activate")),
            listOf(WidgetPresentationRow.Toggle("Wrong payload", "active", "reset")),
            listOf(reset, reset),
            emptyList(),
        )
        for (rows in invalid) {
            val authoring = WidgetAuthoring(host, WidgetDraftProducer { _, _ -> WidgetPresentation(rows) })
            assertEquals(WidgetAuthoringResult.Rejected.INVALID_PRESENTATION, authoring.prepare(request, host.current))
        }
        assertEquals(0, host.previews)

        val valid = WidgetPresentationRow.Toggle("Active", "active", "activate")
        val authoring = WidgetAuthoring(host, WidgetDraftProducer { _, _ -> WidgetPresentation(listOf(valid)) })
        val review = authoring.prepare(request, host.current).ready()
        host.permitted = false
        assertEquals(WidgetAuthoringResult.Rejected.INVALID_PRESENTATION, review.proposeSave())
        assertEquals(1, host.previews)
    }

    @Test fun missingAdaptersInvalidPromptAndProducerFailureHaveNoPreview() = runBlocking {
        val host = Host()
        val producer = WidgetDraftProducer { _, _ -> error("Producer failed") }
        assertEquals(WidgetAuthoringResult.Rejected.UNAVAILABLE, WidgetAuthoring<Int>(null, producer).prepare(request, host.current))
        assertEquals(WidgetAuthoringResult.Rejected.UNAVAILABLE, WidgetAuthoring(host, null).prepare(request, host.current))
        assertEquals(WidgetAuthoringResult.Rejected.INVALID_REQUEST, WidgetAuthoring(host, producer).prepare(WidgetDraftRequest(" "), host.current))
        assertEquals(WidgetAuthoringResult.Rejected.FAILED, WidgetAuthoring(host, producer).prepare(request, host.current))
        val cancelled = WidgetAuthoring(host, WidgetDraftProducer { _, _ -> null })
        assertEquals(WidgetAuthoringResult.Rejected.CANCELLED, cancelled.prepare(request, host.current))
        assertEquals(0, host.previews)
    }

    @Test fun coroutineCancellationDoesNotPublishAPreview() = runBlocking {
        val host = Host()
        val started = CompletableDeferred<Unit>()
        val resumeProducer = CompletableDeferred<WidgetPresentation>()
        val authoring = WidgetAuthoring(host, WidgetDraftProducer { _, _ ->
            started.complete(Unit)
            resumeProducer.await()
        })
        val draft = async { authoring.prepare(request, host.current) }
        started.await()
        draft.cancel()
        assertTrue(runCatching { draft.await() }.exceptionOrNull() is CancellationException)
        assertEquals(0, host.previews)
        assertFalse(resumeProducer.isCompleted)
    }

    @Test fun cancelDuringConfirmationDiscardsTheProposal() = runBlocking {
        val host = Host()
        val authoring = WidgetAuthoring(host, WidgetDraftProducer { _, _ -> WidgetPresentation(listOf(count)) })
        val review = authoring.prepare(request, host.current).ready()
        val started = CompletableDeferred<Unit>()
        val resumeRead = CompletableDeferred<Unit>()
        host.beforeRead = {
            started.complete(Unit)
            resumeRead.await()
        }
        val confirmation = async { review.proposeSave() }
        started.await()
        review.cancel()
        resumeRead.complete(Unit)
        assertEquals(WidgetAuthoringResult.Rejected.CANCELLED, confirmation.await())
    }

    private inner class Host : WidgetDraftHost<Int> {
        var current = WidgetAppSnapshot(WidgetDraftBase(WidgetAppId("library", "app"), 1, 4, 1), references, 4)
        var permitted = true
        var previews = 0
        var beforeRead: suspend () -> Unit = {}

        override suspend fun latest(app: WidgetAppId): WidgetAppSnapshot<Int> {
            beforeRead()
            return current
        }

        override fun validate(snapshot: WidgetAppSnapshot<Int>, presentation: WidgetPresentation): Boolean {
            assertEquals(current, snapshot)
            return permitted
        }

        override fun render(snapshot: WidgetAppSnapshot<Int>, presentation: WidgetPresentation): WidgetPreviewContent {
            assertEquals(current, snapshot)
            previews++
            return WidgetPreviewContent("Test app", presentation.rows.map { row ->
                when (row) {
                    is WidgetPresentationRow.Text -> WidgetPreviewRow.Text(if (row.binding == "count") "${row.label}: ${snapshot.state}" else row.label)
                    is WidgetPresentationRow.Button -> WidgetPreviewRow.Button(row.label)
                    is WidgetPresentationRow.Toggle -> WidgetPreviewRow.Toggle(row.label, true)
                }
            })
        }
    }

    private fun <T> WidgetAuthoringResult<T>.ready(): T = (this as WidgetAuthoringResult.Ready<T>).value
}
