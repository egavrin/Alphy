package com.alpha.assistant.tools

import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.DeepSeekProvider
import com.alpha.assistant.courses.CourseLessonPlan
import com.alpha.assistant.courses.CourseOutline
import com.alpha.assistant.courses.CourseSetup
import com.alpha.assistant.courses.CourseSlideKind
import com.alpha.assistant.courses.CourseSlidePlan
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.images.GeneratedImage
import com.alpha.assistant.images.ImageModel
import com.alpha.assistant.images.ImageSettings
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.ShellViewModel
import com.alpha.assistant.ui.openSeededChat
import com.alpha.assistant.ui.seedOpenChat
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveSharedImageWorkflowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val planets = listOf("Earth", "Mars", "Jupiter", "Saturn")

    @Test fun live() {
        val args = InstrumentationRegistry.getArguments()
        val run = args.getString("imageRun")
        assumeTrue(run != null)
        requireNotNull(run)
        require(run.matches(Regex("[a-f0-9]{24}")))
        val mode = requireNotNull(args.getString("imageScenario"))
        val reopen = mode in setOf("offline", "solar-capture")
        val context = instrumentation.targetContext
        val out = File(context.filesDir, "shared-image-evidence/$run").apply { mkdirs() }
        val phase = File(out, "phase.txt")
        val storeName = "image-live-$run.db"
        val keys = com.alpha.assistant.data.SecureKeyStore(context, "image-live-$run", "image-live-$run")
        val settings = ImageSettings(context)
        val robot = ShellRobot("image-$mode-$run")
        val store = com.alpha.assistant.data.ShellStore(context, storeName)
        val toolDatabase = "$storeName-tools.db"
        val chat = "image-live-chat"
        val saved = if (mode == "solar" || reopen) JSONObject(
            File(context.noBackupFilesDir, "shared-image-input/solar-v4.json").readText()) else null
        val original = saved?.let {
            val spec = it.getJSONObject("spec")
            ToolRecord(it.getString("id"), it.getInt("revision"), it.getInt("stateVersion"), spec.toString(),
                it.getJSONObject("state").toString(), spec.getJSONObject("ui").toString(), chat, true)
        }
        if (!reopen) {
            val key = OneUseCredential.receive("$run-deepseek", 60_000) { phase.writeText("deepseek-ready") }
            keys.save(key).getOrThrow()
            settings.saveKey(OneUseCredential.receive("$run-openrouter", 60_000) { phase.writeText("openrouter-ready") }).getOrThrow()
            settings.select(if (mode == "chat") ImageModel.SEEDREAM else ImageModel.QWEN)
            settings.setEnabled(true).getOrThrow()
            if (mode != "native-runtime") {
                original?.let { ToolStore(context, toolDatabase).use { db -> db.saveTool(it, revision = true) } }
                val now = System.currentTimeMillis()
                store.saveConversation(Conversation(chat, "Image workflow", now, now,
                    messages = original?.let { listOf(Message("v4", "assistant", "", now, "complete", toolId = it.id, toolRevision = it.revision)) }.orEmpty()))
                store.seedOpenChat(chat)
            }
        } else if (mode == "offline") {
            check(context.getSystemService(ConnectivityManager::class.java).activeNetwork == null)
        }
        var model: ShellViewModel? = null
        val live = DeepSeekProvider()
        val provider = object : ChatProvider by live {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                check(!reopen)
                val stage = when {
                    request.systemInstructions.startsWith("Plan only Three.js texture assets.") -> "TEXTURES"
                    request.generationStage != null -> request.generationStage.name
                    request.chatDecision -> "DECISION"
                    else -> "OTHER"
                }
                try {
                    live.respond(key, request, session) { event ->
                        if (event is CloudEvent.RoundComplete)
                            File(out, "deepseek.jsonl").appendText(JSONObject().put("stage", stage).put("reply", event.text).toString() + "\n")
                        emit(event)
                    }
                } catch (failure: CloudFailure) {
                    File(out, "deepseek.jsonl").appendText(JSONObject().put("stage", stage)
                        .put("error", failure.code).put("shape", failure.shape.toString()).toString() + "\n")
                    throw failure
                }
            }
        }
        fun model() = checkNotNull(model)
        fun record(id: String) = main { model().tools.tools.single { it.id == id } }
        fun send(prompt: String) {
            File(out, "request.txt").writeText(prompt)
            robot.edit(robot.text(R.string.composer_label), prompt)
            robot.click(robot.text(R.string.composer_send))
        }
        fun capturePlanets(tag: String) {
            for (planet in planets) {
                val focus = "Focus $planet"
                robot.reveal(focus, null)
                robot.click(focus)
                shot(out, "${planet.lowercase()}-$tag.png")
                if (tag == "saved") {
                    if (robot.find("Resume") != null) {
                        robot.reveal("Resume", null)
                        robot.click("Resume")
                    }
                    robot.waitFor("Pause")
                    SystemClock.sleep(1800)
                    shot(out, "${planet.lowercase()}-rotation.png")
                    robot.click("Pause")
                }
            }
        }
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun submissions(): Long {
            val path = context.getDatabasePath("alpha-image-operations.db")
            if (!path.isFile) return 0
            return android.database.sqlite.SQLiteDatabase.openDatabase(path.path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT count(*) FROM submissions", null).use { rows ->
                    check(rows.moveToFirst())
                    rows.getLong(0)
                }
            }
        }
        val startingSubmissions = submissions()
        fun attest(image: GeneratedImage) {
            val file = File(context.filesDir, "threejs-assets/${image.asset.id}")
            val bytes = file.readBytes()
            val hash = digest(bytes)
            check(hash == image.asset.id && bytes.size.toLong() == image.asset.size)
            val prefix = if (mode == "offline") "offline-" else ""
            File(out, "$prefix${image.slot}-receipt.json").writeText(image.json().toString(2))
            if (mode != "offline") File(out, "${image.slot}.${image.asset.mime.substringAfter('/')}").writeBytes(bytes)
        }
        CloudTestActivity.factory = { app -> ShellViewModel(app, provider, store, keys).openSeededChat(store).also { model = it } }
        try {
            androidx.test.core.app.ActivityScenario.launch(CloudTestActivity::class.java).use {
                robot.waitUntil(30_000) { main { !model().loading && (original == null || model().tools.tools.any { it.id == original.id }) } }
                main { model().tools.generationTraceObserver = { event -> File(out, "checker.log").appendText("$event\n") } }
                when (mode) {
                    "solar" -> {
                        requireNotNull(original)
                        main { model().openSurface(original.id) }
                        val refine = robot.text(R.string.tool_refine)
                        robot.reveal(refine, null)
                        robot.click(refine)
                        robot.waitUntil { main { model().snapshot.selected?.refinementTargetId == original.id } }
                        phase.writeText("solar-refining")
                        send("Refine the saved Solar System Observatory. Generate and apply one fresh 2:1 equirectangular color map each for Earth, Mars, Jupiter, and Saturn. " +
                            "Use seamless flat maps with continuous longitude edges. Show continents on Earth, rust on Mars, the Great Red Spot on Jupiter, and pale bands on Saturn. " +
                            "Keep the orbits, rotation, camera controls, and Saturn's separate ring. Add Focus Mars beside Focus Earth. " +
                            "Make Focus Mars visibly center the Mars sphere immediately. " +
                            "Verify the visible camera change with one viewport capture, one Focus Mars press, and one changed-geometry assertion. " +
                            "Use only the viewport and the Focus Mars button in this check. Use English.")
                        var approved = false
                        robot.waitUntil(650_000) {
                            val save = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Save scene")
                                ?.firstOrNull { it.isClickable && it.isEnabled }
                            if (!approved && save != null) {
                                shot(out, "native-preview.png")
                                approved = save.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            }
                            main {
                                val message = model().snapshot.selected?.messages?.lastOrNull()
                                check(message?.status !in setOf("error", "cancelled", "interrupted")) { "${message?.errorCode}: ${message?.error}" }
                                model().tools.tools.any { it.id == original.id && it.revision == original.revision + 1 } && !model().tools.busy
                            }
                        }
                        check(approved)
                        val revised = record(original.id)
                        val spec = ToolSpec.parsePersisted(revised.spec)
                        check(spec.generatedImages.size == 4 && planets.all { planet ->
                            spec.generatedImages.count { it.slot.contains(planet, ignoreCase = true) } == 1
                        })
                        check(spec.generatedImages.all { it.model == ImageModel.QWEN.wire && it.receipt != null })
                        check(spec.generatedImages.map { it.asset.id }.distinct().size == 4)
                        check(submissions() - startingSubmissions == 4L)
                        spec.generatedImages.forEach(::attest)
                        File(out, "saved-v5.json").writeText(JSONObject(revised.spec).toString(2))
                        main { model().openSurface(original.id) }
                        capturePlanets("saved")
                    }
                    "offline", "solar-capture" -> {
                        requireNotNull(original)
                        val reopened = record(original.id)
                        check(reopened.revision == original.revision + 1)
                        check(JSONObject(reopened.spec).toString() == JSONObject(File(out, "saved-v5.json").readText()).toString())
                        ToolSpec.parsePersisted(reopened.spec).generatedImages.also { check(it.size == 4) }.forEach(::attest)
                        main { model().openSurface(original.id) }
                        capturePlanets(if (mode == "offline") "offline" else "saved")
                        check(submissions() == startingSubmissions)
                        if (mode == "offline") {
                            check(context.getSystemService(ConnectivityManager::class.java).activeNetwork == null)
                            File(out, "offline-verified.txt").writeText("Same saved revision and texture hashes. No active network or new submissions.")
                        }
                    }
                    "chat" -> {
                        phase.writeText("chat-generating")
                        send("Generate a square illustration of a red fox in a snowy pine forest at sunrise. Include the illustration directly in your reply.")
                        var image: GeneratedImage? = null
                        robot.waitUntil(180_000) {
                            main {
                                val reply = model().snapshot.selected?.messages?.lastOrNull()
                                check(reply?.status != "error") { "${reply?.errorCode}: ${reply?.error}" }
                                image = reply?.attachments?.singleOrNull()?.takeIf { it.mime == GeneratedImage.ATTACHMENT }
                                    ?.let { GeneratedImage.read(JSONObject(it.contextText.orEmpty())) }
                                image != null
                            }
                        }
                        val result = checkNotNull(image)
                        check(result.model == ImageModel.SEEDREAM.wire)
                        attest(result)
                        shot(out, "chat-seedream.png")
                    }
                    "native", "native-runtime", "native-button", "game" -> {
                        val isNative = mode != "game"
                        phase.writeText("$mode-building")
                        if (mode == "native") send("Build a small native Alphy mini-app called Forest Badges. Generate a square illustration " +
                            "of a pine forest for the header and display it in the app. Include a direct button labeled Generate Badge. " +
                            "When clicked use the image.generate capability with the prompt 'A simple amber fox badge, square, centered', " +
                            "and display the returned image below the forest header with alt text Amber fox badge. Use English.")
                        else if (mode == "native-button") send("Build a simple native mini-app with a button labeled Generate Badge. " +
                            "Only when that button is pressed, request one square image using image.generate with exactly this prompt: " +
                            "\"A simple amber fox badge, square, centered\". Display the returned resource as an image with alt text Amber fox badge. " +
                            "Keep the button enabled after dismissal or interruption. Use English.")
                        else if (!isNative) send("Build a tiny Three.js game called Target Tap. Show one large textured cube at center. Generate a fresh square " +
                            "color texture of orange and blue geometric tile art for the cube. Keep the camera fixed. Include a native button " +
                            "labeled Hit Target. Pressing Hit Target adds one point and moves the cube to the right immediately. " +
                            "Include a visible score starting at 0 and use the state key score. Check the immediate position change using a viewport capture, one Hit Target press, and one changed-geometry assertion. " +
                            "No physics or animation. Use English.")
                        var approved = false
                        robot.waitUntil(480_000) {
                            if (mode == "game" && !approved) {
                                val save = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Save scene")
                                    ?.firstOrNull { it.isClickable && it.isEnabled }
                                if (save != null) {
                                    shot(out, "game-checker.png")
                                    approved = save.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                }
                            }
                            main {
                                val reply = model().snapshot.selected?.messages?.lastOrNull()
                                check(reply?.status !in setOf("error", "cancelled", "interrupted")) { "${reply?.errorCode}: ${reply?.error}" }
                                model().tools.tools.any { it.saved } && !model().tools.busy
                            }
                        }
                        val tool = main { model().tools.tools.single { it.saved } }
                        val spec = ToolSpec.parsePersisted(tool.spec)
                        check(spec.threeScene == (mode == "game"))
                        when (mode) {
                            "native-button" -> check(spec.generatedImages.isEmpty() && submissions() == startingSubmissions)
                            else -> spec.generatedImages.single().also { check(it.model == ImageModel.QWEN.wire) }.let(::attest)
                        }
                        File(out, "saved.json").writeText(JSONObject(tool.spec).toString(2))
                        main { model().openSurface(tool.id) }
                        val button = if (isNative) "Generate Badge" else "Hit Target"
                        robot.reveal(button, null)
                        shot(out, "$mode-before.png")
                        robot.click(button)
                        if (isNative) {
                            check(spec.capabilityResults && "image.generate" in spec.capabilities)
                            val prompt = "A simple amber fox badge, square, centered"
                            fun verifyConfirmation() {
                                robot.waitFor(robot.text(R.string.image_generate_confirm))
                                robot.waitUntil {
                                    val texts = robot.texts().joinToString("\n")
                                    ImageModel.QWEN.title in texts && prompt in texts
                                }
                            }
                            verifyConfirmation()
                            if (mode == "native-button") {
                                robot.click(robot.text(R.string.cancel))
                                robot.waitUntil { main { !model().tools.busy } }
                                check(submissions() == startingSubmissions)
                                robot.click(button)
                                verifyConfirmation()
                                settings.setEnabled(false).getOrThrow()
                                robot.click(robot.text(R.string.image_generate_now))
                                robot.waitUntil { main { !model().tools.busy } }
                                check(submissions() == startingSubmissions)
                                settings.setEnabled(true).getOrThrow()
                                robot.click(button)
                                verifyConfirmation()
                                main { model().openSurface(null) }
                                robot.waitUntil { main { !model().surfacePending } }
                                val staleConfirm = robot.text(R.string.image_generate_now)
                                if (robot.find(staleConfirm) != null) robot.click(staleConfirm)
                                robot.waitUntil { main { !model().tools.busy } }
                                check(submissions() == startingSubmissions)
                                main { model().openSurface(tool.id) }
                                robot.reveal(button, null)
                                robot.click(button)
                                verifyConfirmation()
                            }
                            shot(out, "native-confirm.png")
                            val confirm = robot.text(R.string.image_generate_now)
                            val oldConfirm = robot.waitFor(confirm)
                            robot.click(confirm)
                            robot.waitUntil { robot.find(confirm) == null }
                            if (mode == "native-button") {
                                oldConfirm.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            }
                            ToolStore(context, toolDatabase).use { tools ->
                                robot.waitUntil(170_000) {
                                    val effect = tools.effects(tool.id).lastOrNull { it.request.capability == "image.generate" }
                                    check(effect?.phase != "terminal" || effect.result.status == "succeeded") { effect?.result?.message.orEmpty() }
                                    val selected = effect?.result?.data?.optString("resourceId").orEmpty()
                                    val resource = tools.resource(selected)
                                    if (resource == null) false else {
                                        check(resource.owner == tool.id && resource.kind == "generatedImage")
                                        val resources = com.alpha.assistant.capabilities.CapabilityResources(context, tools)
                                        check(resources.resolve(tool.id, resource.id) == resource)
                                        check(runCatching { resources.resolve("another-app", resource.id) }.isFailure)
                                        val bytes = File(context.filesDir, "threejs-assets/${resource.locator}").readBytes()
                                        check(digest(bytes) == resource.locator && bytes.size.toLong() == resource.sizeBytes)
                                        check(effect!!.request.args.getString("prompt") == prompt)
                                        File(out, "runtime-result.json").writeText(effect.result.json()
                                            .put("sha256", resource.locator).put("model", ImageModel.QWEN.wire).toString(2))
                                        File(out, "runtime-image.${resource.mimeType.substringAfter('/')}").writeBytes(bytes)
                                        tools.tool(tool.id)?.state?.contains(resource.id) == true || tools.tool(tool.id)?.ui?.contains(resource.id) == true
                                    }
                                }
                            }
                            if (mode == "native-button") {
                                check(submissions() - startingSubmissions == 1L)
                                main { model().openSurface(null) }
                                main { model().openSurface(tool.id) }
                                robot.reveal(button, null)
                                check(submissions() - startingSubmissions == 1L)
                            }
                        } else {
                            check(approved)
                            check(File(out, "checker.log").readText().contains("outcome=VALIDATED"))
                            check(JSONObject(tool.state).getInt("score") == 0)
                            robot.waitUntil(15_000) { JSONObject(record(tool.id).state).optInt("score", -1) == 1 }
                            check(submissions() - startingSubmissions == 1L)
                            File(out, "game-action.txt").writeText("Hit Target changed the saved score from 0 to 1.")
                        }
                        shot(out, "$mode-after.png")
                    }
                    "learn" -> {
                        val courseStore = model().courseStore
                        val course = courseStore.create(
                            CourseSetup("Insect life cycles", "Beginner", "Teach two illustrated slides"),
                            CourseOutline(listOf(CourseLessonPlan("insects", "Insect life cycles", listOf(
                                CourseSlidePlan("butterfly", CourseSlideKind.EXPLANATION,
                                    "Explain butterfly metamorphosis and propose one generated square illustration of a butterfly and chrysalis."),
                                CourseSlidePlan("beetle", CourseSlideKind.EXAMPLE,
                                    "Give a simple beetle life cycle example and propose one generated square illustration of a beetle larva beside an adult beetle."),
                            )))),
                        )
                        fun illustration(id: String) = courseStore.slide(course.id, id)?.let {
                            ToolSpec.parsePersisted(it.content.definitionJson).generatedImages.singleOrNull()
                        }
                        phase.writeText("learn-opening")
                        main { model().resumeDesktopCourse(course.id) }
                        robot.waitUntil(220_000) { illustration("butterfly") != null }
                        attest(checkNotNull(illustration("butterfly")))
                        shot(out, "learn-butterfly.png")
                        robot.waitUntil(220_000) { robot.find(robot.text(R.string.course_next))?.isEnabled == true }
                        check(courseStore.slide(course.id, "beetle") == null)
                        android.database.sqlite.SQLiteDatabase.openDatabase(
                            context.getDatabasePath("alpha-image-operations.db").path, null,
                            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                        ).use { db ->
                            db.rawQuery("SELECT count(*) FROM submissions WHERE operation=?",
                                arrayOf("learn:${course.id}:${course.outlineRevision}:beetle")).use {
                                check(it.moveToFirst() && it.getInt(0) == 0)
                            }
                        }
                        File(out, "learn-prefetch.txt").writeText("Next slide ready; no beetle image submission before navigation.")
                        robot.click(robot.text(R.string.course_next))
                        robot.waitUntil(170_000) { illustration("beetle") != null }
                        val second = checkNotNull(illustration("beetle"))
                        attest(second)
                        shot(out, "learn-beetle.png")
                        main { model().openSurface(null) }
                        robot.waitFor(robot.text(R.string.composer_label))
                        main { model().resumeDesktopCourse(course.id) }
                        robot.waitFor(robot.text(R.string.course_slide_position, 2, 2))
                        check(illustration("beetle") == second)
                        shot(out, "learn-reopened.png")
                    }
                    else -> error("Unknown image workflow")
                }
            }
            phase.writeText("$mode-verified")
        } finally {
            CloudTestActivity.factory = null
            store.close()
            settings.clearKey()
            keys.clear()
        }
    }

    private fun shot(out: File, name: String) {
        SystemClock.sleep(400)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(out, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
