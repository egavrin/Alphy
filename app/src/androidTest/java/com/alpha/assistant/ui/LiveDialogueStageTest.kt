package com.alpha.assistant.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.tools.liveCheck
import com.alpha.assistant.tools.liveChatModel
import com.alpha.assistant.voice.LiveTransport
import com.alpha.assistant.voice.OkHttpLiveTransport
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * Plan D, Dd3: the three live dialogues of design part 4 with the real voice endpoint, the recorded speech, and the real chat provider
 * with its first chat model. Each paid run writes `step-1.png` to `step-4.png`, `sheet.png`, `events.log`, `replay.json` (for a replay
 * at another size, ruling OD-D2 b), and `dialogue.json` to `live-check/<run id>/`, and merges the fields of `dialogue.json` into the
 * `report.json` of `liveCheck`. [DialogueStageRehearsalTest] runs the same driver with no cost.
 *
 * Lane V: `pendulum`, `volcano`, and `water` are adaptive dialogues on new subjects. They answer a question back with «Да» and a
 * spoken failure with «Попробуй ещё раз», let the full generation run, and fail when a step fails [PASS_BAR]. The report holds the
 * outcome and verdict of each step and the `coverage` of the elements of the owner's talk. `water` is the run at the fold size.
 */
class LiveDialogueStageTest {
    @Test fun heart() = live("heart")

    @Test fun moon() = live("moon")

    @Test fun japanese() = live("japanese")

    @Test fun pendulum() = live("pendulum")

    @Test fun volcano() = live("volcano")

    @Test fun water() = live("water")

    // The template dialogues and the no-screen probe of the plan of 2026-11-21 (Tasks 10b and 19). Run tplC and tplF at the fold size.
    @Test fun tplA() = live("tpl-a", "tplA")

    @Test fun tplB() = live("tpl-b", "tplB")

    @Test fun tplC() = live("tpl-c", "tplC")

    @Test fun tplD() = live("tpl-d", "tplD")

    @Test fun tplE() = live("tpl-e", "tplE")

    @Test fun tplF() = live("tpl-f", "tplF")

    @Test fun noScreen() = live("no-screen", "noScreen")

    // The UX study of 2026-11-21 (evidence/ux-2026-11-21/matrix.md): 12 research dialogues of probe steps. Each step is an
    // observation with no verdict, so a run ends complete unless a hard stop ends it. The host sets the theme and the size with adb.
    @Test fun uxHeart() = live("ux-heart", "uxHeart")

    @Test fun uxWhy() = live("ux-why", "uxWhy")

    @Test fun uxOrbit() = live("ux-orbit", "uxOrbit")

    @Test fun uxPythagoras() = live("ux-pythagoras", "uxPythagoras")

    @Test fun uxWeather() = live("ux-weather", "uxWeather")

    @Test fun uxFootball() = live("ux-football", "uxFootball")

    @Test fun uxImages() = live("ux-images", "uxImages")

    @Test fun uxImagesB() = live("ux-images-b", "uxImagesB")

    @Test fun uxCompare() = live("ux-compare", "uxCompare")

    @Test fun uxTap() = live("ux-tap", "uxTap")

    @Test fun uxLesson() = live("ux-lesson", "uxLesson")

    @Test fun uxMixed() = live("ux-mixed", "uxMixed")

    @Test fun uxKinds() = live("ux-kinds", "uxKinds")

    // The speed dialogues of the paired run of each stage (plan 2026-11-22, the speed form): one probe step each.
    @Test fun speedHeart() = live("speed-heart", "speedHeart")

    @Test fun speedPythagoras() = live("speed-pythagoras", "speedPythagoras")

    @Test fun speedOrbit() = live("speed-orbit", "speedOrbit")

    /** Runs the dialogue [name] as the test method [method]. */
    private fun live(name: String, method: String = name) {
        val runId = InstrumentationRegistry.getArguments().getString("liveRunId")
        val files = checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        val directory = File(files, "live-check/$runId")
        try {
            liveCheck("LiveDialogueStageTest#$method", missing = { account -> "Only OpenAI has GPT-Live.".takeIf { account.provider != AiProvider.OPENAI } },
                model = { it.liveChatModel() }) { account, model ->
                // The plan step, and so the kind screen, runs only with the first chat model of the provider.
                check(model == AiProvider.OPENAI.profile.chatModels.first()) { "The dialogues need the first chat model." }
                val cloud = CloudChatProvider({ account }, Endpoints.PRODUCTION, chatModel = { checkNotNull(model) })
                // The shell holds only the fixture marker. The run key goes to the chat call and to the socket here.
                val chat = object : ChatProvider {
                    override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                        check(key == MARKER)
                        cloud.respond(account.key, request, session, emit)
                    }
                }
                val socket = OkHttpLiveTransport()
                val transport = LiveTransport { url, key, listener -> check(key == MARKER); socket.connect(url, account.key, listener) }
                try {
                    val report = DialogueRun(DIALOGUES.getValue(name), directory, "live", "dialogue.json").run(transport, chat, Endpoints.PRODUCTION)
                    "${report.getJSONArray("steps").length()} steps, ${report.getInt("chatCalls")} chat calls, ${report.opt("voiceSeconds")} voice seconds" +
                        (report.optJSONObject("coverage")?.let { ", coverage $it" } ?: "")
                } finally {
                    val leaked = directory.listFiles().orEmpty().filter { it.isFile && it.extension in setOf("json", "log") }
                        .filter { account.key in it.readText() }.map { it.name }
                    // The names only: the failure text never holds the key.
                    if (leaked.isNotEmpty()) throw AssertionError("A record holds the key: $leaked")
                }
            }
        } finally {
            merge(directory)
        }
    }

    /** `liveCheck` writes `report.json` last, so the numbers of the dialogue join it after the check. Its phase stays. */
    private fun merge(directory: File) {
        val report = File(directory, "report.json")
        val dialogue = File(directory, "dialogue.json")
        if (!report.isFile || !dialogue.isFile) return
        val merged = JSONObject(report.readText())
        val numbers = JSONObject(dialogue.readText())
        numbers.keys().forEach { if (it != "phase") merged.put(it, numbers.get(it)) }
        File(directory, "report.tmp").apply { writeText(merged.toString(2)); check(renameTo(report)) }
    }

    private companion object {
        /** The key that [ShellFixture.install] saves. */
        const val MARKER = "alpha-test-only"
    }
}
