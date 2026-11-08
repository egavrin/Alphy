package com.alpha.assistant.data

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Failure cases: a reused identity after Send, a changed identity after reopen,
 * a lost preexisting draft, or a copy that changes the current draft identity. */
class DraftIdentityWorkflowTest {
    @Test fun draftIdentitySurvivesReopenAndChangesAfterSubmission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "draft-identity-${UUID.randomUUID()}.db"
        try {
            ShellStore(context, name).use { store ->
                val originalJson = JSONObject().put("text", "Keep identical text")
                    .put("attachments", JSONArray().put(JSONObject().put("uri", "content://fixture/image")
                        .put("name", "Keep image").put("mime", "image/jpeg")))
                store.saveSetting("newDraft", originalJson.toString())
                val before = store.load().newDraft
                assertEquals("Keep identical text", before.text)
                assertEquals("Keep image", before.attachments.single().name)
                assertEquals(before.id, before.copy(text = "Edited").id)
                assertEquals(before.id, store.load().newDraft.id)
                ShellStore(context, name).use { assertEquals(before, it.load().newDraft) }
                val next = Draft(before.text, before.attachments)
                assertNotEquals(before.id, next.id)
                val conversation = Conversation("fixture", "Fixture", 1, 1, Draft())
                store.createFromNewDraft(conversation, next)
                assertEquals(next, store.load().newDraft)
                assertEquals(conversation, store.load().conversations.single())
                assertNotEquals(before.id, store.load().newDraft.id)
                ShellStore(context, name).use { assertEquals(next.id, it.load().newDraft.id) }
            }
        } finally { context.deleteDatabase(name) }
    }
}
