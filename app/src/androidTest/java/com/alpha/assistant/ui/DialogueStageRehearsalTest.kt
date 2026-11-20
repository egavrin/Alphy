package com.alpha.assistant.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.tools.appfile.plan as appFilePlan
import com.alpha.assistant.voice.ScriptedLiveServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Plan D, Dd3: the driver of the live dialogues on a scripted voice server and a scripted chat with fixture plans, so a fault of the
 * driver, its waits, its pictures, its sheet, or its report does not spend a paid run. No model call and no voice socket.
 * The argument `dialogueSteps` (default 2) sets the steps of a rehearsal: steps 3 and 4 need the spoken press and the spoken change.
 */
class DialogueStageRehearsalTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val files = checkNotNull(context.getExternalFilesDir(null))

    @Test fun heart() { rehearse("heart") }

    @Test fun moon() { rehearse("moon") }

    @Test fun japanese() { rehearse("japanese") }

    /**
     * Ruling OD-D2 b: the stored replies of a finished run (the argument `dialogueRun`, a folder under the files of the app, such as
     * `live-check/<run id>`) make its screens again at the display size of this device, with the words of that run under the pictures.
     * Without the argument, it replays a rehearsal of `moon`.
     */
    @Test fun replay() {
        val source = arguments.getString("dialogueRun")?.let { name ->
            require(name.matches(Regex("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*"))) { "dialogueRun is a folder under the files of the app." }
            File(files, name)
        } ?: rehearse("moon", "dialogue-rehearsal/replay-source")
        val stored = JSONObject(File(source, "replay.json").readText())
        val dialogue = DIALOGUES.getValue(stored.getString("dialogue"))
        val size = context.resources.configuration.let { "${it.screenWidthDp}x${it.screenHeightDp}dp" }
        val directory = File(source, "replay-$size").apply { deleteRecursively() }
        lateinit var run: DialogueRun
        run = DialogueRun(dialogue, directory, "replay", count = stored.getJSONArray("steps").length(), stored = stored)
        val report = run.run(server(dialogue), ScriptedChat(storedReplies(stored)) { run.step }, LOOPBACK)
        assertEquals(report.toString(), "complete", report.getString("phase"))
        assertTrue(File(directory, "sheet.png").isFile)
    }

    /** Runs the rehearsal of the dialogue [name] into [folder] and checks its files. Returns the folder. */
    private fun rehearse(name: String, folder: String = "dialogue-rehearsal/$name"): File {
        val dialogue = DIALOGUES.getValue(name)
        val directory = File(files, folder).apply { deleteRecursively() }
        val count = arguments.getString("dialogueSteps")?.toInt() ?: 2
        lateinit var run: DialogueRun
        run = DialogueRun(dialogue, directory, "rehearsal", count = count)
        val report = run.run(server(dialogue), ScriptedChat(REHEARSALS.getValue(name)) { run.step }, LOOPBACK)
        assertEquals(report.toString(), "complete", report.getString("phase"))
        val steps = report.getJSONArray("steps")
        assertEquals(count, steps.length())
        for (i in 0 until count) {
            val step = dialogue.steps[i]
            val row = steps.getJSONObject(i)
            assertTrue("step-${i + 1}.png", File(directory, "step-${i + 1}.png").isFile)
            // A pause of 300 ms or more in a recording would split it into two turns of the scripted server.
            if (step.speech != null) assertEquals(row.toString(), if (step.handOff) 1 else 0, row.getInt("handOffs"))
            if (step.screen) assertTrue(row.toString(), row.getJSONObject("screen").getBoolean("shown"))
        }
        if (count >= 2) assertEquals(steps.toString(), "new", steps.getJSONObject(1).getJSONObject("screen").getString("change"))
        // The spoken change edits the app of «Шаг 2», so the stage shows a new revision of the same app.
        if (count >= 4) assertEquals(steps.toString(), "revision", steps.getJSONObject(3).getJSONObject("screen").getString("change"))
        val checks = report.getJSONArray("checks")
        assertTrue(checks.toString(), (0 until checks.length()).all { checks.getJSONObject(it).getBoolean("pass") })
        assertTrue(File(directory, "sheet.png").isFile)
        val stored = JSONObject(File(directory, "replay.json").readText()).getJSONArray("steps")
        assertEquals(REHEARSALS.getValue(name)[2]?.decision, stored.getJSONObject(1).optString("decision"))
        return directory
    }

    private fun server(dialogue: Dialogue): ScriptedLiveServer {
        val spoken = dialogue.steps.filter { it.speech != null }
        return ScriptedLiveServer(spoken.map { checkNotNull(it.speech).words }, spoken.indices.filter { !spoken[it].handOff }.toSet())
    }

    private companion object {
        val LOOPBACK = Endpoints { "http://127.0.0.1:4000" }
        /** A change by lines of `lesson.plan`: the lap button gets a new label and a slower lap. */
        const val LESSON_CHANGE = """need lap = button("Медленный круг", [set(${'$'}/angle, 0), animate(${'$'}/angle, 360, 10000)])"""
        /** A change by lines of `quiz.plan`: the button that counts a learned card gets a Russian label. */
        const val QUIZ_CHANGE = """learnedButton = Button("Знаю", action=[inc(${'$'}/learned), set(${'$'}/index, (${'$'}/index + 1) % len(${'$'}/cards)), set(${'$'}/shown, false)])"""

        /** «Шаг 2» a screen of [plan], «Шаг 3» a press of the control [press], «Шаг 4» a change by the lines [change]. */
        fun rehearsal(plan: String, answer: String, press: String, change: String) = mapOf(
            2 to StepReplies(JSONObject(chatDecision("screen", answer, "A screen for the request")).put("show", false).toString(),
                listOf("APP_FILE" to appFilePlan(plan))),
            3 to StepReplies(chatDecision("press", "Нажимаю на экране.", press)),
            4 to StepReplies(chatDecision("refine", "Готово, экран изменён.", "Change the screen as asked"), listOf("APP_FILE_CHANGE" to change)))

        val REHEARSALS = mapOf(
            "heart" to rehearsal("lesson", "Сердце — насос из четырёх камер.", "Дальше", LESSON_CHANGE),
            "moon" to rehearsal("lesson", "Луна делает один оборот вокруг оси за один круг вокруг Земли.", "Вращение Луны: Без вращения",
                LESSON_CHANGE),
            "japanese" to rehearsal("quiz", "По-японски считают так: ити, ни, сан, си, го.", "答えを見る", QUIZ_CHANGE))
    }
}
