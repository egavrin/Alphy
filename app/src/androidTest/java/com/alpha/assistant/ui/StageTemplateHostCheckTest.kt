package com.alpha.assistant.ui

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.tools.AgentScriptProvider
import com.alpha.assistant.tools.agent.AppFileNames
import com.alpha.assistant.ui.TemplateTalk.Companion.SCREEN_REQUEST
import com.alpha.assistant.voice.FakeLiveTransport
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Gate 2c (Task 8b): the host check of the app on model-made template files. Each file of the folder tpl-hostcheck in the external files
 * directory of the app is the scripted template reply of a spoken screen request. A file passes when the app is stored with that file and
 * no template_check fallback ran. Each file prints one line with its outcome, and a miss also with the host check reason. With no folder
 * the test is skipped, so the full suite stays green. The controller pushes the files with adb before the run.
 */
class StageTemplateHostCheckTest {
    private val robot = ShellRobot("stage-template-host")

    @Test fun eachModelMadeTemplatePassesTheHostCheck() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "tpl-hostcheck")
        val files = folder.listFiles { file -> file.name.endsWith(".plan") }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no files in $folder", files.isNotEmpty())
        val misses = mutableListOf<String>()
        for (file in files) {
            val text = file.readText().trimEnd()
            val fixture = ShellFixture("stage-template-host")
            val socket = FakeLiveTransport()
            val shell = TemplateShell(fixture, robot, socket)
            val logged = generationLog().size
            try {
                shell.live(TemplateTalk(AgentScriptProvider(text))) { _, model, phases ->
                    socket.say(SCREEN_REQUEST, "d1", 100)
                    // The screen is stored, or the plan step fell back: a template check fault goes on to the full generation.
                    robot.waitUntil(60_000) { model.stageToolId != null || phases.any { it.startsWith(FALLBACK) } }
                    val outcomes = phases.filter { it.startsWith(AppFileNames.TEMPLATE_OUTCOME) || it.startsWith(AppFileNames.PLAN_OUTCOME) }
                    val miss = when {
                        "$FALLBACK${AppFileNames.TEMPLATE_CHECK}" in phases -> {
                            robot.waitUntil { generationLog().drop(logged).any { AppFileNames.TEMPLATE_CHECK in it } }
                            generationLog().drop(logged).last { AppFileNames.TEMPLATE_CHECK in it }.substringAfter("outcome=").substringBefore(" ")
                        }
                        phases.any { it.startsWith(FALLBACK) } -> "a plan fallback: $outcomes"
                        else -> when (val stored = appFileOf(shell.stored(model, checkNotNull(model.stageToolId)))?.trimEnd()) {
                            null -> "stored with no app file"
                            text -> null
                            else -> "stored with other lines: " + text.lines().filterNot { it in stored.lines() }
                        }
                    }
                    println("gate 2c ${file.name}: ${miss?.let { "miss $it" } ?: "pass"} $outcomes")
                    if (miss != null) misses += "${file.name}: $miss"
                }
            } finally { shell.remove() }
        }
        assertTrue("${misses.size} of ${files.size} files miss the host check: $misses", misses.size <= MAX_MISSES)
    }

    /** The release log lines of this process (ToolGenerationDiagnostic.logGeneration), which name the host check of a template_check fallback. */
    private fun generationLog(): List<String> =
        ProcessBuilder("logcat", "-d", "-s", "AlphyGeneration:W", "--pid=${Process.myPid()}").start().inputStream.bufferedReader().readLines()

    private companion object {
        const val FALLBACK = "${AppFileNames.PLAN_OUTCOME}:fallback:"
        /** The bar of gate 2c: at least 22 of the 24 files pass. */
        const val MAX_MISSES = 2
    }
}
