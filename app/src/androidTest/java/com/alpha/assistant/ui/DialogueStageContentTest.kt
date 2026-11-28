package com.alpha.assistant.ui

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.images.GeneratedImage
import com.alpha.assistant.images.ImageProvider
import com.alpha.assistant.images.ProviderImage
import com.alpha.assistant.tools.AgentScriptProvider
import com.alpha.assistant.tools.STAGE_PICTURE_TAG
import com.alpha.assistant.tools.Scripted
import com.alpha.assistant.tools.agentFixtureFinish
import com.alpha.assistant.tools.agentWrite
import com.alpha.assistant.tools.appfile.plan as appFilePlan
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Plan D, Da2, rulings 2 and 3 of the controller: what the stage and the strip show, each thing once. */
class DialogueStageContentTest {
    private val stage = StageShell("dialogue-stage-content")
    private val robot get() = stage.robot
    @After fun remove() = stage.remove()

    /** Ruling 2: the strip shows the small character only while the stage shows an app, and the status and the microphone state always. */
    @Test fun theStripShowsTheCharacterOnlyBesideAnApp() = stage.onStage(appFilePlan("calc")) { scenario, _ ->
        robot.waitUntil { robot.composedTextIn(scenario, STAGE, CALC_LABEL) }
        assertTrue(robot.composedIn(scenario, STRIP) { it.getOrNull(SemanticsProperties.TestTag) == CHARACTER })
        assertFalse(robot.composedIn(scenario, STAGE) { it.getOrNull(SemanticsProperties.TestTag) == CHARACTER })
        assertTrue(robot.composedTextIn(scenario, STRIP, robot.text(R.string.live_voice_listening)))
        assertTrue(robot.composedTextIn(scenario, STRIP, robot.text(R.string.live_voice_mic_on)))
    }

    /** Ruling 3: a finished answer with a generated image shows its card on the stage, and a later answer with no image removes it. */
    @Test fun aFinishedAnswerWithAPictureShowsOnTheStage() {
        installWithPictures(object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) =
                emit(CloudEvent.RoundComplete(if (request.decisionContext?.originalUserTurn?.text == PICTURE_REQUEST) PICTURE_ANSWER
                    else chatDecision("answer", "Fixture response"), false))
        })
        stage.live { scenario, model ->
            scenario.onActivity { check(model.imageGeneration.settings.setEnabled(true).isSuccess) }
            stage.socket.say(PICTURE_REQUEST, "d1", 100)
            robot.waitUntil(60_000) { stage.socket.commentary().any { it.first == "d1" } }
            val answer = checkNotNull(model.snapshot.selected?.messages?.last())
            assertTrue("${answer.status} ${answer.error}", answer.attachments.any { it.mime == GeneratedImage.ATTACHMENT })
            robot.waitUntil { robot.composedIn(scenario, STAGE) { it.getOrNull(SemanticsProperties.ContentDescription)?.contains(ALT) == true } }
            stage.socket.say("Какая погода", "d2", 2_000)
            robot.waitUntil(60_000) { stage.socket.commentary().any { it.first == "d2" } }
            robot.waitUntil { !robot.composedIn(scenario, STAGE) { it.getOrNull(SemanticsProperties.ContentDescription)?.contains(ALT) == true } }
        }
    }

    /**
     * Plan "one scene", decision 22: a hand-off that fails in its search shows on the stage the fixed text of its error group, which says
     * what to say next, and no English (lane B of the study, O2). A source check failure has a chat text of its own: the stage shows the
     * text of its group in its place, and the card keeps its retry button.
     */
    @Test fun aStageErrorSaysWhatToSayNext() {
        stage.fixture.install(chat = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) =
                when (request.decisionContext?.originalUserTurn?.text) {
                    SEARCH_REQUEST -> throw CloudFailure("search_no_usable_results", SEARCH_FAILURE)
                    SOURCE_REQUEST -> throw CloudFailure("source_attribution", "Cite only the sources of the search.")
                    KEY_REQUEST -> throw CloudFailure("key_missing", "The key is missing.")
                    else -> emit(CloudEvent.RoundComplete(chatDecision("answer", "Fixture response"), false))
                }
        }, liveVoice = stage.fixture.liveVoice(stage.socket, stage.audio))
        stage.live { scenario, model ->
            val search = robot.text(R.string.stage_error_search)
            fun failed(code: String) = model.snapshot.selected?.messages?.any { it.id == model.liveVoiceCardId && it.errorCode == code } == true
            stage.socket.say(SEARCH_REQUEST, "d1", 100)
            robot.waitUntil(60_000) { failed("search_no_usable_results") && stageTexts(robot).isNotEmpty() }
            assertEquals("English texts on the stage", emptyList<String>(), stageTexts(robot).filter { LATIN in it })
            assertTrue("the text of the search group", robot.composedTextIn(scenario, STAGE, search))
            stage.socket.say(SOURCE_REQUEST, "d2", 2_000)
            // Only the card of a source check failure has its own retry button, so it shows when the stage has the second card.
            robot.waitUntil(60_000) { failed("source_attribution") && robot.composedTextIn(scenario, STAGE, robot.text(R.string.chat_message_retry)) }
            assertTrue("the text of the search group", robot.composedTextIn(scenario, STAGE, search))
            assertFalse("the chat text of a source check failure", robot.composedTextIn(scenario, STAGE, robot.text(R.string.chat_source_attribution)))
            stage.socket.say(KEY_REQUEST, "d3", 4_000)
            // Only the card of a key error has the settings button, so it shows when the stage has the third card.
            robot.waitUntil(60_000) { failed("key_missing") && robot.composedTextIn(scenario, STAGE, robot.text(R.string.open_settings)) }
            assertTrue("the text of the key group", robot.composedTextIn(scenario, STAGE, robot.text(R.string.stage_error_key)))
        }
    }

    /** Decision 22: one code of each error group gives the stage text of its group. A key error asks for the settings, also auth. */
    @Test fun eachStageErrorGroupHasItsText() {
        val groups = mapOf("search_no_usable_results" to R.string.stage_error_search, "source_attribution" to R.string.stage_error_search,
            "image_generation" to R.string.stage_error_image, "network" to R.string.stage_error_no_answer,
            "http_500" to R.string.stage_error_no_answer, "widget_size" to R.string.stage_error_too_big, "key_missing" to R.string.stage_error_key,
            "auth" to R.string.stage_error_key, "unknown_code" to R.string.stage_error_other)
        assertEquals(groups.mapValues { robot.text(it.value) }, groups.mapValues { robot.text(stageError(it.key)) })
    }

    /**
     * Plan "one scene", Task 8: in dark theme the stage, the app, and the canvas of a full mini-app are one surface, and a card keeps its
     * palette tint. A template draws its picture in a hero card, which is a card, so its canvas takes the tint of that card.
     */
    @Test fun theStageHasOneSurfaceInDarkTheme() {
        val night = checkNotNull(Regex("""Night mode: (\S+)""").find(robot.shell("cmd uimode night"))).groupValues[1]
        try {
            robot.shell("cmd uimode night yes")
            robot.waitUntil { (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES }
            val full = AgentScriptProvider(listOf(Scripted.Text("outside(\"game\")"), Scripted.Text(agentWrite(ORBIT_APP)), Scripted.Text(agentFixtureFinish)))
            val shell = TemplateShell(stage.fixture, robot, stage.socket)
            shell.live(TemplateTalk(AgentScriptProvider(shipTemplate()), full)) { scenario, model, _ ->
                scenario.onActivity { assertTrue("dark theme", (it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) }
                val ship = shell.screen(model)
                assertOneSurface(scenario, "template", SHIP_CAPTION, canvas = null, picture = true)
                val before = shell.messages(model).size
                stage.socket.say(TemplateTalk.APP_REQUEST, "d2", 2_000)
                shell.waitForTheAnswer(model, before)
                robot.waitUntil(60_000) { model.stageToolId != null && model.stageToolId != ship.id && !model.tools.busy }
                assertOneSurface(scenario, "full", ORBIT_CARD, canvas = ORBIT)
            }
        } finally { robot.shell("cmd uimode night $night") }
    }

    /**
     * The page in the stage gutter, the app background in the padding of its column, and [canvas] by its left edge equal the shell surface
     * within 2 for each channel, at the height of the text [card], away from the corners. The card with the title [card] differs. With
     * [picture], the text is the caption under the large picture (stage A, K1), and the card that differs is the picture card: its pixel is
     * 6 dp from its left edge and 24 dp under its top, under its corner.
     */
    private fun assertOneSurface(scenario: ActivityScenario<CloudTestActivity>, name: String, card: String, canvas: String?, picture: Boolean = false) {
        robot.waitUntil(30_000) { robot.composedTextIn(scenario, STAGE, card) &&
            (canvas == null || robot.composedIn(scenario, STAGE) { it.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith("$canvas.") } == true }) }
        robot.awaitFrames(5)
        val origin = IntArray(2)
        scenario.onActivity { it.window.decorView.getLocationOnScreen(origin) }
        val density = stage.fixture.context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val page = robot.composedBounds(scenario, STAGE).first
        // The gutter of the stage (DialogueStage, Stage A F1), and the column padding «medium» of the app.
        val gutter = dp(if (page.width() / density >= 600) 16 else 8)
        val title = checkNotNull(robot.composedBoundsIn(scenario, STAGE, robot.hasText(card)))
        val drawing = canvas?.let { label ->
            checkNotNull(robot.composedBoundsIn(scenario, STAGE) { it.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith("$label.") } == true })
        }
        val pictureCard = if (picture) checkNotNull(robot.composedBoundsIn(scenario, STAGE) { it.getOrNull(SemanticsProperties.TestTag) == STAGE_PICTURE_TAG }) else null
        val shot = checkNotNull(robot.automation.takeScreenshot())
        try {
            File(checkNotNull(stage.fixture.context.getExternalFilesDir("one-surface")), "$name.png").outputStream().use {
                check(shot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            fun at(x: Int, y: Int) = shot.getPixel(origin[0] + x, origin[1] + y)
            val y = title.centerY()
            val surface = AlphaDarkColors.surface.toArgb()
            val pixels = listOfNotNull("page" to at(page.left + gutter / 2, y), "app" to at(page.left + gutter + dp(6), y),
                drawing?.let { "canvas" to at(it.left + dp(8), it.centerY()) })
            val cardPixel = pictureCard?.let { at(it.left + dp(6), it.top + dp(24)) } ?: at(title.left - dp(6), y)
            val found = (pixels + ("card" to cardPixel)).joinToString { "${it.first} #${Integer.toHexString(it.second)}" }
            Log.i("OneSurface", "$name: $found")
            pixels.forEach { (part, pixel) -> assertTrue("$name: $part is the shell surface #${Integer.toHexString(surface)}: $found", near(pixel, surface)) }
            assertFalse("$name: the card keeps its tint: $found", near(cardPixel, surface))
        } finally { shot.recycle() }
    }

    private fun near(a: Int, b: Int) = listOf(16, 8, 0).all { shift -> abs((a shr shift and 0xFF) - (b shr shift and 0xFF)) <= 2 }

    /** The shell with an image provider of OpenAI that returns a 16 px picture with no network. */
    private fun installWithPictures(chat: ChatProvider) {
        val pictures = ImageProvider { _, _, _ ->
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
            val bytes = ByteArrayOutputStream()
            try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) } finally { bitmap.recycle() }
            ProviderImage(bytes.toByteArray(), "image/png")
        }
        stage.fixture.install(chat = chat, liveVoice = stage.fixture.liveVoice(stage.socket, stage.audio),
            imageProviders = mapOf(AiProvider.OPENAI.id to pictures))
    }

    private companion object {
        const val STAGE = "dialogue_stage"
        const val STRIP = "dialogue_strip"
        const val CHARACTER = "live_voice_character"
        /** A figure label of the calc app. */
        const val CALC_LABEL = "Per person"
        const val PICTURE_REQUEST = "Покажи, как выглядит сердце"
        const val ALT = "Сердце человека в разрезе"
        const val SEARCH_REQUEST = "Найди счёт вчерашнего матча"
        const val SOURCE_REQUEST = "Найди, кто забил гол"
        const val KEY_REQUEST = "Найди состав команды"
        /** The message of the failure of a search with no usable source (WebSearch). */
        const val SEARCH_FAILURE = "Search returned no usable sources. Retry the search or ask without web sources."
        val LATIN = Regex("[A-Za-z]")
        val PICTURE_ANSWER: String = JSONObject(chatDecision("answer", "Вот сердце в разрезе.")).put("image", JSONObject()
            .put("slot", "heart").put("prompt", "A human heart in section").put("alt", ALT).put("aspect", "1:1").put("required", true)
            .toString()).toString()
        const val ORBIT = "Земля на орбите"
        const val ORBIT_CARD = "Один оборот"
        /** A full mini-app in the blue palette with its canvas straight in the column of its theme, as the full generation writes one. */
        val ORBIT_APP: String = JSONObject("""
            {"title":"Орбита","description":"Земля на орбите","capabilities":[],"stateSchema":{"n":"number"},"initialState":{"n":0},
            "surfaces":[{"id":"main","width":300,"height":200}],
            "ui":{"type":"theme","preset":"clean","palette":"blue","cornerRadius":20,"children":[{"type":"column","style":{"gap":"medium","padding":"medium"},
              "children":[{"type":"canvas","text":"$ORBIT","surfaces":["main"]},
              {"type":"card","text":"$ORBIT_CARD","children":[{"type":"text","text":"Земля проходит круг за год."}]}]}]}}
        """.trimIndent()).put("source", "return {state:input.state,surfaces:{main:{commands:[{type:'circle',x:150,y:100,radius:20,color:'accent'}]}}};")
            .toString()
    }
}
