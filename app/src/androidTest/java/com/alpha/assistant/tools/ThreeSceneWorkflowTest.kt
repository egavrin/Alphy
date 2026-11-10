package com.alpha.assistant.tools

import android.app.AlertDialog
import android.app.Application
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.MainActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.data.Draft
import com.alpha.assistant.data.ShellStore
import com.alpha.assistant.data.SecureKeyStore
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.generation.GenerationBehavior
import com.alpha.assistant.tools.three.ThreeSceneAsset
import com.alpha.assistant.tools.three.ThreeSceneAssetCleanup
import com.alpha.assistant.tools.three.ThreeSceneAssets
import com.alpha.assistant.tools.three.ThreeSceneForeground
import com.alpha.assistant.tools.three.ThreeSceneFailure
import com.alpha.assistant.tools.three.ThreeSceneProblem
import com.alpha.assistant.tools.three.ThreeSceneSession
import com.alpha.assistant.tools.three.ThreeSceneWebView
import com.alpha.assistant.tools.three.checkThreeScene
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.seedOpenChat
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreeSceneWorkflowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val output get() = File(context.getExternalFilesDir(null), "three-scene-evidence").apply { mkdirs() }

    @Test fun texturedWorkerChecksActualSurfaceDrawAndLaterBindingChanges() = runBlocking {
        val png = java.io.ByteArrayOutputStream().use { bytes ->
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.CYAN)
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
                bytes.toByteArray()
            } finally { bitmap.recycle() }
        }
        val cleanup = ThreeSceneAssetCleanup(context)
        cleanup.lease().use {
            val asset = ThreeSceneAssets(context).importGenerated(png, "paint", "image/png")
            val image = com.alpha.assistant.images.GeneratedImage("paint", "Cyan paint",
                com.alpha.assistant.images.ImageAspect.SQUARE, asset, 16, 16, "fixture")
            val setup = """
                const {THREE, canvas, bindTexture} = context;
                const renderer = new THREE.WebGLRenderer({canvas});
                const scene = new THREE.Scene();
                const camera = new THREE.PerspectiveCamera(50, 1, 0.1, 10);
                camera.position.z = 3;
                const mesh = new THREE.Mesh(new THREE.BoxGeometry(), new THREE.MeshBasicMaterial());
                scene.add(mesh);
            """.trimIndent()
            val bind = "await bindTexture('paint', mesh);"
            val draw = "renderer.render(scene, camera);"
            val offscreen = "renderer.setRenderTarget(new THREE.WebGLRenderTarget(16, 16)); $draw"
            fun tool(start: String, update: String = ""): ToolRecord {
                val raw = JSONObject("""{"formatVersion":3,"title":"Texture check","description":"A textured cube",
                    "capabilities":[],"runtime":{"kind":"threejs-worker","version":1},"stateSchema":{"ready":"boolean"},"initialState":{"ready":true},
                    "ui":{"type":"text","text":"Texture check"}}""")
                    .put("source", """
                        export async function createScene(context) {
                            $setup
                            $start
                            return {
                                event() {},
                                frame() { $update },
                                snapshot() { return {state:{ready:true}}; },
                                dispose() { renderer.dispose(); }
                            };
                        }
                    """.trimIndent())
                val spec = ToolSpec.parse(raw.toString()).withThreeAssets(listOf(asset)).withGeneratedImages(listOf(image))
                return ToolRecord(UUID.randomUUID().toString(), 1, 0, spec.json, spec.initialState,
                    spec.ui.toString(), null, true)
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                for (start in listOf("$bind $draw", "$bind context.render(renderer, scene, camera);",
                    "$offscreen renderer.setRenderTarget(null); $bind $draw")) {
                    onScene(tool(start)) { session, _ -> session.frame(16) }
                }
                val rejected = listOf(draw, "$bind $bind $draw", "$bind $offscreen", "$bind renderer.render(scene, {});",
                    "$bind renderer.render(scene, new THREE.ArrayCamera([]));",
                    "mesh.geometry.deleteAttribute('uv'); $bind $draw",
                    "$bind renderer.render(new THREE.Scene(), camera);",
                    "$bind new THREE.WebGLRenderer({canvas:new OffscreenCanvas(16,16)}).render(scene,camera);")
                for (start in rejected) {
                    val failure = runCatching { onScene(tool(start)) { _, _ -> } }.exceptionOrNull()
                    assertEquals(start, com.alpha.assistant.tools.three.ThreeSceneProblem.TEXTURE_BINDING,
                        (failure as? com.alpha.assistant.tools.three.ThreeSceneFailure)?.problem)
                }
                for (update in listOf("mesh.material.map = null;", "mesh.material = mesh.material.clone();",
                    "scene.remove(mesh);", "mesh.geometry.deleteAttribute('uv');")) {
                    val failure = runCatching {
                        onScene(tool("$bind $draw", update)) { session, _ -> session.frame(16) }
                    }.exceptionOrNull()
                    assertEquals(update, com.alpha.assistant.tools.three.ThreeSceneProblem.TEXTURE_BINDING,
                        (failure as? com.alpha.assistant.tools.three.ThreeSceneFailure)?.problem)
                }
            }
            cleanup.request()
        }
    }

    @Test fun texturePlanRequiresExplicitConsistentArraysAndCurrentRequestEvidence() {
        val request = "Generate a square map. Remove the old map."
        val image = """{"slot":"paint","prompt":"A square map","alt":"Map","aspect":"1:1"}"""
        fun plan(json: String) = com.alpha.assistant.images.ImagePlan.threeScene(JSONObject(json), request, setOf("old"))
        assertEquals("paint", plan("""{"disposition":"generate","excerpt":"Generate a square map.",
            "imageRequests":[$image],"removeImageSlots":[]}""").requests.single().slot)
        assertEquals(setOf("old"), plan("""{"disposition":"remove","excerpt":"Remove the old map.",
            "imageRequests":[],"removeImageSlots":["old"]}""").remove)
        assertEquals(emptyList<Any>(), plan("""{"disposition":"unchanged","excerpt":"",
            "imageRequests":[],"removeImageSlots":[]}""").requests)
        for (bad in listOf(
            """{"disposition":"generate","excerpt":"Generate a square map.","removeImageSlots":[]}""",
            """{"disposition":"generate","excerpt":"Generate a square map.","imageRequests":[],"removeImageSlots":[]}""",
            """{"disposition":"generate","excerpt":"A different request","imageRequests":[$image],"removeImageSlots":[]}""",
            """{"disposition":"generate","excerpt":"Generate a square map.","imageRequests":[$image,$image],"removeImageSlots":[]}""",
            """{"disposition":"unchanged","excerpt":"","imageRequests":[$image],"removeImageSlots":[]}""",
            """{"disposition":"remove","excerpt":"Remove the old map.","imageRequests":[],"removeImageSlots":["unknown"]}""",
            """{"disposition":"other","excerpt":"","imageRequests":[],"removeImageSlots":[]}""",
        )) assertTrue(bad, runCatching { plan(bad) }.isFailure)
    }

    @Test fun texturePlanCorrectionAndWriterRepairShareOneImageSubmission() = runBlocking {
        stagedTexture()
    }

    @Test fun failedTexturePolishOffersOnlyTheVerifiedScene() = runBlocking {
        stagedTexture(failPolish = true)
    }

    @Test fun cancelingFailedTexturePolishLeavesNoCandidate() = runBlocking {
        stagedTexture(failPolish = true, cancel = true)
    }

    @Test fun revokingImageConsentBeforeSaveRejectsTheDraft() = runBlocking {
        stagedTexture(revokeConsent = true)
    }

    @Test fun requestedTextureInVisualEvidenceTriggersTheImageStage() = runBlocking {
        stagedTexture(visualEvidenceOnly = true)
    }

    @Test fun confirmedTextureWithoutConsentOffersTheVerifiedCore() = runBlocking {
        stagedTexture(visualEvidenceOnly = true, consent = false)
    }

    @Test fun unchangedTexturePlanCannotSilentlyDropAnExplicitAssetRequest() = runBlocking {
        stagedTexture(imagePlanUnchanged = true)
    }

    private suspend fun stagedTexture(failPolish: Boolean = false, cancel: Boolean = false,
        revokeConsent: Boolean = false, visualEvidenceOnly: Boolean = false, consent: Boolean = true,
        imagePlanUnchanged: Boolean = false) {
        val request = "Generate a fresh square color texture of orange and blue geometric tile art for the cube."
        val fallback = failPolish || !consent || imagePlanUnchanged
        val png = java.io.ByteArrayOutputStream().use { bytes ->
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.CYAN)
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
                bytes.toByteArray()
            } finally { bitmap.recycle() }
        }
        val calls = AtomicInteger()
        val settings = com.alpha.assistant.images.ImageSettings(context, "three-repair-${UUID.randomUUID()}")
        val images = com.alpha.assistant.images.ImageGeneration(context, settings, mapOf("openrouter" to
            com.alpha.assistant.images.ImageProvider { _, _, intent ->
                assertEquals("paint", intent.slot)
                calls.incrementAndGet()
                com.alpha.assistant.images.ProviderImage(png, "image/png")
            }))
        val source = """
            export async function createScene({THREE, canvas, bindTexture}) {
                const renderer = new THREE.WebGLRenderer({canvas});
                const scene = new THREE.Scene();
                const camera = new THREE.PerspectiveCamera(50, 1, 0.1, 10);
                camera.position.z = 3;
                const mesh = new THREE.Mesh(new THREE.BoxGeometry(), new THREE.MeshBasicMaterial());
                scene.add(mesh);
                const attach = false;
                if (attach) await bindTexture('paint', mesh);
                renderer.render(scene, camera);
                return {event(){}, frame(){}, snapshot(){return {state:{ready:true}};}, dispose(){renderer.dispose();}};
            }
        """.trimIndent()
        var texturePlans = 0
        var writerAttempts = 0
        var coreWrites = 0
        val reference = "data:image/png;base64,texture-reference"
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, call: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                val text = when {
                    call.systemInstructions.startsWith("Plan only Three.js texture assets.") -> {
                        assertEquals(0, calls.get())
                        assertEquals(1, coreWrites)
                        assertEquals(listOf(reference), call.turns.single().images)
                        assertEquals(request, JSONObject(call.turns.single().text).getString("request"))
                        texturePlans++
                        if (imagePlanUnchanged)
                            """{"disposition":"unchanged","excerpt":"","imageRequests":[],"removeImageSlots":[]}"""
                        else if (texturePlans == 1) "{}" else
                            """{"disposition":"generate","excerpt":"$request","removeImageSlots":[],
                                "imageRequests":[{"slot":"paint","prompt":"Cyan paint","alt":"Cyan paint","aspect":"1:1"}]}"""
                    }
                    call.generationStage == CloudGenerationStage.PLAN -> """
                        {"wholeScreen":true,"language":"en","runtime":"threejs-worker","scene":{"placement":"stage","brief":"A cyan cube","outcome":{"kind":"static"},
                        "checks":{"status":"checkable","scenario":{"slots":{"view":{"kind":"geometry"}},
                        "steps":[{"op":"assert","slot":"view","test":"nonempty"}]}},
                        "checkEvidence":[{"step":1,"excerpt":"$request","purpose":"outcome","scope":"whole"}]}}
                    """.trimIndent().let(::JSONObject).apply {
                        if (visualEvidenceOnly) getJSONObject("scene").put("visualReview", JSONArray().put(request))
                        else put("assetRequest", request)
                    }.toString()
                    call.turns.last().text.contains("Return one JSON patch") -> {
                        assertEquals(1, calls.get())
                        assertTrue(call.turns.any { reference in it.images && request in it.text })
                        if (++writerAttempts == 1)
                            """{"sourceEdits":[{"search":"const attach = false;","replace":"const attach = 0;"}]}"""
                        else if (failPolish) "{}"
                        else """{"sourceEdits":[{"search":"const attach = 0;","replace":"const attach = true;"}]}"""
                    }
                    else -> JSONObject("""{"formatVersion":3,"title":"Painted cube","description":"A cyan cube",
                        "capabilities":[],"runtime":{"kind":"threejs-worker","version":1},"stateSchema":{"ready":"boolean"},"initialState":{"ready":true},
                        "ui":{"type":"text","text":"Painted cube"}}""").put("source", source).toString().also {
                            assertEquals(0, calls.get())
                            coreWrites++
                        }
                }
                emit(CloudEvent.Text(text))
            }
        }
        val database = "three-image-repair-${UUID.randomUUID()}.db"
        try {
            settings.saveKey("fixture-only").getOrThrow()
            settings.setEnabled(consent).getOrThrow()
            ActivityScenario.launch(MainActivity::class.java).use {
                ToolStore(context, database).use { store ->
                    val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context), imageGeneration = images))
                    try {
                        val draft = engine.createDraft(request, null)
                        val generated = runCatching {
                            respondToPreview(if (cancel) "Cancel" else "Save scene",
                                requiredMessage = "Missing: requested images or textures".takeIf { fallback }) {
                                engine.generate(draft.id, "fixture", listOf(reference), CloudSession())
                            }
                        }
                        if (cancel) {
                            assertEquals("three_declined", (generated.exceptionOrNull() as? CloudFailure)?.code)
                            assertNull(store.draft(draft.id)?.candidate)
                            assertNull(store.tool(draft.id))
                            engine.reject(draft.id)
                        } else {
                            generated.getOrThrow()
                            if (revokeConsent) {
                                settings.setEnabled(false).getOrThrow()
                                assertEquals("image_generation", (runCatching { engine.accept(draft.id) }.exceptionOrNull() as? CloudFailure)?.code)
                                assertNull(store.tool(draft.id))
                                engine.reject(draft.id)
                            } else {
                                val record = engine.accept(draft.id)
                                val spec = ToolSpec.parsePersisted(record.spec)
                                assertEquals(if (fallback) emptyList<String>() else listOf("paint"),
                                    spec.generatedImages.map { it.slot })
                                if (!fallback) {
                                    onScene(record) { scene, _ -> assertEquals(record.state, scene.snapshot.state) }
                                }
                                File(output, "staged-texture-${if (fallback) "fallback" else "saved"}.json")
                                    .writeText(JSONObject(record.spec).toString(2))
                            }
                        }
                        assertEquals(if (imagePlanUnchanged) 1 else 2, texturePlans)
                        assertEquals(when {
                            imagePlanUnchanged || !consent -> 0
                            failPolish -> 4
                            else -> 2
                        }, writerAttempts)
                        assertEquals(if (imagePlanUnchanged || !consent) 0 else 1, calls.get())
                    } finally { engine.closeArtifacts() }
                }
            }
        } finally {
            settings.clearKey()
            context.deleteDatabase(database)
            ThreeSceneAssetCleanup(context).request()
        }
    }

    @Test fun aFiniteSceneRequiresTypedTerminalEvidence() {
        val request = "Create a moving scene. Reach the final result."
        val plan = JSONObject("""{"wholeScreen":true,"language":"en","runtime":"threejs-worker","scene":{
            "placement":"stage","brief":"Move the visible object.",
            "outcome":{"kind":"finite","step":1,"excerpt":"Reach the final result."},
            "checks":{"status":"checkable","scenario":{"slots":{"view":{"kind":"surface"},"object":{"kind":"geometry"}},
                "steps":[{"op":"frames","slot":"view","count":8,"observe":"object","changes":3}]}},
            "checkEvidence":[{"step":1,"excerpt":"Reach the final result.","purpose":"motion","scope":"whole"}]}}""")
        fun parse(candidate: JSONObject = plan, originalRequest: String = request) =
            com.alpha.assistant.tools.generation.NeedsPlan.parseResponse(candidate.toString(), null, emptySet(),
                originalRequest, requireSceneOutcome = true)
        assertTrue(runCatching { parse() }.isFailure)
        val continuousRequest = "Keep the object moving continuously."
        val continuous = JSONObject(plan.toString()).apply {
            getJSONObject("scene").put("outcome", JSONObject("""{"kind":"continuous"}"""))
                .getJSONArray("checkEvidence").getJSONObject(0).put("excerpt", continuousRequest)
        }
        assertNotNull(parse(continuous, continuousRequest))

        val scene = plan.getJSONObject("scene")
        val scenario = scene.getJSONObject("checks").getJSONObject("scenario")
        scenario.getJSONObject("slots").put("result", JSONObject("""{"kind":"state"}"""))
        scenario.getJSONArray("steps").put(JSONObject("""{"op":"assert","slot":"result","test":"equals","value":true}"""))
        scene.put("outcome", JSONObject("""{"kind":"finite","step":2,"excerpt":"Reach the final result."}"""))
        scene.getJSONArray("checkEvidence").put(JSONObject("""{"step":2,"excerpt":"Reach the final result.","purpose":"outcome"}"""))
        assertTrue(runCatching { parse() }.isFailure)

        val steps = scenario.getJSONArray("steps")
        scenario.put("steps", JSONArray().put(JSONObject("""{"op":"assert","slot":"result","test":"equals","value":false}"""))
            .put(steps.getJSONObject(0)).put(steps.getJSONObject(1)))
        scene.getJSONObject("outcome").put("step", 3)
        scene.put("checkEvidence", JSONArray("""[{"step":1,"excerpt":"Reach the final result.","purpose":"outcome"},
            {"step":2,"excerpt":"moving scene","purpose":"motion","scope":"whole"},
            {"step":3,"excerpt":"Reach the final result.","purpose":"outcome"}]"""))
        assertNotNull(parse())
    }

    @Test fun aPointerCanLeadDirectlyToATypedTerminalResult() {
        val request = "Tap the scene to start, then reach the final result."
        val plan = JSONObject("""{"wholeScreen":true,"language":"en","runtime":"threejs-worker","scene":{
            "placement":"stage","brief":"A visible playfield.",
            "outcome":{"kind":"finite","step":4,"excerpt":"reach the final result."},
            "checks":{"status":"checkable","scenario":{"slots":{"view":{"kind":"surface"},"object":{"kind":"geometry"},
                "result":{"kind":"state"}},"steps":[
                {"op":"assert","slot":"object","test":"nonempty"},
                {"op":"surface","slot":"view","event":"pointer","phase":"down","x":0.2,"y":0.5},
                {"op":"surface","slot":"view","event":"pointer","phase":"up","x":0.2,"y":0.5},
                {"op":"complete","slot":"view","observe":"result","value":true,"maxFrames":100}]}},
            "checkEvidence":[{"step":1,"excerpt":"scene","purpose":"outcome","scope":"whole"},
                {"step":4,"excerpt":"reach the final result.","purpose":"outcome"}]}}""")
        assertNotNull(com.alpha.assistant.tools.generation.NeedsPlan.parseResponse(plan.toString(), null, emptySet(), request))
    }

    @Test fun boundedRaceChecksTouchPauseFinishRestartAndReopen() = runBlocking {
        val request = "Create a Three.js race with steering, pause, a finish, and restart."
        val fixturePlan = """
            {"wholeScreen":true,"language":"en","runtime":"threejs-worker","scene":{
              "placement":"stage","brief":"A moving race car with visible progress.",
              "outcome":{"kind":"finite","step":18,"excerpt":"$request"},
              "checks":{"status":"checkable","scenario":{
                "slots":{"track":{"kind":"surface"},"drawing":{"kind":"geometry"},"distance":{"kind":"state"},
                         "steered":{"kind":"state"},"finished":{"kind":"state"},"start":{"kind":"action"},"pause":{"kind":"action"},
                         "resume":{"kind":"action"},"restart":{"kind":"action"}},
                "steps":[
                  {"op":"action","slot":"start"},
                  {"op":"capture","slot":"distance","as":"initial"},
                  {"op":"capture","slot":"drawing","as":"before"},
                  {"op":"surface","slot":"track","event":"pointer","phase":"down","x":0.2,"y":0.5},
                  {"op":"frames","slot":"track","count":8},
                  {"op":"surface","slot":"track","event":"pointer","phase":"up","x":0.2,"y":0.5},
                  {"op":"assert","slot":"steered","test":"equals","value":true},
                  {"op":"assert","slot":"distance","test":"greater","capture":"initial"},
                  {"op":"assert","slot":"drawing","test":"changed","capture":"before"},
                  {"op":"action","slot":"pause"},
                  {"op":"capture","slot":"distance","as":"paused"},
                  {"op":"frames","slot":"track","count":8},
                  {"op":"assert","slot":"distance","test":"unchanged","capture":"paused"},
                  {"op":"surface","slot":"track","event":"reopen"},
                  {"op":"frames","slot":"track","count":8},
                  {"op":"assert","slot":"distance","test":"unchanged","capture":"paused"},
                  {"op":"action","slot":"resume"},
                  {"op":"complete","slot":"track","observe":"finished","value":true,"maxFrames":360},
                  {"op":"assert","slot":"distance","test":"equals","value":300},
                  {"op":"surface","slot":"track","event":"reopen"},
                  {"op":"assert","slot":"finished","test":"equals","value":true},
                  {"op":"action","slot":"restart"},
                  {"op":"assert","slot":"finished","test":"equals","value":false},
                  {"op":"assert","slot":"distance","test":"equals","value":0}
                ]}}}}
        """.trimIndent()
        val race = """
            export async function createScene({THREE, canvas, state}) {
                const renderer = new THREE.WebGLRenderer({canvas});
                const scene = new THREE.Scene();
                const camera = new THREE.PerspectiveCamera(50, 1, 0.1, 10);
                camera.position.z = 3;
                const car = new THREE.Mesh(new THREE.BoxGeometry(), new THREE.MeshBasicMaterial({color:0xee4444}));
                scene.add(car);
                let steer = false;
                function draw() { car.position.x = state.steered ? -0.5 : 0; renderer.render(scene,camera); }
                draw();
                return {
                    event(e) {
                        if (e.type==='action' && (e.actionId==='start' || e.actionId==='resume')) state.playing=true;
                        if (e.type==='action' && e.actionId==='pause') state.playing=false;
                        if (e.type==='pointerdown') steer=true;
                        if (e.type==='pointerup') steer=false;
                        if (e.type==='action' && e.actionId==='restart') Object.assign(state,{playing:false,distance:0,finished:false,steered:false});
                        draw();
                    },
                    frame() {
                        if (state.playing && !state.finished) {
                            if (steer) state.steered=true;
                            state.distance++;
                            if (state.distance===300) { state.playing=false; state.finished=true; }
                        }
                        draw();
                    },
                    snapshot(){return {state};},
                    dispose(){renderer.dispose();}
                };
            }
        """.trimIndent()
        val bindings = """{"track":"scene","drawing":{"surface":"scene","path":[0]},"distance":"distance","steered":"steered","finished":"finished"}"""
        val seed = """{"playing":false,"distance":0,"steered":false,"finished":false}"""
        val definition = JSONObject("""{"formatVersion":3,"title":"Race","description":"","capabilities":[],
            "runtime":{"kind":"threejs-worker","version":1},
            "stateSchema":{"playing":"boolean","distance":"number","steered":"boolean","finished":"boolean"},
            "initialState":$seed,"ui":{"type":"column","children":[
              {"type":"button","text":"Start race","action":"start"},
              {"type":"button","text":"Pause race","action":"pause"},
              {"type":"button","text":"Resume race","action":"resume"},
              {"type":"button","text":"Restart race","action":"restart"}]}}""")
        val database = "three-race-workflow-${UUID.randomUUID()}.db"
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                ToolStore(context, database).use { store ->
                    for (source in listOf(race.replace("if (steer) state.steered=true;", "if (steer) state.steered=false;"),
                        race.replace("state.finished=true;", "state.finished=false;"),
                        race.replace("if (e.type==='action' && e.actionId==='pause')",
                            "if (e.type==='open') state.distance=0; if (e.type==='action' && e.actionId==='pause')"), race)) {
                        val provider = object : ChatProvider {
                            override suspend fun respond(key: String, call: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                                val response = when {
                                    call.generationStage == CloudGenerationStage.PLAN -> call.withFixtureCheckEvidence(fixturePlan)
                                    call.turns.last().text.contains("Return one JSON patch") -> "{}"
                                    else -> JSONObject(definition.toString()).put("source", source)
                                        .put("behaviorBindings", JSONObject(bindings)).toString()
                                }
                                emit(CloudEvent.Text(response))
                            }
                        }
                        val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
                        try {
                            val draft = engine.createDraft(request, null)
                            val result = runCatching { approvePreview { engine.generate(draft.id, "fixture", emptyList(), CloudSession()) } }
                            if (source == race) {
                                result.getOrThrow()
                                val record = engine.accept(draft.id)
                                assertEquals(JSONObject(seed).toString(), record.state)
                                onScene(record) { session, _ -> assertEquals(record.state, session.snapshot.state) }
                                File(output, "bounded-race-verified.json").writeText(record.spec)
                            } else {
                                assertEquals("three_behavior", (result.exceptionOrNull() as? CloudFailure)?.code)
                                assertNull(store.draft(draft.id)?.candidate)
                                engine.reject(draft.id)
                            }
                        } finally { engine.closeArtifacts() }
                    }
                }
            }
        } finally { context.deleteDatabase(database) }
    }

    @Test fun repairedOrbitSavesAndReopens() = runBlocking {
        val result = generate("earth", injectStatic = true)
        assertEquals(1, result.repairs)
        val spec = ToolSpec.parsePersisted(result.record.spec)
        assertTrue(spec.threeScene)
        assertTrue(spec.source.contains("const enabled = true;"))
        ActivityScenario.launch(MainActivity::class.java).use {
            onScene(result.record) { scene, dialog ->
                val before = JSONObject(scene.snapshot.state).getDouble("phase")
                repeat(35) { scene.frame(50) }
                val after = JSONObject(scene.snapshot.state).getDouble("phase")
                assertTrue(after > before + 1)
                saveFrame("earth-orbit", scene, dialog)
                scene.event(JSONObject().put("type", "pause"))
                repeat(3) { scene.frame(50) }
                assertEquals(after, JSONObject(scene.snapshot.state).getDouble("phase"), 0.0)
                scene.event(JSONObject().put("type", "resume"))
                scene.event(scene.pointer("pointerdown", 0, .50, .50))
                scene.event(scene.pointer("pointermove", 0, .67, .50))
                scene.event(scene.pointer("pointerup", 0, .67, .50))
                scene.frame(33)
                saveFrame("earth-camera", scene, dialog)
                assertTrue(JSONObject(scene.snapshot.state).getDouble("phase") > after)
            }
        }
    }

    @Test fun workshopDragsPlaysAndRestoresSavedCheckpoint() = runBlocking {
        val result = generate("workshop")
        val restartRun = InstrumentationRegistry.getArguments().getString("threeRun")
        require(restartRun == null || restartRun.matches(Regex("[a-f0-9]{32}")))
        val name = if (restartRun == null) "three-workshop-" + UUID.randomUUID() + ".db" else "three-live-$restartRun.db"
        var retained = false
        try {
            val saved = ActivityScenario.launch(MainActivity::class.java).use {
                ToolStore(context, name).use { store ->
                    store.saveTool(result.record, true)
                    val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
                    val controller = ToolController(context.applicationContext as Application, controllerScope, result.provider,
                        SecureKeyStore(context), store)
                    try {
                        onScene(result.record) { scene, dialog ->
                            saveFrame("workshop-initial", scene, dialog)
                            scene.event(scene.pointer("pointerdown", 0, .30, .50))
                            scene.event(scene.pointer("pointermove", 0, .70, .50))
                            scene.event(scene.pointer("pointerup", 0, .70, .50))
                            assertEquals("completed", scene.lastAudio)
                            scene.event(scene.pointer("pointerup", 0, .70, .50))
                            assertNull(scene.lastAudio)
                            assertTrue(JSONObject(scene.snapshot.state).getBoolean("placed"))
                            repeat(6) { scene.frame(50) }
                            saveFrame("workshop-drop", scene, dialog)
                            controller.saveThreeScene(result.record, scene.snapshot)
                        }
                    } finally {
                        controller.closeAndJoin()
                        controllerScope.cancel()
                    }
                }
            }
            ToolStore(context, name).use { store ->
                val reopened = checkNotNull(store.tool(saved.id))
                assertEquals(saved.state, reopened.state)
                ActivityScenario.launch(MainActivity::class.java).use {
                    onScene(reopened) { scene, dialog ->
                        assertEquals("Готово", JSONObject(scene.snapshot.state).getString("status"))
                        saveFrame("workshop-reopened", scene, dialog)
                    }
                }
            }
            if (restartRun != null) {
                val evidence = File(context.getExternalFilesDir(null), "three-live/$restartRun").apply { mkdirs() }
                File(evidence, "scene-id.txt").writeText(saved.id)
                File(evidence, "expected-state.json").writeText(saved.state)
                retained = true
            }
        } finally {
            if (!retained) context.deleteDatabase(name)
        }
    }

    @Test fun savedScenesOpenFromChatAndHandleNativeInput() = runBlocking {
        for (name in listOf("earth", "workshop")) {
            val tool = generate(name).record
            val spec = ToolSpec.parsePersisted(tool.spec)
            val fixture = ShellFixture("three-chat")
            val robot = ShellRobot(fixture.name)
            try {
                val chat = "three-chat"
                fixture.store.saveConversation(Conversation(chat, spec.title, 1, 2, messages = listOf(
                    Message("request", "user", "Create a Three.js scene.", 1, "sent"),
                    Message("scene", "assistant", spec.title, 2, "complete", replyTo = "request",
                        toolId = tool.id, toolRevision = tool.revision),
                )))
                fixture.store.seedOpenChat(chat)
                ToolStore(context, "${fixture.store.databaseName}-tools.db").use { store ->
                    store.saveTool(tool, revision = true)
                    fixture.install(saveKey = false)
                    fixture.launch().use { activity ->
                        robot.click(robot.text(R.string.tool_open))
                        val pause = robot.text(R.string.tool_game_pause)
                        val resume = robot.text(R.string.tool_game_resume)
                        fun state() = JSONObject(checkNotNull(store.tool(tool.id)).state)
                        if (name == "earth") {
                            robot.waitUntil { state().getDouble("phase") > 0 }
                            fun findWebView(view: View): WebView? = when (view) {
                                is WebView -> view
                                is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
                                else -> null
                            }
                            var inlineView: WebView? = null
                            activity.onActivity { inlineView = findWebView(it.window.decorView) }
                            val retainedView = checkNotNull(inlineView)
                            fun height(): Int {
                                var result = 0
                                activity.onActivity {
                                    if (retainedView.isAttachedToWindow) result = retainedView.height
                                }
                                return result
                            }
                            val inlineHeight = height()
                            assertTrue(inlineHeight > 0)
                            val enter = robot.text(R.string.tool_full_screen_enter)
                            val exit = robot.text(R.string.tool_full_screen_exit)
                            val viewport = Rect()
                            activity.onActivity {
                                val position = IntArray(2).also(retainedView::getLocationOnScreen)
                                viewport.set(position[0], position[1], position[0] + retainedView.width, position[1] + retainedView.height)
                            }
                            File(output, "shared-inline-earth.png").outputStream().use { stream ->
                                val bitmap = checkNotNull(robot.automation.takeScreenshot())
                                try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) } finally { bitmap.recycle() }
                            }
                            println("THREE_INLINE_VIEWPORT viewport=$viewport")
                            val expand = robot.assertTarget(enter)
                            assertTrue("The fullscreen action $expand is inside the inline viewport $viewport", viewport.contains(expand))
                            assertNull(robot.find("На паузе"))
                            robot.click(pause)
                            robot.waitFor(resume)
                            robot.waitFor("На паузе")
                            robot.waitUntil { state().getBoolean("paused") }
                            fun waitForSelectedTab(label: String) {
                                robot.reveal(label, forward = null)
                                robot.waitUntil {
                                    var tab = robot.find(label)
                                    while (tab != null && !tab.isSelected && !tab.isClickable) tab = tab.parent
                                    tab?.let { it.isVisibleToUser && it.isSelected } == true
                                }
                            }
                            val closeUp = "Earth close-up"
                            waitForSelectedTab("Orbit view")
                            robot.reveal(closeUp)
                            robot.click(closeUp)
                            robot.waitUntil { state().getString("camera") == "close" }
                            waitForSelectedTab(closeUp)
                            val paused = state().toString()
                            val cameraTransitions = JSONArray()
                            fun assertPausedCamera(location: String) {
                                robot.reveal(resume, forward = null)
                                robot.waitFor("На паузе")
                                waitForSelectedTab(closeUp)
                                val saved = state()
                                assertEquals("Saved camera and phase $location", paused, saved.toString())
                                cameraTransitions.put(JSONObject().put("location", location)
                                    .put("selectedTab", closeUp).put("pausedLabel", "На паузе")
                                    .put("state", saved).put("originalWebViewHeightPx", height()))
                            }
                            fun enterFullscreen(exitPath: String) {
                                robot.reveal(enter, forward = false)
                                robot.click(enter)
                                robot.waitFor(exit)
                                robot.waitUntil { height() > inlineHeight }
                                assertPausedCamera("in fullscreen before $exitPath")
                            }
                            enterFullscreen("explicit exit")
                            robot.reveal(exit, forward = false)
                            robot.click(exit)
                            robot.waitUntil { height() == inlineHeight }
                            assertPausedCamera("after explicit exit")
                            enterFullscreen("Android Back")
                            robot.back()
                            robot.waitUntil { height() == inlineHeight }
                            assertPausedCamera("after Android Back")
                            enterFullscreen("background")
                            activity.moveToState(Lifecycle.State.STARTED)
                            robot.waitUntil { height() == 0 }
                            activity.moveToState(Lifecycle.State.RESUMED)
                            assertPausedCamera("after reopen")
                            File(output, "shared-earth-camera-transitions.json").writeText(
                                JSONObject().put("inlineViewport", viewport.toShortString())
                                    .put("transitions", cameraTransitions).toString(2))
                        } else {
                            robot.waitFor(pause)
                            val bounds = Rect()
                            robot.waitUntil {
                                robot.find("3D scene")?.getBoundsInScreen(bounds)
                                !bounds.isEmpty
                            }
                            robot.waitUntil {
                                val frame = checkNotNull(robot.automation.takeScreenshot())
                                try {
                                    fun pixel(x: Float, y: Float) = frame.getPixel(
                                        bounds.left + (bounds.width() * x).toInt(),
                                        bounds.top + (bounds.height() * y).toInt())
                                    // Sample the pale tile face and the green background inside the viewport.
                                    val background = pixel(.15f, .42f)
                                    val tile = pixel(.25f, .42f)
                                    Color.red(tile) - Color.red(background) > 128 &&
                                        Color.green(tile) - Color.green(background) > 128
                                } finally { frame.recycle() }
                            }
                            val down = SystemClock.uptimeMillis()
                            fun touch(action: Int, fraction: Float) {
                                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                                    bounds.left + fraction * bounds.width(), bounds.exactCenterY(), 0)
                                try {
                                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                                    assertTrue(robot.automation.injectInputEvent(event, true))
                                } finally { event.recycle() }
                            }
                            touch(MotionEvent.ACTION_DOWN, .30f)
                            repeat(8) { step ->
                                touch(MotionEvent.ACTION_MOVE, .30f + .40f * (step + 1) / 8)
                                delay(25)
                            }
                            touch(MotionEvent.ACTION_UP, .70f)
                            robot.waitUntil { state().getBoolean("placed") }
                        }
                    }
                }
            } finally {
                fixture.model?.tools?.closeAndJoin()
                fixture.remove()
            }
        }
    }

    @Test fun recordedEarthRefinementPassesWithoutModelRepairs() =
        replayEarth("earth-recorded-replay") { request, fixture, _ ->
            when (request.generationStage) {
                CloudGenerationStage.PLAN -> fixture("plan.json")
                CloudGenerationStage.REFINE -> fixture("refine.json")
                else -> error("Unexpected provider stage")
            }
        }

    @Test fun recordedEarthAliasUsesTheRealButton() =
        replayEarth("earth-alias-replay", planRejections = 1) { request, fixture, _ ->
            when (request.generationStage) {
                CloudGenerationStage.PLAN -> {
                    val planning = JSONObject(request.turns.first().text)
                    val buttons = planning.getJSONArray("savedButtons")
                    assertEquals(fixture("prompt.txt"), planning.getString("request"))
                    assertEquals(listOf("focusEarth", "focusJupiter", "focusSaturn", "systemView"),
                        List(buttons.length()) { buttons.getJSONObject(it).getString("action") })
                    assertEquals("Focus Earth", buttons.getJSONObject(0).getString("text"))
                    if (request.turns.size == 1) fixture("alias-invalid-plan.json") else fixture("alias-plan.json")
                }
                CloudGenerationStage.REFINE -> fixture("alias-refine.json")
                else -> error("Unexpected provider stage")
            }
        }

    @Test fun earthRewriteReceivesTheOriginalScene() =
        replayEarth("earth-original-rewrite", writerCalls = 4, savedState = """{"status":"Jupiter"}""") { request, fixture, before ->
            when (request.generationStage) {
                CloudGenerationStage.PLAN -> JSONObject(fixture("plan.json")).apply {
                    getJSONObject("scene").getJSONObject("checks").getJSONObject("scenario")
                        .getJSONArray("steps").getJSONObject(2).put("value", "Earth")
                }.toString()
                CloudGenerationStage.REFINE -> {
                    val writerCheck = request.systemInstructions.substringAfter("Host-owned check: ")
                    val steps = JSONObject(writerCheck.substringBefore(".\nBinding entries"))
                        .getJSONObject("checks").getJSONObject("scenario").getJSONArray("steps")
                    assertFalse(steps.getJSONObject(2).has("value"))
                    val original = ToolSpec.parsePersisted(before.spec).providerDefinition()
                    if (request.turns.last().text.contains("original saved definition")) {
                        assertEquals(original, request.turns.single { it.role == "assistant" }.text)
                        assertTrue(request.turns.last().text.contains("control IDs"))
                        JSONObject(original).apply {
                            put("source", getString("source") + "\n// Earth refinement")
                        }.toString()
                    } else """{"unexpected":true}"""
                }
                else -> error("Unexpected provider stage")
            }
        }

    @Test fun longSavedButtonIdsUseAShortPlanAlias() =
        replayEarth("earth-long-button", earthAction = "focusEarthCloseUpWithFillLight") { request, fixture, _ ->
            when (request.generationStage) {
                CloudGenerationStage.PLAN -> {
                    assertTrue(request.systemInstructions.contains("use a short alias"))
                    assertTrue(request.turns.first().text.contains("focusEarthCloseUpWithFillLight"))
                    fixture("alias-plan.json")
                }
                CloudGenerationStage.REFINE -> {
                    assertTrue(request.systemInstructions.contains("full saved action ID"))
                    fixture("alias-refine.json").replace("\"focusEarth\"", "\"focusEarthCloseUpWithFillLight\"")
                }
                else -> error("Unexpected provider stage")
            }
        }

    private fun replayEarth(
        name: String,
        planRejections: Int = 0,
        writerCalls: Int = 1,
        savedState: String? = null,
        earthAction: String = "focusEarth",
        reply: (CloudRequest, (String) -> String, ToolRecord) -> String,
    ) = runBlocking {
        fun fixture(name: String) = instrumentation.context.assets.open("threejs/earth-replay/$name")
            .bufferedReader().use { it.readText() }
        val original = JSONObject(fixture("baseline.json"))
        val definition = JSONObject(original.getJSONObject("spec").toString().replace("focusEarth", earthAction))
        val before = ToolRecord(UUID.randomUUID().toString(), original.getInt("revision"), original.getInt("stateVersion"),
            definition.toString(), savedState ?: original.getJSONObject("state").toString(), definition.getJSONObject("ui").toString(),
            null, true)
        val calls = mutableListOf<CloudGenerationStage>()
        val expected = List(planRejections + 1) { CloudGenerationStage.PLAN } +
            List(writerCalls) { CloudGenerationStage.REFINE }
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                calls += checkNotNull(request.generationStage)
                check(request.publicUpdateContext != null)
                assertEquals(expected.take(calls.size), calls)
                emit(CloudEvent.Text(reply(request, ::fixture, before)))
            }
        }
        val database = "three-recorded-earth-${UUID.randomUUID()}.db"
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                ToolStore(context, database).use { store ->
                    store.saveTool(before, revision = true)
                    val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
                    try {
                        val draft = engine.createDraft(fixture("prompt.txt"), before.id)
                        val events = mutableListOf<ToolGenerationTraceEvent>()
                        val trace = ToolGenerationTrace(UUID.fromString(draft.id), events::add)
                        approvePreview {
                            engine.generate(draft.id, "fixture", emptyList(), CloudSession(),
                                previewObserver = null, trace = trace, onPublicUpdate = {})
                        }
                        val accepted = engine.accept(draft.id)
                        assertEquals(4, accepted.revision)
                        assertEquals(before.state, accepted.state)
                        assertNotEquals(definition.getString("source"), JSONObject(accepted.spec).getString("source"))
                        assertEquals(expected, calls)
                        assertEquals(planRejections + writerCalls - 1, events.filterIsInstance<ToolGenerationTraceEvent.Attempt>()
                            .count { it.outcome == ToolGenerationOutcome.REJECTED })
                        assertEquals(ToolGenerationTraceOutcome.VALIDATED,
                            events.filterIsInstance<ToolGenerationTraceEvent.Finished>().single().outcome)
                        File(output, "$name-trace.txt").writeText(events.joinToString("\n") + "\n")
                    } finally { engine.closeArtifacts() }
                }
            }
        } finally { context.deleteDatabase(database) }
    }

    @Test fun earthLightingRejectsUnsupportedCheckAndRepairsOnlyItsBinding() = runBlocking {
        val original = generate("earth")
        val database = "three-check-policy-${UUID.randomUUID()}.db"
        val prompt = """Refine this existing Solar System Observatory. Keep all UI controls, orbits,
                status schema, and camera focus options. Improve the Earth close-up only. The current Earth
                is very dark with the bright Sun in the shot. Add soft cool fill lighting from the camera side
                during Earth focus so blue oceans and green continents are clearly visible. Use a flattering
                close-up angle and keep the Sun outside the Earth close-up. Keep the background starfield.
                Use only surface and geometry slots for the automated orbit motion check. Preserve the
                supplied state at startup and make a focused source patch."""
        val badPlan = """{"wholeScreen":true,"runtime":"threejs-worker","language":"en","scene":{"placement":"stage","brief":"Solar System Observatory with the existing UI controls, orbit paths, status readouts, camera focus options, and starfield background preserved. Orbits keep animating around the Sun. On Earth focus, the camera uses a flattering three-quarter close-up angle with the Sun kept outside the frame; a soft cool blue-white fill light positioned on the camera side brightens the Earth so deep blue oceans and green continents read clearly, with a modest emissive tint to avoid a dark limb, while the starfield stays visible behind. Startup state is unchanged and the fill light only affects the Earth focus view.","checks":{"status":"checkable","scenario":{"slots":{"view":{"kind":"surface"},"orbits":{"kind":"geometry"},"earth":{"kind":"geometry"}},"steps":[{"op":"capture","slot":"earth","as":"before"},{"op":"frames","slot":"view","count":8,"observe":"orbits","changes":3},{"op":"assert","slot":"earth","test":"unchanged","capture":"before"}]}}}}"""
        val corrected = JSONObject(badPlan)
        val scene = corrected.getJSONObject("scene")
        val scenario = scene.getJSONObject("checks").getJSONObject("scenario")
        scenario.getJSONObject("slots").remove("earth")
        val frames = scenario.getJSONArray("steps").getJSONObject(1)
        scenario.put("steps", JSONArray().put(frames))
        scene.put("checkEvidence", JSONArray().put(JSONObject()
            .put("step", 1).put("excerpt", "automated orbit motion check")
            .put("purpose", "motion").put("scope", "whole")))
        scene.put("visualReview", JSONArray().put("Improve the Earth close-up only."))
        val requests = mutableListOf<CloudGenerationStage>()
        val provider = object : ChatProvider by original.provider {
            override val supportsSceneVision = false
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                requests += checkNotNull(request.generationStage)
                val reply = when (requests.size) {
                    1 -> badPlan
                    2 -> {
                        assertEquals(CloudGenerationStage.PLAN, request.generationStage)
                        assertTrue(request.systemInstructions.contains("checkEvidence"))
                        corrected.toString()
                    }
                    3 -> {
                        assertEquals(CloudGenerationStage.REFINE, request.generationStage)
                        JSONObject(sourcePatch("0x3180f4", "0x40a0f8")).put("fields", JSONObject()
                            .put("behaviorBindings", JSONObject("""{"orbits":{"surface":"scene","path":[1]}}"""))).toString()
                    }
                    4 -> {
                        assertEquals(CloudGenerationStage.REFINE, request.generationStage)
                        assertTrue(request.turns.last().text.contains("only behaviorBindings"))
                        """{"fields":{"behaviorBindings":{"orbits":{"surface":"scene","path":[0]}}}}"""
                    }
                    else -> error("Unexpected provider call")
                }
                emit(CloudEvent.Text(reply))
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                ToolStore(context, database).use { store ->
                    store.saveTool(original.record, revision = true)
                    val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
                    try {
                        val draft = engine.createDraft(prompt, original.record.id)
                        approvePreview { engine.generate(draft.id, "fixture", emptyList(), CloudSession()) }
                        val accepted = engine.accept(draft.id)
                        assertEquals(original.record.state, accepted.state)
                        val oldSource = ToolSpec.parsePersisted(original.record.spec).source
                        assertEquals(oldSource.replace("0x3180f4", "0x40a0f8"), ToolSpec.parsePersisted(accepted.spec).source)
                        assertEquals(4, requests.size)
                        File(output, "earth-lighting-check-replay.txt").writeText(requests.joinToString())
                    } finally { engine.closeArtifacts() }
                }
            }
        } finally { context.deleteDatabase(database) }
    }

    @Test fun sceneChoiceMustRemainVisibleAfterSelection() = runBlocking {
        val scenario = GenerationBehavior.parse(JSONObject("""{"status":"checkable","scenario":{
            "slots":{"choose":{"kind":"action"},"choice":{"kind":"value"}},
            "steps":[{"op":"capture","slot":"choice","as":"before"},
                     {"op":"action","slot":"choose","value":"close"},
                     {"op":"assert","slot":"choice","test":"changed","capture":"before"},
                     {"op":"assert","slot":"choice","test":"equals","value":"close"}]}}"""),
            emptySet(), threeScene = true) as GenerationBehavior.Scenario
        val bindings = GenerationBehavior.bindings(JSONObject("""{"choose":"camera","choice":[0]}"""), scenario.slots)
        val source = fixtureSource("earth").replace("if (event.type === 'viewport')", """
            if (event.type === 'action' && event.actionId === 'camera') {
                state.camera = event.value;
                if (state.hideAfterSelection) state.showCamera = false;
            }
            if (event.type === 'viewport')
        """.trimIndent())
        val definition = JSONObject(Provider("earth", source, false) {}.candidate()).apply {
            remove("behaviorBindings")
            getJSONObject("stateSchema").put("camera", "string").put("showCamera", "boolean").put("hideAfterSelection", "boolean")
            getJSONObject("initialState").put("camera", "orbit").put("showCamera", true)
            put("ui", JSONObject("""{"type":"transition","preset":"fade","bind":"showCamera","children":[
                {"type":"segmented","text":"Camera","bind":"camera","action":"camera",
                "options":[{"text":"Orbit","value":"orbit"},{"text":"Close-up","value":"close"}]}]}"""))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            suspend fun check(hideAfterSelection: Boolean) {
                definition.getJSONObject("initialState").put("hideAfterSelection", hideAfterSelection)
                val spec = ToolSpec.parse(definition.toString())
                approvePreview { checkThreeScene(spec, "visible-scene-choice", 1, spec.initialState, scenario, bindings, {}, null) }
            }
            check(hideAfterSelection = false)
            val failure = runCatching { check(hideAfterSelection = true) }.exceptionOrNull()
            assertTrue("A choice hidden after selection must fail at the observation, observed $failure", failure is ThreeSceneFailure)
            assertEquals(ThreeSceneProblem.INVALID_BINDING, (failure as ThreeSceneFailure).problem)
            assertEquals(3, failure.step)
        }
    }

    @Test fun periodicReturnAndPauseKeepTheirSeparateExpectations() = runBlocking {
        val prompt = "Create a Three.js orbit that returns to its starting image after eight frames. Pause holds the image still."
        val checks = """{"status":"checkable","scenario":{
            "slots":{"view":{"kind":"surface"},"render":{"kind":"geometry"}},
            "steps":[{"op":"capture","slot":"render","as":"start"},
                     {"op":"frames","slot":"view","count":8,"observe":"render","changes":3},
                     {"op":"assert","slot":"render","test":"unchanged","capture":"start"},
                     {"op":"surface","slot":"view","event":"pause"},
                     {"op":"capture","slot":"render","as":"paused"},
                     {"op":"frames","slot":"view","count":4},
                     {"op":"assert","slot":"render","test":"unchanged","capture":"paused"}]}}"""
        val plan = """{"wholeScreen":true,"runtime":"threejs-worker","language":"en","scene":{
            "placement":"stage","brief":"Render a short orbit with pause.","checks":$checks,
            "checkEvidence":[{"step":2,"excerpt":"Three.js orbit","purpose":"motion","scope":"whole"},
                {"step":3,"excerpt":"returns to its starting image after eight frames","purpose":"periodic_return","scope":"whole"},
                {"step":7,"excerpt":"Pause holds the image still","purpose":"stability","scope":"whole"}]}}"""
        val source = fixtureSource("earth").replace("Math.PI / 4000", "Math.PI / 200")
        val correct = generate("earth", fixturePrompt = prompt, fixturePlan = plan, sourceOverride = source)
        assertEquals(0, correct.repairs)
        for ((expectedStep, broken) in listOf(
            3 to source.replace("Math.PI / 200", "Math.PI / 400"),
            7 to source.replace("enabled && !state.paused", "enabled"),
        )) {
            val failure = runCatching {
                generate("earth", fixturePrompt = prompt, fixturePlan = plan, sourceOverride = broken,
                    session = CloudSession().apply { repeat(3) { claimGenerationRepair(CloudGenerationStage.COMPLETE) } })
            }.exceptionOrNull() as? CloudFailure
            assertEquals("three_behavior", failure?.code)
            assertTrue(failure?.message.orEmpty(), failure?.message.orEmpty().contains("step $expectedStep"))
        }
    }

    @Test fun capturedChangeRequiresTheSameGeometryTarget() = runBlocking {
        val prompt = "Create a Three.js view where refreshing the viewport changes the selected region."
        val plan = """{"wholeScreen":true,"runtime":"threejs-worker","language":"en","scene":{
            "placement":"stage","brief":"Refresh the selected region.","outcome":{"kind":"continuous"},
            "checks":{"status":"checkable","scenario":{
            "slots":{"view":{"kind":"surface"},"before":{"kind":"geometry"},"after":{"kind":"geometry"}},
            "steps":[{"op":"capture","slot":"before","as":"start"},
                     {"op":"surface","slot":"view","event":"viewport"},
                     {"op":"assert","slot":"after","test":"changed","capture":"start"}]}},
            "checkEvidence":[{"step":3,"excerpt":"refreshing the viewport changes the selected region",
                "purpose":"outcome","scope":"selected"}]}}"""
        val fixture = Provider("earth", fixtureSource("earth"), false) {}
        fun bindings(region: Int) = JSONObject("""{"before":{"surface":"scene","path":[1]},
            "after":{"surface":"scene","path":[$region]}}""")
        for ((region, expected) in listOf(3 to "same geometry target", 1 to "Observed unchanged")) {
            var writerCalls = 0
            val provider = object : ChatProvider {
                override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                    val output = if (request.generationStage == CloudGenerationStage.PLAN) plan else when (++writerCalls) {
                        1 -> JSONObject(fixture.candidate()).put("behaviorBindings", bindings(2)).toString()
                        2 -> {
                            assertTrue(request.turns.last().text.contains("only behaviorBindings"))
                            JSONObject().put("fields", JSONObject().put("behaviorBindings", bindings(region))).toString()
                        }
                        else -> error("Unexpected writer call")
                    }
                    emit(CloudEvent.Text(output))
                }
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                val session = CloudSession()
                if (region == 1) repeat(2) { session.claimGenerationRepair(CloudGenerationStage.COMPLETE) }
                val failure = runCatching {
                    ToolGenerationService(provider, SandboxClient(context))
                        .generate("fixture", prompt, null, emptyList(), session, UUID.randomUUID().toString())
                }.exceptionOrNull() as? CloudFailure
                assertEquals("three_behavior", failure?.code)
                assertTrue(failure?.message.orEmpty(), failure?.message.orEmpty().contains(expected))
                assertEquals(2, writerCalls)
            }
        }
    }

    @Test fun aFailedRefinementKeepsTheAcceptedRevision() = runBlocking {
        val result = generate("earth")
        val name = "three-refinement-${UUID.randomUUID()}.db"
        var patchRequests = 0
        val provider = object : ChatProvider by result.provider {
            override suspend fun respond(
                key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit,
            ) {
                if (request.generationStage != CloudGenerationStage.REFINE) {
                    result.provider.respond(key, request, session, emit)
                } else {
                    val patch = if (++patchRequests == 1) {
                        """{"sourceEdits":[{"search":"const enabled = true;","replace":"const enabled = false;"}]}"""
                    } else """{"fields":{"title":"Earth and Moon"}}"""
                    emit(CloudEvent.Text(patch))
                }
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                ToolStore(context, name).use { store ->
                    store.saveTool(result.record, revision = true)
                    val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
                    try {
                        val draft = engine.createDraft("Change the Earth color and keep the Moon moving.", result.record.id)
                        val failure = runCatching {
                            engine.generate(draft.id, "fixture", emptyList(), CloudSession())
                        }.exceptionOrNull() as? CloudFailure
                        assertEquals("three_behavior", failure?.code)
                        assertEquals(2, patchRequests)
                        assertEquals(result.record, store.tool(result.record.id))
                    } finally { engine.closeArtifacts() }
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun repairDoesNotSendLocalAssetData() = runBlocking {
        val marker = "SYNTHETIC_ASSET_PRIVACY_MARKER"
        val file = File(context.filesDir, "chat-camera/private-${UUID.randomUUID()}.glb")
        file.parentFile?.mkdirs()
        val database = "three-privacy-${UUID.randomUUID()}.db"
        val lease = ThreeSceneAssetCleanup(context).lease()
        try {
            file.writeBytes(catalogGlb { it.getJSONObject("asset").put("extras", JSONObject().put("fixture", marker)) })
            val uri = FileProvider.getUriForFile(context, context.packageName + ".chat-camera", file)
            val assets = ThreeSceneAssets(context)
            val attachment = assets.import(uri, "Private.glb", "model/gltf-binary")
            val refs = ThreeSceneAssets.attachmentRefs(listOf(attachment))
            val original = fixtureSource("earth")
            val injected = listOf(
                "throw Error(new TextDecoder().decode(await readAsset('${refs.single().id}')).match(/SYNTHETIC_ASSET_[A-Z_]+/)[0]);",
                "await Promise.reject(Error(new TextDecoder().decode(await readAsset('${refs.single().id}')).match(/SYNTHETIC_ASSET_[A-Z_]+/)[0]));",
                "state.phase = new TextDecoder().decode(await readAsset('${refs.single().id}')).match(/SYNTHETIC_ASSET_[A-Z_]+/)[0];",
            )
            val source = original.replace("state, viewport}", "state, viewport, readAsset}")
                .replace("const enabled = true;", "const enabled = true; ${injected.first()}")
            val fixture = Provider("earth", source, false) {}
            var repairs = 0
            val provider = object : ChatProvider by fixture {
                override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                    assertFalse(request.systemInstructions.contains(marker))
                    assertTrue(request.turns.none { it.text.contains(marker) || it.images.isNotEmpty() })
                    if (request.generationStage == CloudGenerationStage.PLAN) {
                        val payload = JSONObject(request.turns.single().text)
                        assertEquals(refs.single().id, payload.getJSONArray("local3dAssets").getJSONObject(0).getString("id"))
                        assertTrue(request.systemInstructions.contains("require a Three.js plan"))
                    }
                    if (request.turns.last().text.contains("Return one JSON patch")) {
                        val previous = injected[repairs++]
                        emit(CloudEvent.Text(sourcePatch(previous, injected.getOrElse(repairs) { "" })))
                    } else fixture.respond(key, request, session, emit)
                }
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                ToolStore(context, database).use { store ->
                    val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
                    try {
                        val draft = engine.createDraft("Create a 3D view and read the selected asset.", null)
                        val events = mutableListOf<ToolGenerationTraceEvent>()
                        val trace = ToolGenerationTrace(UUID.fromString(draft.id), onEvent = { events += it })
                        approvePreview {
                            engine.generate(draft.id, "fixture", emptyList(), CloudSession(), previewObserver = null,
                                trace = trace, sceneAssets = refs)
                        }
                        assertEquals(3, repairs)
                        val failures = events.filterIsInstance<ToolGenerationTraceEvent.Attempt>()
                            .filter { it.stage == ToolGenerationStage.BEHAVIOR }.map { it.failureCode }
                        assertEquals(listOf("three_init_failed", "three_init_failed", "three_invalid_snapshot"), failures)
                        val exported = events.joinToString("\n")
                        assertFalse(exported.contains(marker))
                        File(output, "privacy-repair-trace.txt").writeText(exported)
                        assertEquals(refs, ToolSpec.parsePersisted(engine.accept(draft.id).spec).sceneAssets)
                    } finally { engine.closeArtifacts() }
                }
            }
        } finally {
            file.delete()
            context.deleteDatabase(database)
            lease.close()
        }
    }

    @Test fun refinementRepairsStartupAndPreservesCompletedProgressThroughRestore() = runBlocking {
        val result = generate("workshop")
        val marker = "SYNTHETIC_CHECKPOINT_PRIVACY_MARKER"
        val completed = result.record.copy(state = JSONObject(result.record.state)
            .put("placed", true).put("status", marker).toString())
        val database = "three-checkpoint-${UUID.randomUUID()}.db"
        var patches = 0
        val provider = object : ChatProvider by result.provider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                assertFalse(request.systemInstructions.contains(marker))
                assertTrue(request.turns.none { it.text.contains(marker) })
                if (request.generationStage == CloudGenerationStage.PLAN) {
                    assertEquals("threejs-worker", JSONObject(request.turns.single().text).getString("savedRuntime"))
                    assertTrue(request.systemInstructions.contains("require a Three.js plan"))
                }
                if (request.generationStage != CloudGenerationStage.REFINE) {
                    result.provider.respond(key, request, session, emit)
                    return
                }
                val color = "scene.background = new THREE.Color(0x16322f);"
                val throwing = "if (state.placed) throw Error(state.status);"
                val reset = "if (state.placed) state.status = 'reset';"
                val patch = when (++patches) {
                    1 -> sourcePatch(color, "$color $throwing").let(::JSONObject).put("fields", JSONObject()
                        .put("behaviorBindings", JSONObject("""{"message":[0],"saved":"placed"}"""))).toString()
                    2 -> sourcePatch(throwing, reset)
                    else -> sourcePatch(reset, "").let(::JSONObject).put("fields", JSONObject()
                        .put("stateSchema", JSONObject("""{"placed":"boolean","status":"string","stars":"number"}"""))
                        .put("initialState", JSONObject("""{"placed":false,"status":"Перетащи М","stars":3}"""))).toString()
                }
                emit(CloudEvent.Text(patch))
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                ToolStore(context, database).use { store ->
                    store.saveTool(completed, revision = true)
                    val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
                    try {
                        val draft = engine.createDraft("Change the appearance of the workshop.", completed.id)
                        approvePreview { engine.generate(draft.id, "fixture", emptyList(), CloudSession()) }
                        assertEquals(3, patches)
                        val saved = engine.accept(draft.id)
                        assertEquals(marker, JSONObject(saved.state).getString("status"))
                        assertEquals(3, JSONObject(saved.state).getInt("stars"))
                        assertTrue(engine.restoreAvailability(saved, completed.revision).reason == null)
                        val restored = engine.rollback(saved.id, saved.revision, saved.stateVersion, completed.revision)
                        assertEquals(saved.state, restored.state)
                        assertEquals(result.record.revision + 2, restored.revision)
                        onScene(restored) { scene, _ -> assertEquals(restored.state, scene.snapshot.state) }
                    } finally { engine.closeArtifacts() }
                }
            }
        } finally { context.deleteDatabase(database) }
    }

    @Test fun rejectsOverdeepGlbInEitherNodeOrder() = runBlocking {
        val file = File(context.filesDir, "chat-camera/depth-${UUID.randomUUID()}.glb")
        file.parentFile?.mkdirs()
        try {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".chat-camera", file)
            for (levels in listOf(32, 33, 64)) for (childFirst in listOf(false, true)) {
                file.writeBytes(catalogGlb { glb ->
                    val nodes = JSONArray()
                    for (i in 0 until levels) {
                        val child = if (childFirst) i - 1 else i + 1
                        nodes.put(JSONObject().apply {
                            if (child in 0 until levels) put("children", JSONArray().put(child))
                        })
                    }
                    glb.put("nodes", nodes).put("animations", JSONArray())
                    glb.getJSONArray("scenes").getJSONObject(0).put("nodes", JSONArray().put(if (childFirst) levels - 1 else 0))
                })
                val result = runCatching { ThreeSceneAssets(context).import(uri, "Depth.glb", "model/gltf-binary") }
                assertEquals("$levels levels, child-first=$childFirst", levels <= 32, result.isSuccess)
            }
        } finally { file.delete() }
    }

    @Test fun idleCleanupKeepsSharedAndPreservedAssets() = runBlocking {
        val directory = File(context.cacheDir, "three-collect-${UUID.randomUUID()}").apply { mkdirs() }
        val chatDb = "three-cleanup-chat-${UUID.randomUUID()}"
        val library = "three-cleanup-${UUID.randomUUID()}.db"
        val namedLibrary = "three-cleanup-library-${UUID.randomUUID()}"
        val preserved = "${ToolLibraryStorage.DEFAULT}.library-${UUID.randomUUID()}.db"
        val active = "${ToolLibraryStorage.DEFAULT}.library-${UUID.randomUUID()}.db"
        val pauseScan = AtomicBoolean(false)
        val verifyMissing = AtomicBoolean(false)
        val missingScans = LinkedBlockingQueue<Unit>()
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val privateContext = object : ContextWrapper(context) {
            override fun getFilesDir() = directory
            override fun getNoBackupFilesDir() = directory
            override fun getDatabasePath(name: String): File {
                if (name == preserved && pauseScan.compareAndSet(true, false)) {
                    paused.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                }
                if (name == preserved && verifyMissing.get()) missingScans.add(Unit)
                return if (name in setOf(preserved, active, ToolLibraryStorage.DEFAULT)) File(directory, name)
                    else super.getDatabasePath(name)
            }
            override fun databaseList() = listOf(chatDb, preserved, active, library, namedLibrary)
                .filter { name ->
                    val file = if (name in setOf(preserved, active)) File(directory, name)
                        else super.getDatabasePath(name)
                    file.exists()
                }.toTypedArray()
        }
        val files = File(directory, "threejs-assets").apply { mkdirs() }
        val cleanup = ThreeSceneAssetCleanup(privateContext)
        val shared = "1".repeat(64)
        val old = "2".repeat(64)
        val candidate = "3".repeat(64)
        val unused = "4".repeat(64)
        val publishedDuringScan = "5".repeat(64)
        val namedAsset = "6".repeat(64)
        val part = "import-orphan.part"
        val unrelated = "keep.txt"
        fun ref(id: String) = JSONObject().put("id", id).put("name", "Fixture").put("mime", "model/gltf-binary").put("size", 1)
        val base = ToolSpec.parse(JSONObject()
            .put("formatVersion", 3).put("runtime", JSONObject("""{"kind":"threejs-worker","version":1}"""))
            .put("title", "Fixture").put("description", "").put("capabilities", JSONArray())
            .put("stateSchema", JSONObject("""{"phase":"number","paused":"boolean"}"""))
            .put("initialState", JSONObject("""{"phase":0,"paused":false}"""))
            .put("ui", JSONObject("""{"type":"text","text":"Fixture"}"""))
            .put("source", fixtureSource("earth")).toString())
        fun spec(id: String) = base.withThreeAssets(ThreeSceneAssets.read(JSONArray().put(ref(id)))).json
        fun present() = files.list()?.toSet().orEmpty()
        fun attachment(id: String) = com.alpha.assistant.data.Attachment("alphy-three-asset:$id", "Fixture",
            ThreeSceneAssets.ATTACHMENT_MIME, ref(id).toString())
        val now = System.currentTimeMillis()
        try {
            ShellStore(privateContext, chatDb).use { chat ->
                ToolStore(privateContext, library).use { tools ->
                    val lease = cleanup.lease()
                    try {
                        for (name in listOf(shared, old, candidate, unused, namedAsset, part, unrelated)) File(files, name).writeText("x")
                        val conversation = Conversation("chat", "Fixture", now, now,
                            refinementDraft = Draft(attachments = listOf(attachment(shared))))
                        chat.saveConversation(conversation)
                        chat.saveNewDraft(Draft(attachments = listOf(attachment(shared))))
                        val record = ToolRecord("scene", 1, 1, spec(old), base.initialState, base.ui.toString(), null, true, false, now)
                        tools.saveTool(record, true)
                        ToolStore(privateContext, namedLibrary).use {
                            it.saveTool(record.copy(spec = spec(namedAsset)), true)
                        }
                        tools.saveTool(record.copy(revision = 2, spec = spec(shared)), true)
                        tools.saveDraft(ToolDraft("preview", null, 0, 0, "Fixture", spec(candidate), "{}", "{}", null))
                        tools.close()
                        check(context.getDatabasePath(library).renameTo(privateContext.getDatabasePath(preserved)))
                        cleanup.request()
                        assertEquals(setOf(shared, old, candidate, unused, namedAsset, part, unrelated), present())
                    } finally { lease.close() }
                    withTimeout(5_000) { while (unused in present() || part in present()) delay(25) }
                    assertEquals(setOf(shared, old, candidate, namedAsset, unrelated), present())
                    File(files, publishedDuringScan).writeText("x")
                    File(files, unused).writeText("x")
                    pauseScan.set(true)
                    cleanup.request()
                    try {
                        assertTrue(withContext(Dispatchers.IO) { paused.await(5, TimeUnit.SECONDS) })
                        cleanup.lease().use {
                            chat.saveNewDraft(Draft(attachments = listOf(attachment(publishedDuringScan))))
                        }
                    } finally {
                        resume.countDown()
                    }
                    withTimeout(5_000) { while (unused in present()) delay(25) }
                    assertTrue(publishedDuringScan in present())

                    val preservedFile = privateContext.getDatabasePath(preserved)
                    val activeFile = privateContext.getDatabasePath(active)
                    preservedFile.copyTo(activeFile)
                    val marker = ToolLibraryStorage.selectionFile(privateContext, ToolLibraryStorage.DEFAULT)
                    marker.writeText(JSONObject().put("active", active).put("preserved", preserved).toString())
                    assertTrue(preservedFile.delete())
                    verifyMissing.set(true)
                    chat.clearHistory()
                    chat.saveNewDraft(Draft())
                    repeat(2) {
                        cleanup.request()
                        assertNotNull(withContext(Dispatchers.IO) { missingScans.poll(5, TimeUnit.SECONDS) })
                    }
                    verifyMissing.set(false)
                    assertTrue(publishedDuringScan in present())
                    assertTrue(shared in present())
                    assertTrue(marker.delete())
                    assertTrue(activeFile.delete())
                    assertTrue(context.deleteDatabase(namedLibrary))
                    cleanup.request()
                    withTimeout(5_000) { while (present() != setOf(unrelated)) delay(25) }
                }
            }
        } finally {
            context.deleteDatabase(chatDb)
            context.deleteDatabase(library)
            context.deleteDatabase(namedLibrary)
            directory.deleteRecursively()
        }
    }

    @Test fun webViewSerializesLargeReadsWithinTheTransferBudget() = runBlocking {
        val bytes = 33 * 1024 * 1024
        val opens = AtomicInteger()
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val asset = ThreeSceneAsset("budget-fixture", "application/octet-stream", bytes.toLong(), "Budget fixture") {
            opens.incrementAndGet()
            val active = inFlight.incrementAndGet()
            peak.updateAndGet { maxOf(it, active) }
            object : InputStream() {
                private var remaining = bytes
                private val done = AtomicBoolean(false)
                override fun available() = remaining
                override fun read(): Int {
                    if (remaining == 0) { close(); return -1 }
                    remaining--
                    return 0
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    if (remaining == 0) { close(); return -1 }
                    val count = minOf(remaining, length)
                    buffer.fill(0, offset, offset + count)
                    remaining -= count
                    return count
                }
                override fun close() {
                    if (done.compareAndSet(false, true)) inFlight.decrementAndGet()
                }
            }
        }
        val source = """
            export async function createScene({THREE, canvas, viewport, readAsset}) {
                const renderer = new THREE.WebGLRenderer({canvas});
                renderer.setSize(viewport.width, viewport.height, false);
                renderer.clear();
                const size = async () => (await readAsset('budget-fixture')).byteLength;
                const reads = await Promise.all([size(), size()]);
                return {
                    event() {}, frame() {}, snapshot() { return {state: {reads}}; },
                    dispose() { renderer.dispose(); }
                };
            }
        """.trimIndent()
        ActivityScenario.launch(MainActivity::class.java).use {
            withContext(Dispatchers.Main.immediate) {
                val activity = ThreeSceneForeground.activity()
                val web = ThreeSceneWebView(activity, "asset-budget", 1, source, listOf(asset))
                val viewport = android.widget.FrameLayout(activity).apply {
                    addView(web.view, android.widget.FrameLayout.LayoutParams(-1, 440))
                }
                val dialog = AlertDialog.Builder(activity).setView(viewport).create()
                try {
                    dialog.show()
                    withTimeout(2_500) { while (web.view.width == 0 || web.view.height == 0) delay(16) }
                    val result = try {
                        web.start(JSONObject()).getJSONObject("state")
                    } catch (failure: Exception) {
                        throw AssertionError("Broker opened ${opens.get()} reads with ${inFlight.get()} still active.", failure)
                    }
                    val reads = result.getJSONArray("reads")
                    assertEquals(2, reads.length())
                    assertEquals(bytes, reads.getInt(0))
                    assertEquals(bytes, reads.getInt(1))
                    assertEquals(2, opens.get())
                    assertEquals(1, peak.get())
                } finally {
                    dialog.dismiss()
                    web.close()
                }
            }
        }
    }

    @Test fun threeScenePlanRejectsUnsupportedControlsBeforeWriter() {
        val fixture = Provider("earth", fixtureSource("earth"), false) {}
        val plan = JSONObject(fixture.plan())
        val steps = plan.getJSONObject("scene").getJSONObject("checks").getJSONObject("scenario").getJSONArray("steps")
        val motion = steps.getJSONObject(0)
        steps.put(motion)
        for (event in listOf("""{"op":"surface","slot":"view","event":"control","direction":"left"}""",
            """{"op":"surface","slot":"view","event":"start"}""")) {
            steps.put(0, JSONObject(event))
            assertTrue(runCatching {
                com.alpha.assistant.tools.generation.NeedsPlan.parseResponse(plan.toString(), null, emptySet())
            }.isFailure)
        }
    }

    private fun sourcePatch(search: String, replacement: String) = JSONObject()
        .put("sourceEdits", JSONArray().put(JSONObject().put("search", search).put("replace", replacement))).toString()

    private fun fixtureSource(name: String) =
        instrumentation.context.assets.open("threejs/$name.js").bufferedReader().use { it.readText() }

    private fun catalogGlb(edit: (JSONObject) -> Unit): ByteArray {
        val original = context.assets.open("threejs/catalog/robot.glb").use { it.readBytes() }
        val oldLength = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN).getInt(12)
        val model = JSONObject(original.copyOfRange(20, 20 + oldLength).toString(Charsets.UTF_8)).also(edit)
        val json = model.toString().toByteArray()
        val length = (json.size + 3) / 4 * 4
        return ByteBuffer.allocate(original.size - oldLength + length).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546c67).putInt(2).putInt(capacity()).putInt(length).putInt(0x4e4f534a)
            put(json)
            repeat(length - json.size) { put(' '.code.toByte()) }
            put(original, 20 + oldLength, original.size - 20 - oldLength)
        }.array()
    }

    private suspend fun <T> approvePreview(block: suspend () -> T): T = respondToPreview("Save scene", block = block)

    private suspend fun <T> respondToPreview(label: String, requiredMessage: String? = null, block: suspend () -> T): T =
        withTimeout(if (requiredMessage == null) 600_000 else 60_000) {
            val approval = launch(Dispatchers.IO) {
                while (true) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    val ready = requiredMessage == null || root?.findAccessibilityNodeInfosByText(requiredMessage).orEmpty().isNotEmpty()
                    if (ready && root?.findAccessibilityNodeInfosByText(label).orEmpty().any {
                            it.isClickable && it.isEnabled &&
                                it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                        }) break
                    delay(100)
                }
            }
            try { block() } finally { approval.cancelAndJoin() }
        }

    @Test fun cancelingThePreviewStopsAnInFlightVisualReview() = runBlocking {
        val reviewing = CompletableDeferred<Unit>()
        val generation = async(Dispatchers.IO) {
            runCatching { generate("shader", onVisual = { reviewing.complete(Unit); awaitCancellation() }) }
        }
        try {
            withTimeout(30_000) { reviewing.await() }
            ShellRobot("three-visual-cancel").waitUntil {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                root?.findAccessibilityNodeInfosByText("Cancel").orEmpty().any {
                    it.isClickable && it.isEnabled &&
                        it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            val failure = withTimeout(5_000) { generation.await().exceptionOrNull() } as? CloudFailure
            assertEquals("three_declined", failure?.code)
        } finally {
            generation.cancel()
        }
    }

    @Test fun cancelingDuringLocalStartupDoesNotAskForRepair() = runBlocking {
        val original = fixtureSource("earth")
        val source = original.replace("const enabled = true;", "const enabled = true; await new Promise(() => {});")
        val fixture = Provider("earth", source, false) {}
        val provider = object : ChatProvider by fixture {
            override val supportsSceneVision = false
        }
        val database = "three-startup-cancel-${UUID.randomUUID()}.db"
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                ToolStore(context, database).use { store ->
                    val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
                    try {
                        val draft = engine.createDraft("Create a Three.js orbit.", null)
                        val generation = async(Dispatchers.IO) {
                            runCatching { engine.generate(draft.id, "fixture", emptyList(), CloudSession()) }
                        }
                        ShellRobot("three-startup-cancel").waitUntil {
                            val root = instrumentation.uiAutomation.rootInActiveWindow
                            root?.findAccessibilityNodeInfosByText("Cancel").orEmpty().any {
                                it.isClickable && it.isEnabled &&
                                    it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                            }
                        }
                        val failure = withTimeout(5_000) { generation.await().exceptionOrNull() } as? CloudFailure
                        assertEquals("three_declined", failure?.code)
                        assertEquals(0, fixture.repairs)
                        assertNull(store.draft(draft.id)?.candidate)
                    } finally { engine.closeArtifacts() }
                }
            }
        } finally { context.deleteDatabase(database) }
    }

    @Test fun threeWriterReceivesTheOriginalRequestAndContextSeparately() = runBlocking {
        val original = "Create a 3D shape with a moving shader. Exclude scoreboards."
        val brief = "$original\nSuggested implementation: add a scoreboard."
        val reference = "data:image/png;base64,three-reference"
        val fixture = Provider("shader", fixtureSource("shader"), false) {}
        var writerCalls = 0
        val provider = object : ChatProvider by fixture {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                if (request.generationStage == CloudGenerationStage.COMPLETE) {
                    writerCalls++
                    assertTrue(request.systemInstructions.contains(com.alpha.assistant.tools.generation.GenerationBehavior.workflowScopeGuidance))
                    val user = request.turns.first()
                    val payload = JSONObject(user.text)
                    assertEquals(original, payload.getString("request"))
                    assertEquals(brief, payload.getString("context"))
                    assertEquals(listOf(reference), user.images)
                }
                fixture.respond(key, request, session, emit)
            }
        }
        val database = "three-context-${UUID.randomUUID()}.db"
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                ToolStore(context, database).use { store ->
                    val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
                    try {
                        val draft = engine.createDraft(brief, null)
                        approvePreview {
                            engine.generate(draft.id, "fixture", listOf(reference), CloudSession(),
                                previewObserver = null, trace = null, originalRequest = original)
                        }
                        val saved = engine.accept(draft.id)
                        assertEquals(1, writerCalls)
                        assertTrue(ToolSpec.parsePersisted(saved.spec).threeScene)
                        onScene(saved) { scene, _ -> assertEquals(saved.state, scene.snapshot.state) }
                    } finally { engine.closeArtifacts() }
                }
            }
        } finally { context.deleteDatabase(database) }
    }

    @Test fun generatedShaderDrawsAThirdScene() = runBlocking {
        val result = generate("shader")
        assertTrue(ToolSpec.parsePersisted(result.record.spec).source.contains("new THREE.ShaderMaterial"))
        ActivityScenario.launch(MainActivity::class.java).use {
            onScene(result.record) { scene, dialog ->
                repeat(8) { scene.frame(33) }
                saveFrame("shader", scene, dialog)
            }
        }
    }

    @Test fun recordFixture() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("threeRecord")
        org.junit.Assume.assumeTrue(name in setOf("earth", "workshop"))
        val liveRun = args.getString("threeRecordRun")
        val from = args.getString("threeRecordFrom")?.toDouble() ?: .30
        val to = args.getString("threeRecordTo")?.toDouble() ?: .70
        val record = if (liveRun == null) generate(checkNotNull(name)).record else {
            require(liveRun.matches(Regex("[a-f0-9]{32}")))
            val id = File(context.getExternalFilesDir(null), "three-live/$liveRun/scene-id.txt").readText()
            ToolStore(context, "three-live-$liveRun.db").use { store -> checkNotNull(store.tool(id)) }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            onScene(record) { scene, dialog ->
                dialog.window!!.setLayout(920, 620)
                delay(120)
                scene.viewport()
                File(output, "record-$name-ready.txt").writeText("ready")
                if (name == "earth") {
                    repeat(245) {
                        scene.frame(33)
                        delay(22)
                    }
                } else {
                    repeat(30) { scene.frame(33); delay(26) }
                    val before = scene.snapshot.state
                    scene.event(scene.pointer("pointerdown", 0, from, .50))
                    repeat(32) { index ->
                        scene.event(scene.pointer("pointermove", 0, from + (to - from) * (index + 1) / 32, .50))
                        scene.frame(33)
                        delay(24)
                    }
                    scene.event(scene.pointer("pointerup", 0, to, .50))
                    assertEquals("completed", scene.lastAudio)
                    assertNotEquals(before, scene.snapshot.state)
                    repeat(135) { scene.frame(33); delay(24) }
                }
                File(output, "record-$name-done.txt").writeText("complete")
                delay(800)
            }
        }
    }

    @Test fun importsAUserSelectedGlbAndRejectsAnExternalReference() = runBlocking {
        val file = File(context.filesDir, "chat-camera/three-import-" + UUID.randomUUID() + ".glb")
        file.parentFile?.mkdirs()
        val original = context.assets.open("threejs/catalog/robot.glb").use { it.readBytes() }
        try {
            file.writeBytes(original)
            val uri = FileProvider.getUriForFile(context, context.packageName + ".chat-camera", file)
            val assets = ThreeSceneAssets(context)
            val attachment = assets.import(uri, "Robot.glb", "model/gltf-binary")
            val refs = ThreeSceneAssets.attachmentRefs(listOf(attachment))
            assertEquals(1, refs.size)
            assertEquals(original.size.toLong(), refs.single().size)
            val asset = assets.workerAssets(refs).first()
            assertArrayEquals(original, asset.open().use { it.readBytes() })
            val malformed = original.toString(Charsets.ISO_8859_1).replace("\"name\":\"Robot\"", "\"uri\":\"/abcde\"")
                .toByteArray(Charsets.ISO_8859_1)
            file.writeBytes(malformed)
            assertTrue(runCatching { assets.import(uri, "External.glb", "model/gltf-binary") }.isFailure)
        } finally { file.delete() }
    }

    private data class Run(val record: ToolRecord, val provider: Provider, val repairs: Int)

    private suspend fun generate(
        name: String, injectStatic: Boolean = false, onVisual: suspend () -> Unit = {},
        fixturePrompt: String? = null, fixturePlan: String? = null, sourceOverride: String? = null,
        session: CloudSession = CloudSession(),
    ): Run {
        val prompt = fixturePrompt ?: when (name) {
            "earth" -> "Create an Earth and Moon Three.js scene. The Moon orbits Earth every eight seconds. Add camera controls and pause."
            "workshop" -> "Создай 3D-мастерскую. Перетащи кубик М в ячейку. Проиграй локальный звук, покажи анимацию робота и сохрани прогресс."
            else -> "Create a Three.js torus knot with a moving custom shader."
        }
        val source = sourceOverride ?: fixtureSource(name)
        val provider = Provider(name, source, injectStatic, fixturePlan, onVisual)
        val database = "three-generation-" + UUID.randomUUID() + ".db"
        try {
            return ActivityScenario.launch(MainActivity::class.java).use {
                ToolStore(context, database).use { store ->
                    val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
                    try {
                        val draft = engine.createDraft(prompt, null)
                        approvePreview { engine.generate(draft.id, "fixture", emptyList(), session) }
                        val record = engine.accept(draft.id)
                        File(output, "$name-generated.json").writeText(record.spec)
                        File(output, "$name-request.txt").writeText(prompt)
                        File(output, "$name-repairs.txt").writeText(provider.repairs.toString())
                        Run(record, provider, provider.repairs)
                    } finally { engine.closeArtifacts() }
                }
            }
        } finally { context.deleteDatabase(database) }
    }

    private suspend fun <T> onScene(
        tool: ToolRecord, body: suspend (ThreeSceneSession, AlertDialog) -> T,
    ): T = withContext(Dispatchers.Main.immediate) {
        val activity = ThreeSceneForeground.activity()
        val spec = ToolSpec.parsePersisted(tool.spec)
        val session = ThreeSceneSession(activity, spec, tool.id, tool.revision, tool.state, tool.ui)
        val container = android.widget.FrameLayout(activity).apply {
            addView(session.web.view, android.widget.FrameLayout.LayoutParams(-1, 440))
        }
        val dialog = AlertDialog.Builder(activity).setTitle(spec.title).setView(container).create()
        try {
            dialog.show()
            dialog.window!!.setLayout(680, 500)
            delay(90)
            session.start()
            body(session, dialog)
        } finally {
            dialog.dismiss()
            session.close()
        }
    }

    private suspend fun saveFrame(name: String, scene: ThreeSceneSession, dialog: AlertDialog) {
        delay(55)
        val bitmap = scene.web.capture(checkNotNull(dialog.window))
        try {
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    private inner class Provider(
        val name: String, val source: String, val injectStatic: Boolean,
        val fixturePlan: String? = null, val onVisual: suspend () -> Unit,
    ) : ChatProvider {
        override val supportsSceneVision = true
        var repairs = 0
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            val text = when {
                request.generationStage == CloudGenerationStage.PLAN -> request.withFixtureCheckEvidence(fixturePlan ?: plan())
                request.generationStage == null -> {
                    val frames = request.turns.flatMap { it.images }
                    assertTrue(frames.isNotEmpty())
                    frames.forEachIndexed { index, frame ->
                        val image = android.util.Base64.decode(frame.substringAfter(','), android.util.Base64.DEFAULT)
                        File(output, "$name-checked-$index.png").writeBytes(image)
                    }
                    onVisual()
                    """{"issue":null}"""
                }
                request.turns.last().text.contains("Return one JSON patch") -> {
                    repairs++
                    android.util.Log.i("ThreeSceneTest", name + " " + request.turns.last().text.take(380))
                    """{"sourceEdits":[{"search":"const enabled = false;","replace":"const enabled = true;"}]}"""
                }
                else -> candidate()
            }
            emit(CloudEvent.Text(text))
        }

        fun plan(): String {
            val motion = """{"slots":{"view":{"kind":"surface"},"render":{"kind":"geometry"}},"steps":[
                {"op":"frames","slot":"view","count":6,"observe":"render","changes":2}]}"""
            val workshop = """{"slots":{"view":{"kind":"surface"},"render":{"kind":"geometry"},"message":{"kind":"value"},"audio":{"kind":"audio"},"saved":{"kind":"state"}},"steps":[
                {"op":"capture","slot":"render","as":"initial"},
                {"op":"surface","slot":"view","event":"pointer","phase":"down","x":0.30,"y":0.50},
                {"op":"surface","slot":"view","event":"pointer","phase":"move","x":0.45,"y":0.70},
                {"op":"surface","slot":"view","event":"pointer","phase":"up","x":0.45,"y":0.70},
                {"op":"assert","slot":"saved","test":"equals","value":false},
                {"op":"surface","slot":"view","event":"pointer","phase":"down","x":0.30,"y":0.50},
                {"op":"surface","slot":"view","event":"pointer","phase":"move","x":0.70,"y":0.50},
                {"op":"surface","slot":"view","event":"pointer","phase":"up","x":0.70,"y":0.50},
                {"op":"assert","slot":"render","test":"changed","capture":"initial"},
                {"op":"assert","slot":"message","test":"equals","value":"Готово"},
                {"op":"assert","slot":"audio","test":"equals","value":"completed"},
                {"op":"surface","slot":"view","event":"reopen"},
                {"op":"assert","slot":"saved","test":"equals","value":true}]}"""
            val checks = JSONObject().put("status", "checkable").put("scenario", JSONObject(if (name == "workshop") workshop else motion))
            return JSONObject().put("wholeScreen", true).put("language", if (name == "workshop") "ru" else "en")
                .put("runtime", "threejs-worker").put("scene", JSONObject().put("placement", "stage")
                    .put("brief", "Render the requested interactive 3D scene.").put("checks", checks)).toString()
        }

        fun candidate(): String {
            val data = JSONObject("""{"formatVersion":3,"capabilities":[],"runtime":{"kind":"threejs-worker","version":1},
                "behaviorBindings":{"view":"scene","render":{"surface":"scene","path":[0]}}}""")
            data.put("title", if (name == "workshop") "Мастерская слов" else if (name == "earth") "Earth and Moon" else "Three.js shader")
            data.put("description", if (name == "workshop") "Собери букву" else "An interactive 3D scene")
            data.put("source", if (injectStatic) source.replace("const enabled = true;", "const enabled = false;") else source)
            if (name == "workshop") {
                data.put("stateSchema", JSONObject("""{"placed":"boolean","status":"string"}"""))
                data.put("initialState", JSONObject("""{"placed":false,"status":"Перетащи М"}"""))
                data.put("ui", JSONObject("""{"type":"column","children":[{"type":"text","text":"Задание","bind":"status"}]}"""))
                data.getJSONObject("behaviorBindings").put("message", JSONArray().put(0)).put("saved", "placed")
            } else if (name == "earth") {
                data.put("stateSchema", JSONObject("""{"phase":"number","paused":"boolean","camera":"string"}"""))
                data.put("initialState", JSONObject("""{"phase":0,"paused":false,"camera":"orbit"}"""))
                data.put("ui", JSONObject("""{"type":"column","children":[
                    {"type":"tabs","text":"Camera","bind":"camera","action":"camera",
                     "options":[{"text":"Orbit view","value":"orbit"},{"text":"Earth close-up","value":"close"}]}]}"""))
            } else {
                data.put("stateSchema", JSONObject("""{"phase":"number","paused":"boolean"}"""))
                data.put("initialState", JSONObject("""{"phase":0,"paused":false}"""))
                data.put("ui", JSONObject("""{"type":"column","children":[{"type":"text","text":"3D scene"}]}"""))
            }
            return data.toString()
        }
    }
}
