package com.alpha.assistant.tools

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.MainActivity
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.images.ImageGeneration
import com.alpha.assistant.images.ImageProvider
import com.alpha.assistant.images.ImageSettings
import com.alpha.assistant.images.ProviderImage
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.three.ThreeSceneWebView
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Failure case: the agent loop could not build 3D scenes, so a request for a rotating planet became a flat drawing. */
@RunWith(AndroidJUnit4::class)
class AgentThreeSceneWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun qualifiedWebView() = assumeTrue("This device has no qualified WebView for 3D scenes", ThreeSceneWebView.available())

    private fun cube(start: String = "", texture: String = "", color: String = "0xffaa33") = JSONObject("""
        {"title":"Cube","description":"A cube that turns","capabilities":[],"runtime":{"kind":"threejs-worker","version":1},
        "stateSchema":{"turns":"number"},"initialState":{"turns":0},
        "ui":{"type":"column","children":[{"type":"metric","text":"Turns","bind":"turns"},{"type":"button","text":"Turn","action":"turn"}]}}
    """.trimIndent()).put("source", """
        export async function createScene(context) {
            const {THREE} = context;
            $start
            const renderer = new THREE.WebGLRenderer({canvas: context.canvas, antialias: true});
            renderer.setSize(context.viewport.width, context.viewport.height, false);
            const scene = new THREE.Scene();
            scene.background = new THREE.Color(0x101820);
            scene.add(new THREE.AmbientLight(0xffffff, 1.5));
            const camera = new THREE.PerspectiveCamera(50, context.viewport.width / context.viewport.height, 0.1, 50);
            camera.position.z = 3;
            const mesh = new THREE.Mesh(new THREE.BoxGeometry(1.4, 1.4, 1.4), new THREE.MeshStandardMaterial({color: $color}));
            scene.add(mesh);
            $texture
            const state = {...context.state};
            const draw = () => renderer.render(scene, camera);
            draw();
            return {
                event(e) {
                    if (e.type === 'viewport') { renderer.setSize(e.width, e.height, false); camera.aspect = e.width / e.height; camera.updateProjectionMatrix(); }
                    if (e.type === 'action' && e.actionId === 'turn') { state.turns += 1; mesh.rotation.y += Math.PI / 4; }
                    draw();
                },
                frame(f) { mesh.rotation.x += f.dtMs / 1000; draw(); },
                snapshot() { return {state}; },
                dispose() { renderer.dispose(); }
            };
        }
    """.trimIndent())

    private val turns = """[{"tap":"turn"},{"pointer":"scene","x":0.5,"y":0.5},{"wait":200},{"tap":"turn"},{"expect":"value","target":"turns","equals":2}]"""
    private val finish = JSONObject().put("tool", "finish").put("summary", "The cube is ready.")
        .put("checks", JSONArray().put(JSONObject().put("name", "turns").put("steps", JSONArray(turns)))).toString()

    @Test fun aSceneErrorReachesTheModelAndTheFixedSceneRunsChecks(): Unit = runBlocking {
        val provider = AgentScriptProvider(writeAppReply(cube(start = "throw new Error('missing planet radius');")), writeAppReply(cube()),
            JSONObject().put("tool", "run").put("steps", JSONArray(turns)).toString(), finish)
        val preview = ActivityScenario.launch(MainActivity::class.java).use {
            ToolGenerationService(provider, SandboxClient(context))
                .generate("fixture", "A 3D cube that turns with a button", null, emptyList(), CloudSession(), "agent-three-${UUID.randomUUID()}")
        }
        assertTrue(provider.calls.first().systemInstructions.contains("createScene"))
        assertTrue(provider.feedback(1), provider.feedback(1).contains("write_app: REJECTED"))
        assertTrue(provider.feedback(1), provider.feedback(1).contains("missing planet radius"))
        assertTrue(provider.feedback(2), provider.feedback(2).contains("write_app: OK"))
        assertTrue(provider.feedback(2), provider.feedback(2).contains("3D viewport"))
        assertTrue(provider.feedback(3), provider.feedback(3).contains("run: OK"))
        assertTrue(preview.spec.threeScene)
        assertEquals(4, provider.calls.size)
    }

    @Test fun aTextureIsMadeBoundAndKept(): Unit = runBlocking {
        val settings = ImageSettings(context, "agent-three-${UUID.randomUUID()}")
        val images = ImageGeneration(context, settings, mapOf("openrouter" to ImageProvider { _, _, _ ->
            val bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
            val bytes = ByteArrayOutputStream()
            try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) } finally { bitmap.recycle() }
            ProviderImage(bytes.toByteArray(), "image/png")
        }))
        val texture = JSONObject("""{"tool":"generate_image","images":[
            {"slot":"skin","prompt":"Blue paint texture","alt":"Blue paint","aspect":"2:1"}]}""").toString()
        val provider = AgentScriptProvider(texture, writeAppReply(cube(texture = "await context.bindTexture('skin', mesh);", color = "0xffffff")), finish)
        val name = "agent-three-${UUID.randomUUID()}"
        try {
            settings.saveKey("fixture-only").getOrThrow()
            settings.setEnabled(true).getOrThrow()
            ToolStore(context, name).use { store ->
                val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context), images))
                val draft = engine.createDraft("A 3D cube with a blue paint texture", null)
                val generated = ActivityScenario.launch(MainActivity::class.java).use {
                    runCatching { engine.generate(draft.id, "fixture", emptyList(), CloudSession()) }
                }
                assertTrue(provider.feedback(2), provider.feedback(2).contains("write_app: OK"))
                generated.getOrThrow()
                val saved = ToolSpec.parsePersisted(engine.accept(draft.id).spec)
                assertEquals(listOf("skin"), saved.generatedImages.map { it.slot })
                assertTrue(saved.sceneAssets.any { it.id == saved.generatedImages.single().asset.id })
            }
        } finally {
            settings.clearKey()
            context.deleteDatabase(name)
        }
    }
}
