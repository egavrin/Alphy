package com.alpha.assistant.tools

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.core.view.allViews
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.galleryWallpaperColors
import com.alpha.assistant.ui.AlphaTheme
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativePresentationWorkflowTest {
    private val robot = ShellRobot("native-presentation")
    private val output get() = File(robot.instrumentation.targetContext.getExternalFilesDir(null), "native-presentation").apply { mkdirs() }

    /** Run the same target and inputs on both revisions for comparable native captures. */
    @Test fun captureComparableFixtures() {
        val font = robot.shell("settings get system font_scale")
        val manifest = JSONArray()
        try {
            for (scale in listOf(1f, 2f)) {
                robot.shell("settings put system font_scale $scale")
                withPreview { scenario ->
                    for (wide in listOf(false, true)) {
                        robot.rotate(scenario, landscape = wide)
                        robot.waitUntil {
                            var actual = 0f
                            scenario.onActivity { actual = it.resources.configuration.fontScale }
                            actual == scale
                        }
                        for (dark in listOf(false, true)) for (scene in PresentationFixtures.comparison) {
                            preview(scenario, scene, dark, wide)
                            val stem = "${scene.id}-${if (dark) "dark" else "light"}-${if (wide) "wide" else "narrow"}-${(scale * 100).toInt()}"
                            capture(stem)
                            var width = 0
                            scenario.onActivity { width = it.resources.configuration.screenWidthDp }
                            manifest.put(JSONObject().put("file", "$stem.png").put("seed", "green").put("dark", dark)
                                .put("fontScale", scale.toDouble()).put("windowWidthDp", width).put("fixture", scene.id))
                        }
                    }
                }
            }
            assertEquals(PresentationFixtures.comparison.size * 8, manifest.length())
        } finally {
            if (font == "null") robot.shell("settings delete system font_scale")
            else robot.shell("settings put system font_scale $font")
            File(output, "matrix.json").writeText(manifest.toString(2))
            assertEquals("Android font scale was restored", font, robot.shell("settings get system font_scale"))
        }
    }

    @Test fun openSectionsAndRecordsUseTheSurfaceAndStatusRemainsReadable() = withPreview { scenario ->
        robot.rotate(scenario, landscape = false)
        for (dark in listOf(false, true)) {
            val counter = PresentationFixtures.counter
            preview(scenario, counter, dark)
            assertEquals("The section does not repeat the host title", 1, robot.texts().count { it.trim() == counter.title })
            val scheme = preview(scenario, PresentationFixtures.budget, dark)
            val section = robot.bounds("Расходы месяца")
            val card = robot.bounds("Лимит на неделю")
            val body = robot.bounds("Запланировано 4 000 ₽")
            capture("surfaces-$dark") { image ->
                assertEquals("Open section", scheme.surface.toArgb(), image.getPixel(section.left - dp(4), section.centerY()))
                assertEquals("Explicit tinted card", scheme.primaryContainer.toArgb(), image.getPixel(card.left - dp(4), card.centerY()))
                assertTrue("Rendered section body text", pixels(image, body, scheme.onSurface.toArgb()) > 0)
                assertTrue("Section body text contrast", ColorUtils.calculateContrast(scheme.onSurface.toArgb(), scheme.surface.toArgb()) >= 4.5)
            }
            preview(scenario, PresentationFixtures.records, dark)
            val first = robot.bounds("Продукты")
            val second = robot.bounds("Транспорт")
            capture("record-rows-$dark") { image ->
                assertEquals("Records use the host surface", scheme.surface.toArgb(), image.getPixel(first.left - dp(4), first.centerY()))
                assertTrue("A divider separates records", (first.bottom until second.top).any { y ->
                    image.getPixel(image.width / 2, y) == scheme.outlineVariant.toArgb()
                })
            }
            preview(scenario, PresentationFixtures.reading, dark)
            val status = robot.bounds("Пауза")
            val empty = robot.bounds("Нет заметок")
            capture("status-empty-$dark") { image ->
                // Warning keeps its amber in every palette. The reading app has its own palette background.
                val (_, warning, onWarning) = checkNotNull(ToolPalettes.status("warning", dark))
                val ink = onWarning.toArgb()
                val background = warning.toArgb()
                assertTrue("Visible status glyph", pixels(image, Rect(status.left - dp(33), status.top - dp(4), status.left - dp(3), status.bottom + dp(4)), ink) > 0)
                assertTrue("Rendered status text", pixels(image, status, ink) > 0)
                assertTrue("Status text contrast", ColorUtils.calculateContrast(ink, background) >= 4.5)
                assertEquals("Empty state is neutral", scheme.surface.toArgb(), image.getPixel(dp(20), empty.centerY()))
            }
            assertEquals(FontFamily.Serif, textLayout(scenario, "План чтения").layoutInput.style.fontFamily)
            assertNotEquals(FontFamily.Serif, textLayout(scenario, "Читать главу каждый день").layoutInput.style.fontFamily)
            preview(scenario, PresentationFixtures.habit, dark)
            val number = textLayout(scenario, "7").layoutInput.style
            val label = textLayout(scenario, "Дней подряд").layoutInput.style
            // The technical preset keeps the body font and aligns digits with tabular figures.
            assertNotEquals(FontFamily.Monospace, number.fontFamily)
            assertEquals("tnum", number.fontFeatureSettings)
            assertTrue("The result is the focal text", number.fontSize.value > label.fontSize.value)
            assertNotEquals(FontFamily.Monospace, textLayout(scenario, "Готово").layoutInput.style.fontFamily)
        }
    }

    @Test fun horizontalButtonsRespectVisibilityAvailabilityAndExplicitVariants() = withPreview { scenario ->
        robot.rotate(scenario, landscape = false)
        val scheme = preview(scenario, PresentationFixtures.actionGroup, dark = false)
        assertNull(robot.find("Скрыто"))
        assertNull(robot.find("Скрытый приоритет"))
        for (label in listOf("Недоступно", "Недоступный приоритет")) {
            assertTrue(label, generateSequence(robot.waitFor(label)) { it.parent }.any { !it.isEnabled })
        }
        val primary = robot.assertTarget("Продолжить")
        val secondary = robot.assertTarget("Сбросить")
        capture("implicit-actions") { image ->
            assertTrue("First visible enabled ordinary button is filled", isFilled(image, primary, scheme))
            assertFalse("Later ordinary peer is secondary", isFilled(image, secondary, scheme))
        }
        preview(scenario, PresentationFixtures.explicitGroup, dark = false)
        val explicit = robot.assertTarget("Сохранить")
        val peer = robot.assertTarget("Обновить")
        val lone = robot.assertTarget("Проверить")
        capture("explicit-actions") { image ->
            assertTrue("The explicit filled variant stays primary", isFilled(image, explicit, scheme))
            assertFalse("Explicit primary reserves emphasis", isFilled(image, peer, scheme))
            assertTrue("A lone visible ordinary button stays filled", isFilled(image, lone, scheme))
        }
    }

    private fun appColors(preset: String, dark: Boolean) = checkNotNull(ToolPalettes.scheme(presetPalette(preset), dark))

    private fun dp(value: Int) = (robot.instrumentation.targetContext.resources.displayMetrics.density * value).toInt()

    private fun isFilled(image: Bitmap, bounds: Rect, scheme: ColorScheme): Boolean =
        pixels(image, Rect(bounds.left + dp(8), bounds.centerY() - dp(2), bounds.left + dp(16), bounds.centerY() + dp(2)), scheme.primary.toArgb()) > dp(8)

    private fun pixels(image: Bitmap, bounds: Rect, color: Int): Int {
        assertTrue(Rect(0, 0, image.width, image.height).contains(bounds))
        return (bounds.top until bounds.bottom).sumOf { y ->
            (bounds.left until bounds.right).count { x -> image.getPixel(x, y) == color }
        }
    }

    private fun capture(stem: String, inspect: (Bitmap) -> Unit = {}) {
        robot.awaitFrames(2)
        val image = checkNotNull(robot.automation.takeScreenshot())
        try {
            File(output, "$stem.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            inspect(image)
        } finally { image.recycle() }
    }

    private fun textLayout(scenario: ActivityScenario<CloudTestActivity>, label: String): TextLayoutResult {
        lateinit var result: TextLayoutResult
        scenario.onActivity { activity ->
            val owner = activity.window.decorView.allViews.filterIsInstance<ViewRootForTest>().single().semanticsOwner
            val node = owner.getAllSemanticsNodes(mergingEnabled = false).first {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true
            }
            val layout = mutableListOf<TextLayoutResult>()
            assertTrue(checkNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(layout))
            result = layout.single()
        }
        return result
    }

    private fun textPosition(scenario: ActivityScenario<CloudTestActivity>, label: String): Rect {
        val result = Rect()
        scenario.onActivity { activity ->
            val owner = activity.window.decorView.allViews.filterIsInstance<ViewRootForTest>().single().semanticsOwner
            val node = owner.getAllSemanticsNodes(mergingEnabled = false).first {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true
            }
            val coordinates = node.layoutInfo.coordinates
            val top = coordinates.localToRoot(Offset.Zero)
            result.set(top.x.toInt(), top.y.toInt(), top.x.toInt() + coordinates.size.width, top.y.toInt() + coordinates.size.height)
        }
        return result
    }

    private fun preview(scenario: ActivityScenario<CloudTestActivity>, scene: PresentationFixtures.Scene, dark: Boolean,
        wide: Boolean = false): ColorScheme {
        val spec = scene.spec()
        val colors = galleryWallpaperColors("green", dark)
        // Each capture opens a new preview. Rotation must not restore the record check's scroll offset.
        val viewport = ScrollState(0)
        val laidOut = AtomicBoolean()
        scenario.onActivity { activity ->
            activity.setContent {
                AlphaTheme {
                    MaterialTheme(colorScheme = colors) {
                        Surface(Modifier.fillMaxSize(), color = colors.surface) {
                            Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                                key(scene.id, dark) {
                                    val values = spec.initialState
                                    Column(Modifier.widthIn(max = if (wide) 640.dp else 320.dp).fillMaxWidth()
                                        .verticalScroll(viewport).padding(16.dp)
                                        .onGloballyPositioned { laidOut.set(true) }) {
                                        Text(scene.title, style = MaterialTheme.typography.headlineLarge)
                                        ToolContent(spec.ui.toString(), values, true, heading = scene.title) { _, _ -> }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        robot.diagnostics.target(scene.probe)
        robot.waitUntil { laidOut.get() }
        scenario.onActivity {
            assertEquals("Opening scroll offset for ${scene.id}, dark=$dark, wide=$wide",
                0, viewport.value)
        }
        robot.waitUntil { robot.find(scene.probe)?.isVisibleToUser == true }
        return colors
    }

    private fun revealInPreview(label: String): AccessibilityNodeInfo {
        robot.diagnostics.target(label)
        val node = robot.find(label)
        // Compose can accept Show on screen even when the preview fits and cannot scroll.
        if (node?.isVisibleToUser == true && generateSequence(node.parent) { it.parent }.none { it.isScrollable }) return node
        return robot.reveal(label)
    }

    private fun withPreview(block: (ActivityScenario<CloudTestActivity>) -> Unit) {
        val fixture = ShellFixture("presentation-preview")
        try {
            fixture.install()
            fixture.launch().use(block)
        } finally { fixture.remove() }
    }

}

private object PresentationFixtures {
    data class Scene(val id: String, val title: String, val probe: String, val ui: String,
        val schema: String = """{"ready":"boolean"}""", val state: String = """{"ready":true}""",
        val source: String = "return {state:input.state};") {
        fun spec() = ToolSpec.parse(JSONObject().put("formatVersion", 3).put("title", title)
            .put("description", "").put("capabilities", JSONArray()).put("ui", JSONObject(ui))
            .put("stateSchema", JSONObject(schema)).put("initialState", JSONObject(state)).put("source", source).toString())
    }

    val counter = Scene("counter", "Счётчик поездок", "Поездок", """
        {"type":"section","text":"Счётчик поездок","children":[
          {"type":"metric","text":"Поездок","bind":"count"},
          {"type":"row","children":[{"type":"button","text":"Прибавить","action":"add"},{"type":"button","text":"Сбросить","action":"reset"}]}
        ]}""", """{"count":"number"}""", """{"count":12}""")
    val budget = Scene("budget", "Бюджет", "Расходы месяца", """
        {"type":"column","children":[
          {"type":"section","text":"Расходы месяца","style":{"tone":"accent"},"children":[{"type":"text","text":"Запланировано 4 000 ₽"}]},
          {"type":"card","text":"Лимит на неделю","style":{"tone":"accent"},"children":[{"type":"text","text":"Осталось 750 ₽"}]}
        ]}""")
    val records = Scene("records", "Расходы", "Продукты", """
        {"type":"list","text":"Последние записи","bind":"rows","action":"pick",
         "columns":[{"text":"Название","field":"name"},{"text":"Сумма","field":"amount"}],
         "rowActions":[{"text":"Отметить","action":"mark","enabledField":"available"}]}
        """, """{"rows":{"type":"list","maxItems":10,"fields":{"id":"string","name":"string","amount":"number","available":"boolean"}},"last":"string"}""",
        """{"rows":[{"id":"food","name":"Продукты","amount":420,"available":true},{"id":"bus","name":"Транспорт","amount":90,"available":false}],"last":""}""",
        "if (input.event.type==='action') input.state.last=input.event.actionId+':'+input.event.value; return {state:input.state};")
    val reading = Scene("reading", "Чтение", "План чтения", """
        {"type":"theme","preset":"editorial","children":[
          {"type":"section","text":"План чтения","children":[{"type":"text","text":"Читать главу каждый день"},{"type":"badge","text":"Пауза","style":{"tone":"warning"}}]},
          {"type":"emptyState","text":"Нет заметок","children":[{"type":"text","text":"Добавьте первую запись"}]}
        ]}""")
    val habit = Scene("habit", "Привычки", "Дней подряд", """
        {"type":"theme","preset":"technical","children":[
          {"type":"metric","text":"Дней подряд","bind":"streak"},
          {"type":"text","text":"Читать утром"},
          {"type":"button","text":"Готово","action":"done"}
        ]}""", """{"streak":"number"}""", """{"streak":7}""")
    val actionGroup = Scene("implicit", "Действия", "Продолжить", """
        {"type":"flowRow","children":[
          {"type":"button","text":"Скрыто","action":"hidden","visibleBind":"shown"},
          {"type":"button","text":"Скрытый приоритет","action":"hiddenPrimary","visible":false,"variant":"filled"},
          {"type":"button","text":"Недоступно","action":"disabled","enabledBind":"shown"},
          {"type":"button","text":"Недоступный приоритет","action":"disabledPrimary","enabled":false,"variant":"filled"},
          {"type":"button","text":"Продолжить","action":"continue"},
          {"type":"button","text":"Сбросить","action":"reset"}
        ]}""", """{"shown":"boolean"}""", """{"shown":false}""")
    val explicitGroup = Scene("explicit", "Команды", "Сохранить", """
        {"type":"column","children":[
          {"type":"row","children":[
            {"type":"button","text":"Сохранить","action":"save","variant":"filled"},
            {"type":"button","text":"Обновить","action":"refresh"},
            {"type":"button","text":"Подробнее","action":"details","variant":"text"}
          ]},
          {"type":"row","children":[
            {"type":"button","text":"Проверить","action":"check"},
            {"type":"button","text":"Скрыто","action":"hidden","visible":false}
          ]}
        ]}""")
    val comparison = listOf(counter, budget, records, reading, habit)
}
