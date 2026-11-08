package com.alpha.assistant.data

import android.content.Context
import android.database.DatabaseUtils
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.chat.RefinementChoice
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/** Failure cases: an unsafe default, a missing or invalid stored value treated as clean,
 * a state lost across reopen, or a change to existing history, drafts, or attachments. */
class MessageProvenanceWorkflowTest {
    @Test fun hostProvenanceAndConversationFieldsSurviveReopen() = withDatabase { context, name ->
        val attachment = Attachment("content://fixture/note", "Note", "text/plain", "Attachment text")
        val original = Message(
            id = "assistant",
            role = "assistant",
            text = """{"provenance":"CLEAN"}""",
            createdAt = 3,
            status = "sent",
            attachments = listOf(attachment, attachment.copy(contextText = null)),
            replyTo = "user",
            search = true,
            searchStatus = "complete",
            sources = listOf(Source("Source", "https://example.test", "Summary", "Site", "Icon")),
            error = "Error",
            errorCode = "fixture_error",
            toolId = "tool",
            toolRevision = 2,
            toolOperationId = "operation",
            queueOrder = 4,
            hostTargetId = "host",
            requiresRefinement = true,
            refinementChoices = listOf(RefinementChoice("tool", "Choice")),
        )
        assertEquals(MessageProvenance.UNKNOWN, original.provenance)
        val conversation = Conversation(
            id = "fixture",
            title = "Fixture",
            createdAt = 1,
            updatedAt = 4,
            draft = Draft("Conversation draft", listOf(attachment), "draft"),
            messages = listOf(
                Message("user", "user", "User text", 2, "sent", provenance = MessageProvenance.CLEAN),
                original.copy(id = "derived", provenance = MessageProvenance.MEMORY_DERIVED),
                original,
            ),
            refinementTargetId = original.id,
            refinementDraft = Draft("Refinement draft", listOf(attachment), "refinement"),
        )
        val newDraft = Draft("New draft", listOf(attachment), "new")
        ShellStore(context, name).use { store -> store.createFromNewDraft(conversation, newDraft) }

        ShellStore(context, name).use { store ->
            val restored = store.load()
            assertEquals(conversation, restored.conversations.single())
            assertEquals(newDraft, restored.newDraft)
        }
    }

    @Test fun missingAndInvalidStoredProvenancePreserveHistoryAsUnknown() = withDatabase { context, name ->
        val body = """
            {"id":"fixture","title":"Fixture","createdAt":1,"updatedAt":2,
             "draft":{"id":"draft","text":"Keep draft","attachments":[]},"messages":[
             {"id":"old","role":"assistant","text":"Keep history","createdAt":1,"status":"sent",
              "attachments":[{"uri":"content://fixture/note","name":"Note","mime":"text/plain"}]},
             {"id":"future","role":"assistant","text":"Future","createdAt":2,"status":"sent",
              "attachments":[],"provenance":"FUTURE_VALUE"},
             {"id":"wrong-type","role":"assistant","text":"Wrong type","createdAt":2,"status":"sent",
              "attachments":[],"provenance":["CLEAN"]},
             {"id":"null","role":"assistant","text":"Null","createdAt":2,"status":"sent",
              "attachments":[],"provenance":null},
             {"id":"old-user","role":"user","text":"Old user text","createdAt":2,"status":"sent",
              "attachments":[]}]}
        """.trimIndent()
        ShellStore(context, name).use { store ->
            store.writableDatabase.execSQL(
                "INSERT INTO conversations (id, updated_at, body) VALUES (?, ?, ?)",
                arrayOf<Any>("fixture", 2L, body),
            )
        }

        ShellStore(context, name).use { store ->
            val restored = store.load().conversations.single()
            assertEquals(Draft("Keep draft", id = "draft"), restored.draft)
            assertEquals(
                listOf("Keep history", "Future", "Wrong type", "Null", "Old user text"),
                restored.messages.map { it.text },
            )
            assertEquals(List(5) { MessageProvenance.UNKNOWN }, restored.messages.map { it.provenance })
            assertEquals(
                Attachment("content://fixture/note", "Note", "text/plain"),
                restored.messages.first().attachments.single(),
            )
            val storedBody = DatabaseUtils.stringForQuery(store.readableDatabase, "SELECT body FROM conversations", null)
            assertEquals(body, storedBody)
        }
    }

    private fun withDatabase(block: (Context, String) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "message-provenance-${UUID.randomUUID()}.db"
        try {
            block(context, name)
        } finally {
            context.deleteDatabase(name)
        }
    }
}
