package com.alpha.assistant.ui

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.tools.AgentScriptProvider
import com.alpha.assistant.ui.DataTalk.Companion.DATA_REQUEST
import com.alpha.assistant.voice.FakeLiveTransport
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The pictures of the data forms (stage 5 look): each file of the folder look-plans in the external files directory of the app is the
 * scripted data reply of a spoken data request. The stage shows it in the light and in the dark theme, and each picture goes to
 * look/<file>-<theme>-<size>.png in that directory, where size is phone below 600 dp of the smallest width and fold above. With no folder
 * the test is skipped, so the full suite stays green. The controller pushes the files with adb before the run and looks at each picture.
 */
class StageDataLookTest {
    private val robot = ShellRobot("stage-data-look")

    @Test fun eachDataFormGivesItsPictureInBothThemes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = File(context.getExternalFilesDir(null), "look-plans").listFiles { file -> file.name.endsWith(".plan") }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no files in look-plans", files.isNotEmpty())
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

    /** The stage with the data form [text] after its narration call ended, saved as a picture in [target]. */
    private fun picture(text: String, target: File) {
        val fixture = ShellFixture("stage-data-look")
        val socket = FakeLiveTransport()
        val shell = TemplateShell(fixture, robot, socket)
        try {
            shell.live(DataTalk(AgentScriptProvider(text))) { _, model, _ ->
                socket.say(DATA_REQUEST, "d1", 100)
                shell.finalFile(model, shell.shown(model).id)
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
