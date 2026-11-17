package com.alpha.assistant.ui

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.R
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.Capability
import com.alpha.assistant.cloud.CloudChatProvider
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.cloud.LoopbackServer
import com.alpha.assistant.cloud.jsonResponse
import com.alpha.assistant.cloud.openAiSearchReply
import com.alpha.assistant.cloud.responsesCall
import com.alpha.assistant.cloud.responsesText
import com.alpha.assistant.images.ImageModel
import com.alpha.assistant.images.ImageSettings
import java.io.OutputStream
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Модели» lists the curated models of the account provider, and a request uses the choice (spec 7.4, 7.5, 14.8). */
class AiModelsWorkflowTest {
    private val fixture = ShellFixture("ai-models")
    private val robot = ShellRobot("ai-models")
    @After fun remove() = fixture.remove()

    private fun openSettings(scenario: ActivityScenario<*>) {
        robot.openDrawer(scenario)
        robot.click(robot.text(R.string.settings))
        robot.awaitDrawerClosed(scenario)
        robot.waitUntil { robot.composed(scenario, "settings-pane") }
        robot.click(robot.text(R.string.settings_ai))
    }
    /** Saves [value] with «Согласиться и сохранить» and waits until the account holds the new key. */
    private fun saveKey(value: String, provider: AiProvider) {
        val before = fixture.keys.consentIdentity()
        robot.reveal(robot.text(R.string.key_label), forward = null)
        robot.enter(robot.text(R.string.key_label), value)
        robot.waitFor(robot.text(R.string.key_provider))
        robot.selected(provider.displayName)
        robot.reveal(robot.text(R.string.consent_and_save))
        robot.click(robot.text(R.string.consent_and_save))
        robot.waitUntil { fixture.keys.consentIdentity().let { it != null && it != before } }
        robot.waitUntil { fixture.model!!.accountProvider == provider && !fixture.model!!.keyBusy }
        // A save scrolls the panel to its top. A reveal during that scroll can stop early, so the test waits for the top.
        robot.waitUntil { robot.find(robot.text(R.string.key_ready))?.isVisibleToUser == true && !robot.scrollBackward() }
    }
    private fun openModels() {
        robot.reveal(robot.text(R.string.ai_models), forward = null)
        robot.click(robot.text(R.string.ai_models))
    }
    /** The selected state of the radio row that names [label]. TalkBack reads the same node. A selected row has no click action. */
    private fun checked(label: String): Boolean =
        generateSequence(robot.reveal(label, forward = null)) { it.parent }.first { it.isCheckable }.checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE
    /** Selects the row of [label]. Before the tap the row is not selected, so it is a 48 dp click target (decision 7). */
    private fun choose(label: String) {
        robot.reveal(label, forward = null)
        robot.assertTarget(label)
        robot.click(label)
        robot.waitUntil { checked(label) }
    }
    /** «Модели» shows the curated models of [provider] and no model of another provider (spec 2.7, 11.2). */
    private fun assertLists(scenario: ActivityScenario<*>, provider: AiProvider) {
        val profile = provider.profile
        robot.waitUntil {
            AiProvider.entries.all { other ->
                (other.profile.chatModels + other.profile.speechModels.map { it.title } +
                    ImageModel.entries.filter { it.provider == other }.map { it.title })
                    .all { robot.composedText(scenario, it) == (other == provider) }
            }
        }
        assertTrue(robot.composedText(scenario, robot.text(R.string.ai_models_chat)))
        assertEquals(provider.name, Capability.IMAGES in profile.capabilities, robot.composedText(scenario, robot.text(R.string.ai_models_images)))
        assertEquals(provider.name, Capability.CLOUD_VOICE in profile.capabilities, robot.composedText(scenario, robot.text(R.string.ai_models_voice)))
        assertTrue(robot.reveal(robot.text(R.string.ai_models_chat), forward = null).isHeading)
    }
    private fun revealOpenRouterChatModel() =
        robot.reveal(AiProvider.OPENROUTER.profile.chatModels.first(), forward = null) // One chat model is plain text, not a radio row.

    /** Saves an OpenAI key and chooses its second chat model in the open «Модели». */
    private fun chooseSecondOpenAiModel(scenario: ActivityScenario<*>) {
        openSettings(scenario)
        saveKey(OPENAI_KEY, AiProvider.OPENAI)
        openModels()
        choose(SECOND_OPENAI)
    }
    private fun send(text: String) {
        robot.back()
        robot.back()
        robot.edit(robot.text(R.string.composer_label), text)
        robot.click(robot.text(R.string.composer_send))
    }
    private fun decision(search: String) =
        JSONObject().put("kind", "answer").put("text", "Ответ").put("brief", "").put("search", search).toString()
    private val JSONObject.firstTool: JSONObject? get() = optJSONArray("tools")?.getJSONObject(0)
    /**
     * Chooses the second OpenAI chat model, sends [text] through a loopback provider that reads the same choice,
     * and waits for [result]. [reply] gets the first tool of each request. Every request must name the chosen model.
     */
    private fun requestsWithSecondOpenAiModel(text: String, result: String, reply: (JSONObject?, OutputStream) -> Unit): List<JSONObject> =
        LoopbackServer { raw, output -> reply(JSONObject(raw.body).firstTool, output) }.use { server ->
            // The production wiring of the provider and the model store, with the loopback endpoints.
            fixture.install(chat = CloudChatProvider(fixture.keys, fixture.models, Endpoints { server.url }))
            fixture.launch().use { scenario ->
                openSettings(scenario)
                openModels()
                choose(SECOND_OPENAI)
                assertEquals(SECOND_OPENAI, fixture.models.chat(AiProvider.OPENAI))
                send(text)
                robot.waitFor(result)
                assertTrue(server.failures.toString(), server.failures.isEmpty())
                server.requests.map { JSONObject(it.body) }.onEach { assertEquals(SECOND_OPENAI, it.getString("model")) }
            }
        }

    @Test fun listsOnlyActiveProviderModels() {
        fixture.install()
        fixture.launch().use { scenario ->
            openSettings(scenario)
            openModels()
            assertLists(scenario, AiProvider.OPENAI)
            assertTrue(robot.reveal(robot.text(R.string.ai_models_images), forward = null).isHeading)
            // The defaults are selected. Each row is a target while it is not selected.
            for ((first, second) in listOf(AiProvider.OPENAI.profile.chatModels.first() to SECOND_OPENAI,
                    ImageModel.GPT_IMAGE_FLARE.title to ImageModel.GPT_IMAGE_SUNBURST.title)) {
                assertTrue(checked(first))
                choose(second)
                assertFalse(checked(first))
                robot.reveal(first, forward = null)
                robot.assertTarget(first)
            }
            // The image choice goes to the isolated file of the fixture, not to the app file.
            assertEquals(ImageModel.GPT_IMAGE_SUNBURST, ImageSettings(fixture.context, fixture.keys, fixture.imageStore).model)
            // The list follows the account, so a DeepSeek key replaces the OpenAI models in the open section.
            saveKey(DEEPSEEK_KEY, AiProvider.DEEPSEEK)
            assertLists(scenario, AiProvider.DEEPSEEK)
            // A single model is text, not a choice. DeepSeek has no cloud voice, so assertLists found no voice group.
            assertFalse(robot.reveal("deepseek-flash", forward = null).isClickable)
        }
    }

    // Spec 14.1: the first-run flow shows the same key panel, but not «Модели».
    @Test fun onboardingShowsNoModels() {
        fixture.install(saveKey = false, onboarding = true)
        fixture.launch().use { scenario ->
            robot.click(robot.text(R.string.onboarding_next))
            saveKey(OPENROUTER_KEY, AiProvider.OPENROUTER)
            assertTrue(robot.composedText(scenario, robot.text(R.string.ai_available)))
            assertFalse(robot.composedText(scenario, robot.text(R.string.ai_models)))
        }
    }

    // Spec 6.7: a key of a different provider resets the model choices to its defaults.
    @Test fun selectionResetsOnProviderChange() {
        fixture.install(saveKey = false)
        fixture.launch().use { scenario ->
            val images = checkNotNull(fixture.model).imageGeneration.settings
            chooseSecondOpenAiModel(scenario)
            saveKey(OPENROUTER_KEY, AiProvider.OPENROUTER)
            revealOpenRouterChatModel()
            assertEquals(AiProvider.OPENAI.profile.chatModels.first(), fixture.models.chat(AiProvider.OPENAI))
            choose(ImageModel.SEEDREAM.title)
            saveKey(OPENAI_KEY, AiProvider.OPENAI)
            saveKey(OPENROUTER_KEY + "-2", AiProvider.OPENROUTER)
            revealOpenRouterChatModel()
            assertTrue(checked(ImageModel.QWEN.title))
            robot.reveal("Eleven v4 Turbo", forward = null) // One voice is plain text, not a radio row.
            assertEquals(AiProvider.OPENROUTER.profile.chatModels.first(), fixture.models.chat(AiProvider.OPENROUTER))
            assertEquals(ImageModel.QWEN, images.model)
            assertEquals(AiProvider.OPENROUTER.profile.speechModels.first(), fixture.models.speech(AiProvider.OPENROUTER))
        }
    }

    // Decision 1: «Удалить ключ» keeps the choices, and a later key of a different provider resets them.
    @Test fun deletedKeyThenOtherProviderResets() {
        fixture.install(saveKey = false)
        fixture.launch().use { scenario ->
            chooseSecondOpenAiModel(scenario)
            robot.reveal(robot.text(R.string.key_delete), forward = null)
            robot.click(robot.text(R.string.key_delete))
            robot.click(robot.text(R.string.delete))
            robot.waitUntil { fixture.model!!.accountProvider == null && !fixture.model!!.keyBusy }
            assertEquals(SECOND_OPENAI, fixture.models.chat(AiProvider.OPENAI))
            saveKey(OPENROUTER_KEY, AiProvider.OPENROUTER)
            // Without an account the section left the composition, so it is closed again.
            openModels()
            revealOpenRouterChatModel()
            assertEquals(AiProvider.OPENAI.profile.chatModels.first(), fixture.models.chat(AiProvider.OPENAI))
            assertEquals(AiProvider.OPENROUTER.profile.chatModels.first(), fixture.models.chat(AiProvider.OPENROUTER))
        }
    }

    // Anthropic has no image model and no cloud voice, so «Модели» shows only the chat group.
    @Test fun anthropicShowsChatOnly() {
        fixture.install(saveKey = false)
        fixture.launch().use { scenario ->
            openSettings(scenario)
            saveKey(ANTHROPIC_KEY, AiProvider.ANTHROPIC)
            openModels()
            assertLists(scenario, AiProvider.ANTHROPIC)
            assertFalse(robot.composedText(scenario, robot.text(R.string.ai_models_images)))
            assertFalse(robot.composedText(scenario, robot.text(R.string.ai_models_voice)))
        }
    }

    @Test fun sameProviderKeyKeepsSelection() {
        fixture.install(saveKey = false)
        fixture.launch().use { scenario ->
            chooseSecondOpenAiModel(scenario)
            saveKey(OPENAI_KEY + "-2", AiProvider.OPENAI)
            assertEquals(SECOND_OPENAI, fixture.models.chat(AiProvider.OPENAI))
            // A closed section leaves the composition, so the reopened section reads the stored choice.
            openModels()
            robot.waitGone(SECOND_OPENAI)
            openModels()
            assertTrue(checked(SECOND_OPENAI))
        }
    }

    @Test fun requestUsesSelectedModel() {
        val requests = requestsWithSecondOpenAiModel("Привет", "Ответ модели") { tool, output ->
            if (tool?.optString("name") == "submit_chat_decision") output.responsesCall("call_decision", "submit_chat_decision", decision("none"))
            else output.responsesText("Ответ модели")
        }
        assertEquals(2, requests.size)
    }

    // Spec 7.5: the chosen chat model also runs the search.
    @Test fun searchUsesSelectedModel() {
        // The search finds no source, so the request ends with the error of an empty search.
        val requests = requestsWithSecondOpenAiModel("Новости Казани",
                "Web search returned no sources. The requested facts could not be verified. Retry with a more specific request.") { tool, output ->
            when {
                tool?.optString("type") == "web_search" -> output.jsonResponse(openAiSearchReply("[]", emptyList()))
                tool?.optString("name") == "web_search" -> output.responsesCall("call_search", "web_search", "{\"query\":\"Новости Казани\"}")
                else -> output.responsesCall("call_decision", "submit_chat_decision", decision("required"))
            }
        }
        assertEquals(3, requests.size)
        assertEquals(1, requests.count { it.firstTool?.optString("type") == "web_search" })
    }

    private companion object {
        // Fake keys. Each prefix selects one provider (spec 6.3).
        const val OPENAI_KEY = "sk-proj-alpha-test-only"
        const val OPENROUTER_KEY = "sk-or-v1-alpha-test-only"
        val DEEPSEEK_KEY = fakeDeepSeekKey()
        const val ANTHROPIC_KEY = "sk-ant-api03-alpha-test-only"
        val SECOND_OPENAI = AiProvider.OPENAI.profile.chatModels[1]
    }
}
