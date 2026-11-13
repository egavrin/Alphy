package com.alpha.assistant.tools

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.R
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.seedOpenChat
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Exact saved artifacts from the historical and systemic grid captures. No provider calls. */
class FrozenGridRestartWorkflowTest {
    @Test fun frozenGridRestartsAndPersistsTheNextScore() =
        replayGrid("grid-restart-frozen.json", fromChat = false)

    @Test fun liveGridRestartsAndReopensFromItsChatCard() =
        replayGrid("grid-restart-live.json", fromChat = true)

    private fun replayGrid(asset: String, fromChat: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assetPath = "generation-reliability/$asset"
        val artifact = instrumentation.context.assets.open(assetPath).bufferedReader().use { JSONObject(it.readText()) }
        val fixture = ShellFixture("frozen-grid-restart")
        val robot = ShellRobot(fixture.name)
        val database = "${fixture.name}.db-tools.db"
        val output = if (fromChat) "live-grid-restart" else "frozen-grid-restart"
        val directory = File(fixture.context.getExternalFilesDir(null), output).apply {
            deleteRecursively()
            check(mkdirs())
        }
        val proof = JSONObject().put("status", "failed").put("fixture", assetPath)
        val id = "frozen_grid"
        val conversation = if (fromChat) "grid-chat" else null
        fun screenshot(name: String): Boolean {
            val bitmap = robot.automation.takeScreenshot() ?: return false
            return try {
                File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
        try {
            ToolStore(fixture.context, database).use { store ->
                val spec = ToolSpec.parsePersisted(artifact.getJSONObject("spec").toString())
                if (conversation != null) {
                    fixture.store.saveConversation(Conversation(conversation, spec.title, 1, 2, messages = listOf(
                        Message("request", "user", spec.title, 1, "sent"),
                        Message("answer", "assistant", spec.description, 2, "complete", replyTo = "request", toolId = id, toolRevision = 1),
                    )))
                    fixture.store.seedOpenChat(conversation)
                }
                store.saveTool(ToolRecord(id, 1, 1, spec.json, artifact.getString("state"), artifact.getString("ui"),
                    conversation, true), revision = true)
                fixture.install()
                fixture.launch().use {
                    val model = checkNotNull(fixture.model)
                    robot.waitUntil { !model.loading && !model.tools.busy }
                    if (fromChat) robot.click(robot.text(R.string.tool_open))
                    else robot.instrumentation.runOnMainSync { model.openSurface(id) }
                    robot.waitFor(robot.text(R.string.tool_refine))
                    fun reopen() {
                        Plan5WorkflowChecks.reopenSaved(robot, spec.title, fromChat)
                    }
                    Plan5WorkflowChecks(robot, proof, { checkNotNull(store.tool(id)) }, ::screenshot, database)
                        .run("extra:russian-grid-game", ::reopen) { error("Unexpected refinement") }
                    assertEquals(1, JSONObject(checkNotNull(store.tool(id)).state).getInt("score"))
                    assertEquals(0, fixture.provider.calls)
                    proof.put("status", "passed")
                }
            }
        } finally {
            File(directory, "workflow.json").writeText(proof.toString(2))
            fixture.model?.let { model ->
                runBlocking { model.tools.closeAndJoin() }
                model.courseStore.close()
                model.memoryStore.close()
            }
            fixture.remove()
            fixture.context.deleteDatabase("${fixture.name}.db-courses.db")
            fixture.context.deleteDatabase("${fixture.name}.db-memory.db")
        }
    }
}
