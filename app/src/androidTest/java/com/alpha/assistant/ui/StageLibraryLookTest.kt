package com.alpha.assistant.ui

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.tools.AgentScriptProvider
import com.alpha.assistant.voice.FakeLiveTransport
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The pictures of the graphics library (stage 6 look): each file of the folder library-plans in the external files directory of the app is the
 * scripted template reply of a spoken screen request, or a free app file (app(...)) as the reply of the plan step. The stage shows it in the light and in the dark theme, and each picture goes to
 * look/<file>-<theme>-<size>.png in that directory, where size is phone below 600 dp of the smallest width and fold above. With no folder the
 * test is skipped, so the full suite stays green. The job pushes the files with adb before the run and looks at each picture.
 */
class StageLibraryLookTest {
    private val robot = ShellRobot("stage-library-look")

    @Test fun eachSceneGivesItsPictureInBothThemes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = File(context.getExternalFilesDir(null), "library-plans").listFiles { file -> file.name.endsWith(".plan") }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no files in library-plans", files.isNotEmpty())
        val out = File(context.getExternalFilesDir(null), "look").apply { mkdirs() }
        val size = if (context.resources.configuration.smallestScreenWidthDp < 600) "phone" else "fold"
        val night = checkNotNull(Regex("""Night mode: (\S+)""").find(robot.shell("cmd uimode night"))).groupValues[1]
        try {
            for ((theme, mode) in listOf("light" to Configuration.UI_MODE_NIGHT_NO, "dark" to Configuration.UI_MODE_NIGHT_YES)) {
                robot.shell("cmd uimode night ${if (mode == Configuration.UI_MODE_NIGHT_YES) "yes" else "no"}")
                robot.waitUntil { (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == mode }
                for (file in files) picture(file.readText(), File(out, "${file.nameWithoutExtension}-$theme-$size.png"))
            }
        } finally { robot.shell("cmd uimode night $night") }
    }

    /** The stage with the scene [text] after its narration call ended, saved as a picture in [target]. */
    private fun picture(text: String, target: File) {
        val fixture = ShellFixture("stage-library-look")
        val shell = TemplateShell(fixture, robot, FakeLiveTransport())
        try {
            // A free app file (app(...)) is the reply of the plan step, and a template file the reply of the template call.
            val talk = if (text.startsWith("app(")) TemplateTalk(plans = AgentScriptProvider(text)) else TemplateTalk(AgentScriptProvider(text))
            shell.live(talk) { _, model, _ ->
                shell.finalFile(model, shell.screen(model).id)
                robot.awaitFrames(30)
                val picture = checkNotNull(robot.instrumentation.uiAutomation.takeScreenshot())
                try {
                    target.outputStream().use { check(picture.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { picture.recycle() }
                println("look ${target.name}")
            }
        } finally { shell.remove() }
    }
}
