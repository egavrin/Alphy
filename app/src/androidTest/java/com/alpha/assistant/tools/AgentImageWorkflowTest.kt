package com.alpha.assistant.tools

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.images.ImageAspect
import com.alpha.assistant.images.ImageGeneration
import com.alpha.assistant.images.ImageProvider
import com.alpha.assistant.images.ImageSettings
import com.alpha.assistant.images.ProviderImage
import com.alpha.assistant.sandbox.SandboxClient
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Failure case: the agent loop could not make pictures, and a follow-up edit dropped the saved ones. */
@RunWith(AndroidJUnit4::class)
class AgentImageWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun recipe(slot: String, title: String = "Tomato salad") = JSONObject("""
        {"title":"$title","description":"A salad recipe with a picture",
        "stateSchema":{"done":"boolean"},"initialState":{"done":false},
        "source":"const s=input.state;if(input.event.type==='action')s.done=input.event.value;return {state:s};",
        "ui":{"type":"column","children":[
          {"type":"image","assetSlot":"$slot","text":"A tomato salad in a bowl"},
          {"type":"text","text":"Cut the tomatoes and add olive oil."},
          {"type":"checkbox","text":"Done","bind":"done","action":"done"}]}}
    """.trimIndent())

    private val picture = JSONObject("""{"tool":"generate_image","images":[
        {"slot":"salad","prompt":"A tomato salad in a white bowl, top view","alt":"A tomato salad in a bowl","aspect":"3:2"}]}""").toString()

    private val finish = JSONObject().put("tool", "finish").put("summary", "The recipe is ready.").put("checks", JSONArray("""
        [{"name":"done","steps":[{"set":"done","value":true},{"expect":"value","target":"done","equals":true}]}]""")).toString()

    private fun withImages(enabled: Boolean, body: suspend (ImageGeneration, AtomicInteger) -> Unit) = runBlocking {
        val settings = ImageSettings(context, "agent-image-${UUID.randomUUID()}")
        val count = AtomicInteger()
        val images = ImageGeneration(context, settings, mapOf("openrouter" to ImageProvider { _, _, intent ->
            count.incrementAndGet()
            val bitmap = if (intent.aspect == ImageAspect.LANDSCAPE) Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888)
                else Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream()
            try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) } finally { bitmap.recycle() }
            ProviderImage(bytes.toByteArray(), "image/png")
        }))
        try {
            settings.saveKey("fixture-only").getOrThrow()
            settings.setEnabled(enabled).getOrThrow()
            body(images, count)
        } finally {
            settings.clearKey()
        }
    }

    @Test fun anAppMakesAPictureShowsItAndAFollowUpKeepsIt() = withImages(enabled = true) { images, count ->
        val provider = AgentScriptProvider(writeAppReply(recipe("salad")), picture, writeAppReply(recipe("salad")), finish,
            JSONObject().put("tool", "edit_app").put("set", JSONObject().put("title", "Summer salad")).toString(), finish)
        val name = "agent-images-${UUID.randomUUID()}"
        ToolStore(context, name).use { store ->
            val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context), images))
            val draft = engine.createDraft("A tomato salad recipe with a picture", null)
            engine.generate(draft.id, "fixture", emptyList(), CloudSession())
            val instructions = provider.calls.first().systemInstructions
            assertTrue(instructions.contains("generate_image"))
            assertTrue(provider.feedback(1), provider.feedback(1).contains("write_app: REJECTED"))
            assertTrue(provider.feedback(1), provider.feedback(1).contains("generate_image"))
            assertTrue(provider.feedback(2), provider.feedback(2).contains("generate_image: OK"))
            assertTrue(provider.feedback(2), provider.feedback(2).contains("salad"))
            assertTrue(provider.feedback(3), provider.feedback(3).contains("write_app: OK"))
            val saved = engine.accept(draft.id)
            assertEquals(listOf("salad"), ToolSpec.parsePersisted(saved.spec).generatedImages.map { it.slot })
            assertEquals(1, count.get())

            val edit = engine.createDraft("Rename it to Summer salad", saved.id)
            engine.generate(edit.id, "fixture", emptyList(), CloudSession())
            val first = provider.calls[4].turns.first().text
            assertTrue(first, first.contains("salad"))
            val renamed = ToolSpec.parsePersisted(engine.accept(edit.id).spec)
            assertEquals("Summer salad", renamed.title)
            assertEquals(listOf("salad"), renamed.generatedImages.map { it.slot })
            assertEquals(1, count.get())
        }
        context.deleteDatabase(name)
    }

    @Test fun picturesOffHideTheToolAndRejectACall() = withImages(enabled = false) { images, count ->
        val plain = recipe("salad").apply {
            getJSONObject("ui").put("children", getJSONObject("ui").getJSONArray("children").let { children ->
                JSONArray().put(children.get(1)).put(children.get(2))
            })
        }
        val provider = AgentScriptProvider(picture, writeAppReply(plain), finish)
        val name = "agent-images-off-${UUID.randomUUID()}"
        ToolStore(context, name).use { store ->
            val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context), images))
            val draft = engine.createDraft("A tomato salad recipe with a picture", null)
            engine.generate(draft.id, "fixture", emptyList(), CloudSession())
            assertFalse(provider.calls.first().systemInstructions.contains("generate_image"))
            assertTrue(provider.feedback(1), provider.feedback(1).contains("generate_image: REJECTED"))
            assertTrue(ToolSpec.parsePersisted(engine.accept(draft.id).spec).generatedImages.isEmpty())
            assertEquals(0, count.get())
        }
        context.deleteDatabase(name)
    }
}
