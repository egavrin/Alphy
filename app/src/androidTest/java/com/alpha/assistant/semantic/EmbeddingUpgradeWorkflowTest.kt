package com.alpha.assistant.semantic

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.automations.AutomationStore
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Draft
import com.alpha.assistant.data.Message
import com.alpha.assistant.data.SecureKeyStore
import com.alpha.assistant.data.ShellStore
import com.alpha.assistant.memory.MemoryStore
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmbeddingUpgradeWorkflowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val mode = InstrumentationRegistry.getArguments().getString("semanticUpgrade")
    private val draft = "Pick up the bicycle from the repair shop"
    private val message = "The black Labrador is called Pepper"
    private val fact = "My favorite color is blue"
    private val syntheticCredential = "fixture-upgrade-key"

    @Test fun preservesSourceStoresAcrossSignedInstall() {
        assumeTrue(context.packageName == "com.alpha.assistant.productionembeddingqa" && mode in setOf("seed", "verify"))
        val keys = SecureKeyStore(context)
        val spec = ToolSpec.parse("""
            {"formatVersion":3,"title":"Trip expenses","description":"Track vacation spending",
            "capabilities":[],"stateSchema":{"total":"number"},"initialState":{"total":42},
            "ui":{"type":"text","text":"Total","bind":"total"},"source":"return {state:input.state};"}
        """.trimIndent())
        if (mode == "seed") {
            ShellStore(context).use { shell ->
                shell.saveConversation(Conversation("upgrade-chat", "Upgrade history", 1, 2,
                    Draft(draft), listOf(Message("upgrade-message", "assistant", message, 2, "complete"))))
            }
            MemoryStore(context).use { it.saveExplicitFact(fact) }
            ToolStore(context).use { store ->
                val app = ToolRecord("upgrade-app", 1, 1, spec.json, spec.initialState, spec.ui.toString(), "upgrade-chat", true)
                store.commitTool(app, null, true)
            }
            AutomationStore(context).use {
                it.save(null, JSONObject("""
                    {"name":"Upgrade automation","trigger":{"type":"manual"},"conditions":{},
                    "actions":[{"id":"notice","capability":"notification.post","args":{"title":"Fixture","text":"Local only"}}]}
                """.trimIndent()))
            }
            keys.save(syntheticCredential).getOrThrow()
        } else {
            ShellStore(context).use {
                val chat = it.load().conversations.single { row -> row.id == "upgrade-chat" }
                assertEquals(draft, chat.draft.text)
                assertEquals(message, chat.messages.single().text)
            }
            MemoryStore(context).use { assertTrue(it.list().any { row -> row.text == fact && row.revision == 1L }) }
            ToolStore(context).use {
                val app = checkNotNull(it.tool("upgrade-app"))
                assertEquals(spec.json, app.spec)
                assertEquals(spec.initialState, app.state)
            }
            AutomationStore(context).use {
                assertTrue(it.rules().any { rule -> rule.definition.name == "Upgrade automation" && !rule.enabled })
            }
            assertTrue(keys.consented())
            assertEquals(syntheticCredential, keys.read().getOrThrow())
        }
    }
}
