package com.alpha.assistant.ui

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.KeyCheck
import com.alpha.assistant.images.GeneratedImage
import com.alpha.assistant.images.ImageProvider
import com.alpha.assistant.images.ProviderImage
import com.alpha.assistant.tools.appfile.plan as appFilePlan
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

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

    /** The shell as [ShellFixture.install] builds it, with an image provider of OpenAI that returns a 16 px picture with no network. */
    private fun installWithPictures(chat: ChatProvider) {
        val fixture = stage.fixture
        check(fixture.keys.save("alpha-test-only", AiProvider.OPENAI).isSuccess)
        val pictures = ImageProvider { _, _, _ ->
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
            val bytes = ByteArrayOutputStream()
            try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) } finally { bitmap.recycle() }
            ProviderImage(bytes.toByteArray(), "image/png")
        }
        CloudTestActivity.factory = { app ->
            ShellViewModel(app, chat, fixture.store, fixture.keys, liveVoiceFactory = fixture.liveVoice(stage.socket, stage.audio),
                keyCheck = { KeyCheck.ACCEPTED }, legacy = fixture.legacy, models = fixture.models, imageStore = fixture.imageStore,
                voiceStore = fixture.voiceStore, imageProviders = mapOf(AiProvider.OPENAI.id to pictures)).also { fixture.model = it }
        }
    }

    private companion object {
        const val STAGE = "dialogue_stage"
        const val STRIP = "dialogue_strip"
        const val CHARACTER = "live_voice_character"
        /** A figure label of the calc app. */
        const val CALC_LABEL = "Per person"
        const val PICTURE_REQUEST = "Покажи, как выглядит сердце"
        const val ALT = "Сердце человека в разрезе"
        val PICTURE_ANSWER: String = JSONObject(chatDecision("answer", "Вот сердце в разрезе.")).put("image", JSONObject()
            .put("slot", "heart").put("prompt", "A human heart in section").put("alt", ALT).put("aspect", "1:1").put("required", true)
            .toString()).toString()
    }
}
