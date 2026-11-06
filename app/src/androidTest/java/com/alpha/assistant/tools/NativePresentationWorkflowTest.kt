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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.alpha.assistant.R
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.galleryWallpaperColors
import com.alpha.assistant.tools.generation.NeedsPlan
import com.alpha.assistant.tools.generation.NativePresentation
import com.alpha.assistant.tools.generation.NativePresentationProfile
import com.alpha.assistant.tools.generation.PartsGeneration
import com.alpha.assistant.tools.generation.ToolComposition
import com.alpha.assistant.ui.AlphaTheme
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
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
                val ink = scheme.onTertiaryContainer.toArgb()
                val background = scheme.tertiaryContainer.toArgb()
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
            assertEquals(FontFamily.Monospace, number.fontFamily)
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

    @Test fun recipeProfilesKeepSourceOrderAndUseTheAvailableReadingAndWorkspaceWidth() {
        val scene = PresentationFixtures.recipe
        val before = scene.spec()
        val font = robot.shell("settings get system font_scale")
        val captures = JSONArray()
        try {
            for (scale in listOf(1f, 2f)) {
                robot.shell("settings put system font_scale $scale")
                withPreview { scenario ->
                    for (wide in listOf(false, true)) {
                        robot.rotate(scenario, landscape = wide)
                        robot.waitUntil {
                            var effective = 0f
                            scenario.onActivity { effective = it.resources.configuration.fontScale }
                            effective == scale
                        }
                        for (dark in listOf(false, true)) {
                            preview(scenario, scene, dark, wide)
                            val baseline = textLayout(scenario, "12").layoutInput.style.fontSize.value
                            val originalMetric = textPosition(scenario, "Очки")
                            val originalContext = textPosition(scenario, "Подробности")
                            assertTrue(originalContext.top > originalMetric.bottom)
                            revealInPreview("Музей")
                            val baselineRecord = robot.assertTarget("Музей").width()
                            for (profile in NativePresentationProfile.entries) {
                                val recipe = recipe(before, profile)
                                val colors = preview(scenario, scene, dark, wide, recipe)
                                val score = textPosition(scenario, "Очки")
                                val context = textPosition(scenario, "Подробности")
                                val name = "recipe-${profile.wire}-${if (dark) "dark" else "light"}-${if (wide) "wide" else "narrow"}-${(scale * 100).toInt()}"
                                assertEquals(1, robot.texts().count { it.trim() == scene.title })
                                if (profile == NativePresentationProfile.WORKSPACE && wide && scale == 1f) {
                                    assertTrue("Adjacent slots share a row: $score and $context", context.left > score.left && context.top < score.bottom)
                                } else assertTrue("Slots follow their source order: $score and $context", context.top > score.bottom)
                                val value = textLayout(scenario, "12").layoutInput.style.fontSize.value
                                assertEquals(FontFamily.Serif, textLayout(scenario, "Подробности").layoutInput.style.fontFamily)
                                if (profile == NativePresentationProfile.FOCUS) assertTrue("Focus enlarges the metric", value > baseline)
                                else assertEquals(baseline, value, 0f)
                                capture(name) { image ->
                                    if (profile == NativePresentationProfile.FOCUS) {
                                        val visibleScore = robot.bounds("Очки")
                                        assertTrue("Focus uses a visible wallpaper accent",
                                            pixels(image, Rect(visibleScore.left, visibleScore.top - dp(24),
                                                visibleScore.left + dp(48), visibleScore.top), colors.secondary.toArgb()) > 0)
                                    }
                                }
                                revealInPreview("Музей")
                                val width = robot.assertTarget("Музей").width()
                                if (profile == NativePresentationProfile.READING && wide) {
                                    assertTrue("Reading uses a narrower content width", width < baselineRecord)
                                }
                                if (profile == NativePresentationProfile.WORKSPACE) {
                                    assertEquals("The workspace collection uses the whole row", baselineRecord, width)
                                }
                                captures.put(JSONObject().put("file", "$name.png").put("profile", profile.wire)
                                    .put("fontScale", scale.toDouble()).put("wide", wide).put("dark", dark))
                            }
                        }
                    }
                }
            }
            assertEquals(24, captures.length())
            assertEquals("Rendering preserves the validated spec", before.json, scene.spec().json)
        } finally {
            if (font == "null") robot.shell("settings delete system font_scale")
            else robot.shell("settings put system font_scale $font")
            File(output, "recipes.json").writeText(captures.toString(2))
            assertEquals(font, robot.shell("settings get system font_scale"))
        }
    }

    @Test fun recipePrimaryTracksCurrentAvailabilityAndLeavesModalActionsInTheirOwnGroup() = withPreview { scenario ->
        robot.rotate(scenario, landscape = false)
        val scene = PresentationFixtures.recipe
        val actions = CopyOnWriteArrayList<Pair<String, Any?>>()
        val spec = scene.spec()
        val colors = preview(scenario, scene, dark = false, presentation = recipe(spec, NativePresentationProfile.FOCUS)) { id, value, current ->
            actions += id to value
            when (id) {
                "advance" -> current.put("points", current.getInt("points") + 1)
                "show" -> current.put("shown", !current.getBoolean("shown"))
                "allow" -> current.put("allowed", !current.getBoolean("allowed"))
                "modal" -> current.put("dialog", true)
                "close" -> current.put("dialog", false)
            }
        }
        val palette = colors.copy(primary = colors.secondary)
        fun filled(label: String, expected: Boolean, stem: String) {
            revealInPreview(label)
            val target = robot.assertTarget(label)
            capture(stem) { image -> assertEquals(label, expected, isFilled(image, target, palette)) }
        }
        filled("Продолжить", true, "recipe-primary")
        filled("Сбросить", false, "recipe-secondary")
        robot.click("Продолжить")
        robot.waitFor("13")
        assertEquals(listOf("advance" to null), actions.toList())
        revealInPreview("Видимость")
        robot.click("Видимость")
        robot.waitGone("Продолжить")
        filled("Сбросить", true, "recipe-hidden-fallback")
        revealInPreview("Видимость")
        robot.click("Видимость")
        revealInPreview("Доступность")
        robot.click("Доступность")
        robot.waitUntil {
            robot.find("Продолжить")?.let { button -> generateSequence(button) { it.parent }.any { !it.isEnabled } } == true
        }
        filled("Сбросить", true, "recipe-disabled-fallback")
        revealInPreview("Доступность")
        robot.click("Доступность")
        revealInPreview("Проверка")
        val paneWindow = checkNotNull(robot.diagnostics.freshRoot()).windowId
        robot.click("Проверка")
        val dialogTitle = "Проверка рецепта"
        robot.diagnostics.target(dialogTitle)
        robot.waitUntil {
            val title = robot.find(dialogTitle)?.takeIf { it.isVisibleToUser && it.windowId != paneWindow }
                ?: return@waitUntil false
            val bounds = Rect().also { title.getBoundsInScreen(it) }
            // The dialog's semantics arrive before the Android window becomes opaque.
            val image = robot.automation.takeScreenshot() ?: return@waitUntil false
            try {
                image.getPixel(bounds.left - dp(8), bounds.centerY()) == colors.surfaceContainerHigh.toArgb()
            } finally { image.recycle() }
        }
        filled("Внутри", true, "recipe-modal-primary")
        filled("Позже", false, "recipe-modal-secondary")
        robot.back()
        robot.waitGone(dialogTitle)
        revealInPreview("13")
        assertEquals(1, actions.count { it.first == "advance" })
        assertEquals(1, actions.count { it.first == "close" })
    }

    private fun recipe(spec: ToolSpec, profile: NativePresentationProfile): NativePresentation =
        NativePresentation.parse(NativePresentation.json(profile, "editorial", 0, "advance"), spec.ui)

    @Test fun mixedCounterAndRecordActionsSaveAndReopenThroughTheNativeHost() {
        val canonical = runBlocking { PresentationFixtures.mixedCounter() }
        assertNotNull(canonical.composition)
        assertEquals(canonical.json, ToolSpec.parsePersisted(canonical.json).json)
        withSaved(canonical) { scenario, tools, fixture ->
            robot.reveal("Прибавить")
            val add = robot.assertTarget("Прибавить")
            val reset = robot.assertTarget("Сбросить")
            capture("mixed-counter") { image ->
                assertNotEquals("Counter controls have distinct presentation",
                    image.getPixel(add.left + dp(10), add.centerY()), image.getPixel(reset.left + dp(10), reset.centerY()))
            }
            robot.click("Прибавить")
            robot.waitUntil { fixture.model?.tools?.busy == false && saved(tools).getInt("p5_count_value") == 1 }
            scenario.recreate()
            robot.reveal("Сбросить")
            assertEquals(1, saved(tools).getInt("p5_count_value"))
            robot.click("Сбросить")
            robot.waitUntil { fixture.model?.tools?.busy == false && saved(tools).getInt("p5_count_value") == 0 }
            assertEquals(canonical.json, checkNotNull(tools.tool("presentation")).spec)
        }
        withSaved(PresentationFixtures.records.spec()) { scenario, tools, fixture ->
            robot.reveal("Продукты")
            robot.assertTarget("Продукты")
            robot.click("Продукты")
            robot.waitUntil { fixture.model?.tools?.busy == false && saved(tools).getString("last") == "pick:food" }
            robot.reveal("Отметить")
            robot.assertTarget("Отметить")
            robot.click("Отметить")
            robot.waitUntil { fixture.model?.tools?.busy == false && saved(tools).getString("last") == "mark:food" }
            scenario.recreate()
            robot.reveal("Транспорт")
            assertEquals("mark:food", saved(tools).getString("last"))
        }
    }

    private fun saved(tools: ToolStore) = JSONObject(checkNotNull(tools.tool("presentation")).state)
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
        wide: Boolean = false, presentation: NativePresentation? = null, onAction: ((String, Any?, JSONObject) -> Unit)? = null): ColorScheme {
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
                                key(scene.id, dark, presentation?.profile) {
                                    var values by remember { mutableStateOf(spec.initialState) }
                                    Column(Modifier.widthIn(max = if (wide) 640.dp else 320.dp).fillMaxWidth()
                                        .verticalScroll(viewport).padding(16.dp)
                                        .onGloballyPositioned { laidOut.set(true) }) {
                                        Text(scene.title, style = MaterialTheme.typography.headlineLarge)
                                        ToolContent(spec.ui.toString(), values, true, heading = scene.title, presentation = presentation) { id, value ->
                                            onAction?.let { dispatch ->
                                                val current = JSONObject(values)
                                                dispatch(id, value, current)
                                                values = current.toString()
                                            }
                                        }
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
            assertEquals("Opening scroll offset for ${scene.id}/${presentation?.profile}, dark=$dark, wide=$wide",
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

    private fun withSaved(spec: ToolSpec, block: (ActivityScenario<CloudTestActivity>, ToolStore, ShellFixture) -> Unit) {
        val fixture = ShellFixture("presentation-saved")
        try {
            ToolStore(fixture.context, "${fixture.name}.db-tools.db").use { tools ->
                tools.saveTool(ToolRecord("presentation", 1, 1, spec.json, spec.initialState, spec.ui.toString(), "chat", true), true)
                fixture.store.saveConversation(Conversation("chat", "Галерея", 1, 1, messages = listOf(
                    Message("artifact", "assistant", "", 1, "complete", toolId = "presentation", toolRevision = 1),
                )))
                fixture.store.saveSetting("selected", "chat")
                fixture.install()
                fixture.launch().use { scenario ->
                    robot.rotate(scenario, landscape = false)
                    robot.reveal(robot.text(R.string.tool_open))
                    robot.click(robot.text(R.string.tool_open))
                    robot.waitFor(robot.text(R.string.tool_content))
                    robot.waitUntil { fixture.model?.tools?.busy == false }
                    block(scenario, tools, fixture)
                    assertEquals(0, fixture.provider.calls)
                }
            }
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
    val recipe = Scene("recipe", "Рецепт поездки", "Очки", """
        {"type":"column","children":[
          {"type":"section","text":"Рецепт поездки","children":[
            {"type":"metric","text":"Очки","bind":"points"},
            {"type":"row","children":[
              {"type":"button","text":"Сбросить","action":"reset"},
              {"type":"button","text":"Продолжить","action":"advance","visibleBind":"shown","enabledBind":"allowed"}
            ]}
          ]},
          {"type":"section","text":"Подробности","children":[{"type":"text","text":"Короткое пояснение"}]},
          {"type":"row","children":[
            {"type":"button","text":"Видимость","action":"show"},
            {"type":"button","text":"Доступность","action":"allow"},
            {"type":"button","text":"Проверка","action":"modal"}
          ]},
          {"type":"list","text":"Остановки","bind":"places","columns":[{"text":"Название","field":"name"}],"action":"place"},
          {"type":"dialog","text":"Проверка рецепта","visibleBind":"dialog","dismissAction":"close","children":[
            {"type":"row","children":[
              {"type":"button","text":"Внутри","action":"inside"},
              {"type":"button","text":"Позже","action":"later"}
            ]}
          ]}
        ]}""",
        """{"points":"number","shown":"boolean","allowed":"boolean","dialog":"boolean","places":{"type":"list","maxItems":4,"fields":{"id":"string","name":"string"}}}""",
        """{"points":12,"shown":true,"allowed":true,"dialog":false,"places":[{"id":"museum","name":"Музей"}]}""")

    suspend fun mixedCounter(): ToolSpec {
        val plan = NeedsPlan.parse("""
            {"title":"Счётчик поездок","description":"Локальный счётчик","language":"ru","inputs":[],"constants":[],"needs":[
              {"id":"count","type":"counter","label":"Поездок","description":"Увеличение и сброс"},
              {"id":"notes","type":"action","label":"Справка","description":"Текст о поездках"}
            ],"questions":[]}
        """.trimIndent())
        val assembly = PartsGeneration.build(plan) { raw ->
            val questions = JSONObject(raw).getJSONArray("questions")
            val answers = JSONArray()
            repeat(questions.length()) { index ->
                answers.put(JSONObject().put("id", questions.getJSONObject(index).getString("id"))
                    .put("choice", "A").put("confidence", 1).put("probabilities", JSONObject().put("A", 1).put("B", 0)))
            }
            JSONObject().put("answers", answers).toString()
        }
        return ToolComposition.complete(assembly, """
            {"stateSchema":{},"initialState":{},"capabilities":[],"source":"return {state:input.state};",
             "ui":{"type":"column","children":[{"type":"section","text":" Счётчик поездок ",
                "children":[{"type":"text","text":"Учитывайте завершённые поездки"}]}]}}
        """.trimIndent())
    }
}
