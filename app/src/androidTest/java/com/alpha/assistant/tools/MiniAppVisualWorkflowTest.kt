package com.alpha.assistant.tools

import com.alpha.assistant.ui.seedOpenChat
import android.accessibilityservice.AccessibilityServiceInfo
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Build
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inspector.WindowInspector
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.GallerySamples
import com.alpha.assistant.GalleryToneLabels
import com.alpha.assistant.MiniAppGalleryActivity
import com.alpha.assistant.R
import com.alpha.assistant.galleryWallpaperColors
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.ui.AlphaDarkColors
import com.alpha.assistant.ui.AlphaLightColors
import com.alpha.assistant.ui.alphaColorScheme
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MiniAppVisualWorkflowTest {
    private val robot = ShellRobot("plan3-visual")

    @Test fun unavailableSavedThemesStayInLibraryUntilExplicitDeletion() {
        val fixture = ShellFixture("visual-saved-themes")
        try {
            ToolStore(fixture.context, "${fixture.name}.db-tools.db").use { tools ->
                val records = listOf("palette", "mode").map { field ->
                    val title = "Fixture ${field.uppercase()}"
                    val theme = JSONObject("""{"type":"theme","preset":"clean","children":[{"type":"text","text":"Generated fixture"}]}""")
                        .put(field, if (field == "mode") "system" else JSONObject("""{"primary":"#FFFFFF","onPrimary":"#000000"}"""))
                    val raw = JSONObject().put("formatVersion", 3).put("title", title).put("description", "Fixture")
                        .put("capabilities", org.json.JSONArray()).put("stateSchema", JSONObject("""{"count":"number"}""")).put("initialState", JSONObject("""{"count":7}"""))
                        .put("ui", theme).put("source", "return {state:input.state};")
                    val oldSpec = raw.toString()
                    val oldUi = theme.toString()
                    theme.remove(field)
                    val supported = ToolSpec.parse(raw.toString())
                    val record = ToolRecord(field, 1, 1, supported.json, supported.initialState, supported.ui.toString(), "chat", true)
                    tools.saveTool(record, true)
                    // Seed the historical payload after a supported save. The current write path rejects it.
                    tools.writableDatabase.execSQL("UPDATE tools SET spec=?, ui=? WHERE id=?", arrayOf(oldSpec, oldUi, field))
                    tools.writableDatabase.execSQL("UPDATE revisions SET spec=?, ui=? WHERE tool_id=?", arrayOf(oldSpec, oldUi, field))
                    record.copy(spec = oldSpec, ui = oldUi)
                }
                fixture.store.saveConversation(Conversation("chat", "Fixtures", 1, 1, messages = records.map {
                    Message(it.id, "assistant", "", 1, "complete", toolId = it.id, toolRevision = 1)
                }))
                fixture.store.seedOpenChat("chat")
                fixture.install()
                fixture.launch().use { scenario ->
                    robot.waitFor(robot.text(R.string.tool_closed_format))
                    assertNull(robot.find("Generated fixture"))
                    robot.openDrawer(scenario)
                    robot.click(robot.text(R.string.mini_apps))
                    robot.awaitDrawerClosed(scenario)
                    robot.reveal("Fixture MODE")
                    robot.click("Fixture MODE")
                    robot.waitFor(robot.text(R.string.tool_closed_format_detail))
                    scenario.recreate()
                    robot.waitFor(robot.text(R.string.tool_closed_format_detail))
                    for (record in records) {
                        assertNull("The theme must fail current validation", ToolSpec.available(record.spec))
                        assertEquals(record.spec, tools.tool(record.id)?.spec)
                        assertEquals(record.state, tools.tool(record.id)?.state)
                        assertEquals(1, tools.revisions(record.id).size)
                    }
                    robot.click(robot.text(R.string.tool_options))
                    robot.click(robot.text(R.string.delete))
                    robot.waitFor(robot.text(R.string.tool_delete_title, "Fixture MODE"))
                    assertNotNull(tools.tool("mode"))
                    robot.click(robot.text(R.string.delete))
                    robot.waitUntil { tools.tool("mode") == null }
                    assertNotNull(tools.tool("palette"))
                    assertEquals(0, fixture.provider.calls)
                }
            }
        } finally { fixture.remove() }
    }

    @Test fun libraryHostUsesRussianActions() {
        val fixture = ShellFixture("visual-russian")
        try {
            fixture.install()
            fixture.launch().use { scenario ->
                robot.openDrawer(scenario)
                robot.click("Мини-приложения")
                robot.awaitDrawerClosed(scenario)
                robot.waitFor(robot.text(R.string.tool_catalog_empty))
                robot.waitFor("Поиск мини-приложений")
                assertNull(robot.find("Create preview"))
                assertNull(robot.find("Library"))
            }
        } finally { fixture.remove() }
    }

    @Test fun galleryFixturesCoverTheCurrentCatalog() {
        val covered = GallerySamples.values.flatMap { sample ->
            sample.spec()
            sample.sections.flatMap { section -> sample.componentTypes(section.id) }
        }.toSet()
        assertEquals(ToolUiCatalog.components.keys, covered)
    }

    @Test fun gallerySectionsFitCompactAndExpandedViewports() =
        captureGallery(listOf("fixed"), listOf(false), "plan3-gallery-section-smoke", collectFailures = true)

    @Test fun galleryShowsReadableRolesAndNativeControls() =
        captureGallery(listOf("fixed", "device", "blue", "green"), listOf(false, true), "plan3-gallery-sections", collectFailures = true)

    @Test fun gallerySnackbarActionStaysSeparateAndReadable() =
        captureGallery(listOf("device"), listOf(true), "alpha3-gallery-snackbar", onlySections = listOf("presentation/snackbar"),
            widths = listOf(true))

    @Test fun galleryCompactTimePickerAcrossSchemes() =
        captureGallery(listOf("fixed", "device", "blue", "green"), listOf(false, true), "plan3-gallery-time-picker-delta",
            onlySections = listOf("forms/time"), widths = listOf(true))

    @Test fun galleryGridShowsEveryCharacterAtDoubleFont() =
        captureGallery(listOf("blue"), listOf(false), "plan3-gallery-grid-visibility", onlySections = listOf("records/lazy-grid"), widths = listOf(false))

    @Test fun galleryGridAndDateRangeAcrossSchemes() =
        captureGallery(listOf("fixed", "device", "blue", "green"), listOf(false, true), "plan3-gallery-visibility-delta",
            onlySections = listOf("records/lazy-grid", "forms/date-range"))

    @Test fun galleryDateRangeChangesAtDoubleFontInLandscape() =
        captureGallery(listOf("green"), listOf(true), "plan3-gallery-date-range-system-font",
            onlySections = listOf("forms/date-range"), widths = listOf(true), fonts = listOf(2f))

    @Test fun galleryDateRangeChangesAtDoubleFontInPortrait() =
        captureGallery(listOf("green"), listOf(true), "plan3-gallery-date-range-portrait-system-font",
            onlySections = listOf("forms/date-range"), widths = listOf(false), fonts = listOf(2f))

    @Test fun galleryDateRangeAcceptsSoftwareKeyboardAtSystemFont200() {
        val app = robot.instrumentation.targetContext
        val directory = File(app.getExternalFilesDir(null), "plan3-gallery-date-range-ime").apply { mkdirs() }
        val original = robot.shell("settings get system font_scale")
        val service = robot.automation.serviceInfo
        val originalFlags = service.flags
        val report = JSONArray()
        val failures = mutableListOf<String>()
        try {
            service.flags = originalFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            robot.automation.serviceInfo = service
            robot.shell("settings put system font_scale 2.0")
            withoutAnimations {
                for (landscape in listOf(false, true)) {
                    val mode = if (landscape) "landscape" else "portrait"
                    val states = JSONArray()
                    report.put(JSONObject().put("orientation", mode).put("states", states))
                    val intent = Intent(app, MiniAppGalleryActivity::class.java).putExtra("matrix", true)
                        .putExtra("systemFont", true).putExtra("scheme", "green").putExtra("dark", true)
                        .putExtra("page", "forms").putExtra("expanded", landscape)
                    ActivityScenario.launch<MiniAppGalleryActivity>(intent).use { scenario ->
                        robot.rotate(scenario, landscape)
                        try { editDateRangeWithKeyboard(scenario, directory, states, mode) }
                        catch (failure: AssertionError) { failures += "$mode: ${failure.message}" }
                    }
                }
            }
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        } finally {
            service.flags = originalFlags
            robot.automation.serviceInfo = service
            if (original == "null") robot.shell("settings delete system font_scale")
            else robot.shell("settings put system font_scale $original")
            val restored = robot.shell("settings get system font_scale")
            File(directory, "matrix.json").writeText(JSONObject().put("previousSystemFontScale", original)
                .put("restoredSystemFontScale", restored).put("orientations", report).toString(2))
            assertEquals("System font scale restored", original, restored)
        }
    }

    private fun editDateRangeWithKeyboard(scenario: ActivityScenario<MiniAppGalleryActivity>, directory: File,
        states: JSONArray, mode: String) {
        lateinit var safe: Rect
        lateinit var labels: List<String>
        lateinit var check: GalleryCaptureCheck
        lateinit var invalidRange: String
        scenario.onActivity {
            assertEquals(2f, it.resources.configuration.fontScale)
            safe = safeArea(it)
            labels = listOf(R.string.tool_date_range_start, R.string.tool_date_range_end).map(it::getString)
            invalidRange = it.getString(R.string.tool_date_range_invalid)
            check = checkNotNull(galleryCaptureChecks(it).getValue("forms/date-range").next).check
            it.section = "date-range"
        }
        val colors = galleryWallpaperColors("green", true)
        val surface = colors.surface.toArgb()
        val original = "Дни доставки: 2028-06-03 – 2028-06-05"
        val applied = "Дни доставки: 2028-06-08 – 2028-06-11"
        val apply = robot.text(R.string.tool_generated_apply, "Дни доставки")
        val cancel = robot.text(R.string.cancel)
        fun captureDialog(state: String) {
            capture(states, directory, "$mode-$state",
                check.copy(body = check.body + labels, unclipped = labels), safe, surface,
                dateFields = reachableDateRangeFields(safe), dateInk = colors.onSurface.toArgb() to colors.onSurfaceVariant.toArgb(),
                expectedFontScale = 2f)
        }
        fun type(field: AccessibilityNodeInfo, digits: String, state: String) {
            typeDateWithKeyboard(field, digits, "$mode-$state", safe, directory, states, colors.onSurface.toArgb())
        }
        tap(robot.assertTarget(original))
        captureDialog("open")
        type(reachableDateRangeFields(safe).last(), "01062028", "invalid-end-ime")
        visible(apply, disabled = true)
        visible(invalidRange, textOnly = true)
        type(reachableDateRangeFields(safe).last(), "11062028", "corrected-end-ime")
        type(reachableDateRangeFields(safe).first(), "08062028", "new-start-ime")
        robot.diagnostics.target(apply)
        robot.waitUntil {
            val action = generateSequence(findVisible(apply, textOnly = false)) { it.parent }.firstOrNull { it.isClickable }
            action?.isEnabled == true
        }
        captureDialog("ready-to-apply")
        tap(robot.assertTarget(apply))
        capture(states, directory, "$mode-applied", GalleryCaptureCheck(targets = listOf(applied)), safe, surface, expectedFontScale = 2f)
        tap(robot.assertTarget(applied))
        tap(robot.assertTarget(cancel))
        robot.assertTarget(applied)
    }

    private fun typeDateWithKeyboard(field: AccessibilityNodeInfo, digits: String, state: String, safe: Rect,
        directory: File, states: JSONArray, ink: Int) {
        val previousDigits = field.text.toString().count(Char::isDigit)
        val appWindowId = field.windowId
        val fieldBounds = field.screenBounds()
        val inset = (18 * robot.instrumentation.targetContext.resources.displayMetrics.density).toInt()
        // Tap after the existing digits so the Backspace key deletes from the end.
        robot.shell("input tap ${fieldBounds.right - inset} ${fieldBounds.centerY()}")
        robot.waitUntil { keyboardWindow() != null }
        val keyboard = checkNotNull(keyboardWindow())
        val keyboardBounds = Rect().also(keyboard::getBoundsInScreen)
        val record = JSONObject().put("state", state)
            .put("keyboardBoundsPx", keyboardBounds.toShortString()).put("windowFontScale", 2).put("complete", false)
        states.put(record)
        fun focusedField() = robot.automation.windows.firstOrNull { it.id == appWindowId }?.root?.let(::descendants)
            ?.firstOrNull { it.isEditable && it.isFocused && it.isVisibleToUser }
        fun captureValue(stage: String) {
            awaitRenderedWindow(appWindowId, 2f)
            val screenshot = checkNotNull(robot.automation.takeScreenshot())
            try {
                val file = "$state-$stage.png"
                screenshot.save(File(directory, file))
                record.put("${stage}File", file)
                val focused = focusedField() ?: throw AssertionError("Focused date field in $state")
                val unobscured = Rect(safe).apply { bottom = minOf(bottom, keyboardBounds.top) }
                record.put("${stage}Value", dateFieldPixels(screenshot, listOf(focused), ink to ink, 2f, emptyList(), unobscured))
            } finally { screenshot.recycle() }
        }
        captureValue("before")
        val keys = checkNotNull(keyboardWindow()?.root).let(::descendants).filter { it.isVisibleToUser }.toList()
        val keyLabels = keys.mapNotNull { (it.contentDescription ?: it.text)?.toString() }.distinct()
        record.put("keyboardLabels", JSONArray(keyLabels))
        fun press(label: String) {
            val key = keys.firstOrNull { (it.contentDescription ?: it.text)?.toString().equals(label, ignoreCase = true) }
                ?: throw AssertionError("IME key $label is absent from $keyLabels")
            tap(key.screenBounds())
        }
        val delete = keyLabels.firstOrNull { it.equals("Delete", ignoreCase = true) || it.equals("Backspace", ignoreCase = true) }
            ?: throw AssertionError("IME delete key is absent from $keyLabels")
        repeat(previousDigits) { press(delete) }
        for (digit in digits) press(digit.toString())
        try {
            robot.waitUntil { focusedField()?.text?.toString()?.filter(Char::isDigit) == digits }
        } catch (failure: AssertionError) {
            val observed = focusedField()?.text?.toString()
            record.put("observedInput", observed ?: JSONObject.NULL)
            throw AssertionError("IME input in $state expected $digits, observed $observed", failure)
        }
        captureValue("after")
        robot.back()
        robot.waitUntil { keyboardWindow() == null && dateRangeFields().any { it.text.toString().filter(Char::isDigit) == digits } }
        record.put("enteredDigits", digits).put("complete", true)
    }

    private fun keyboardWindow() = robot.automation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    private fun tap(bounds: Rect) = robot.shell("input tap ${bounds.centerX()} ${bounds.centerY()}")

    @Test fun gallerySheetExpandsAtDoubleFontOnDeviceScheme() =
        captureGallery(listOf("device"), listOf(false), "plan3-gallery-sheet-system-font",
            onlySections = listOf("presentation/sheet"), widths = listOf(false), fonts = listOf(2f), requireSheetExpansion = true)

    private fun changeGalleryDateRange(safe: Rect, stacked: Boolean): String {
        visible("Дни доставки", textOnly = true)
        val (_, end) = reachableDateRangeFields(safe)
        fun enter(field: AccessibilityNodeInfo, digits: String) {
            val input = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, digits) }
            assertTrue("Enter date $digits", field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, input))
        }
        val apply = robot.text(R.string.tool_generated_apply, "Дни доставки")
        if (stacked) {
            enter(end, "01062028")
            visible(robot.text(R.string.tool_date_range_invalid), textOnly = true)
            visible(apply, disabled = true)
        }
        // Move the end first so the new start remains within the range.
        enter(reachableDateRangeFields(safe).last(), "11062028")
        robot.waitUntil { dateRangeFields().any { it.text.toString().filter(Char::isDigit) == "11062028" } }
        enter(reachableDateRangeFields(safe).first(), "08062028")
        robot.waitUntil { dateRangeFields().any { it.text.toString().filter(Char::isDigit) == "08062028" } }
        robot.assertTarget(apply)
        robot.click(apply)
        val applied = "Дни доставки: 2028-06-08 – 2028-06-11"
        robot.assertTarget(applied)
        return applied
    }

    private fun reachableDateRangeFields(safe: Rect): List<AccessibilityNodeInfo> {
        robot.waitUntil { dateRangeFields().size == 2 }
        val fields = dateRangeFields().sortedWith(compareBy({ it.screenBounds().top }, { it.screenBounds().left }))
        val minSize = (48 * robot.instrumentation.targetContext.resources.displayMetrics.density).toInt()
        for (field in fields) {
            val bounds = field.screenBounds()
            assertTrue("Reachable date field: $bounds", safe.contains(bounds) &&
                bounds.height() >= minSize && bounds.width() >= minSize)
        }
        return fields
    }

    private fun dateRangeFields(): List<AccessibilityNodeInfo> =
        robot.diagnostics.freshRoot()?.let(::descendants)?.filter { it.isVisibleToUser && it.isEnabled && it.isEditable &&
            it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SET_TEXT } }?.toList().orEmpty()

    private fun captureGallery(schemes: List<String>, modes: List<Boolean>, directoryName: String,
        collectFailures: Boolean = false, onlySections: List<String>? = null, widths: List<Boolean> = listOf(false, true),
        fonts: List<Float> = listOf(1f, 2f), requireSheetExpansion: Boolean = false) {
        val app = robot.instrumentation.targetContext
        val gallery = Intent(app, MiniAppGalleryActivity::class.java).putExtra("matrix", true).putExtra("systemFont", true)
        val originalFont = robot.shell("settings get system font_scale")
        val destination = File(app.getExternalFilesDir(null), directoryName).apply { mkdirs() }
        val report = JSONArray()
        val failures = mutableListOf<String>()
        val normalHeights = mutableMapOf<Triple<String, Boolean, Boolean>, Int>()
        try {
            withoutAnimations {
                for (fontScale in fonts) {
                    robot.shell("settings put system font_scale $fontScale")
                    for (scheme in schemes)
                        for (dark in modes)
                            for (expanded in widths) {
                                val name = "$scheme-${if (dark) "dark" else "light"}-${(fontScale * 100).toInt()}-${if (expanded) "expanded" else "compact"}"
                                val intent = Intent(gallery).putExtra("scheme", scheme).putExtra("dark", dark)
                                    .putExtra("expanded", expanded)
                                val colors = when (scheme) {
                                    "fixed" -> if (dark) AlphaDarkColors else AlphaLightColors
                                    "device" -> alphaColorScheme(app, dark)
                                    else -> galleryWallpaperColors(scheme, dark)
                                }
                                val backgrounds = mapOf("default" to colors.surfaceContainer, "muted" to colors.surfaceVariant,
                                    "accent" to colors.primaryContainer, "success" to colors.secondaryContainer,
                                    "warning" to colors.tertiaryContainer, "error" to colors.errorContainer).mapValues { it.value.toArgb() }
                                val captures = JSONArray()
                                report.put(JSONObject().put("scheme", scheme).put("dark", dark)
                                    .put("fontScale", fontScale.toDouble()).put("expanded", expanded)
                                    .put("animations", "disabled for capture").put("captures", captures))
                                ActivityScenario.launch<MiniAppGalleryActivity>(intent).use { scenario ->
                                    robot.rotate(scenario, landscape = expanded)
                                    robot.waitUntil {
                                        var effective = 0f
                                        scenario.onActivity { effective = it.resources.configuration.fontScale }
                                        effective == fontScale && !ValueAnimator.areAnimatorsEnabled()
                                    }
                                    lateinit var checks: Map<String, GalleryCaptureCheck>
                                    lateinit var safe: Rect
                                    lateinit var dateLabels: List<String>
                                    scenario.onActivity { activity ->
                                        checks = galleryCaptureChecks(activity)
                                        safe = safeArea(activity)
                                        dateLabels = listOf(R.string.tool_date_range_start, R.string.tool_date_range_end).map(activity::getString)
                                    }
                                    val sections = GallerySamples.values.flatMap { sample ->
                                        sample.sections.map { section -> "${sample.id}/${section.id}" }
                                    } + "canvas"
                                    assertEquals("Every section has a rendered-state check", sections.toSet(), checks.keys)
                                    val selected = onlySections ?: sections
                                    for (key in selected) {
                                        val page = key.substringBefore('/')
                                        val section = key.substringAfter('/', "").ifEmpty { null }
                                        scenario.onActivity { it.page = page; it.section = section }
                                        val check = checks.getValue(key)
                                        val stem = "$name-${key.replace('/', '-')}"
                                        try {
                                            val roleLabels = when (key) {
                                                "roles/swatches" -> ToolUiCatalog.tones.associateWith { it }
                                                "catalog/containers" -> GalleryToneLabels
                                                else -> emptyMap()
                                            }
                                            capture(captures, destination, stem, check, safe, colors.surface.toArgb(), roleLabels.mapValues {
                                                backgrounds.getValue(it.value)
                                            }, expectedFontScale = fontScale)
                                            if (key == "presentation/snackbar") {
                                                val message = visible(check.body.single(), textOnly = true).screenBounds()
                                                val target = robot.assertTarget(check.targets.single())
                                                assertFalse("$stem action $target overlaps the message $message", Rect.intersects(target, message))
                                            }
                                            if (key == "roles/swatches") {
                                                val width = robot.assertTarget("Проверить роли").width() / app.resources.displayMetrics.density
                                                assertTrue("$stem width=$width", if (expanded) width >= 580 else width <= 360)
                                                val dimensions = Triple(scheme, dark, expanded)
                                                val height = visible("default", textOnly = true).screenBounds().height()
                                                if (fontScale == 2f) {
                                                    val baseline = normalHeights[dimensions]
                                                    assertTrue("$stem 200% text height=$height, 100% height=$baseline",
                                                        baseline != null && height > baseline)
                                                } else normalHeights[dimensions] = height
                                            }
                                            check.next?.let { step ->
                                                step.click?.let(robot::click)
                                                step.reveal?.let { label -> if (findVisible(label, textOnly = true) == null) robot.reveal(label) }
                                                if (step.back) robot.back()
                                                if (key == "choices/select" || key == "choices/menu") {
                                                    val label = step.check.targets.single()
                                                    val minimum = (48 * app.resources.displayMetrics.density).toInt()
                                                    robot.diagnostics.target(label)
                                                    robot.waitUntil {
                                                        val item = generateSequence(robot.find(label)) { it.parent }
                                                            .firstOrNull { it.isClickable && it.isEnabled }
                                                        val bounds = item?.screenBounds()
                                                        bounds != null && bounds.width() >= minimum && bounds.height() >= minimum
                                                    }
                                                }
                                                val expansion = if (step.expandSheet) expandSheet(checkNotNull(step.dismiss), safe, requireSheetExpansion) else null
                                                val editDateRange = key == "forms/date-range"
                                                val fields = if (editDateRange) reachableDateRangeFields(safe) else emptyList()
                                                val openedCheck = if (editDateRange) {
                                                    step.check.copy(body = step.check.body + dateLabels, unclipped = dateLabels)
                                                } else step.check
                                                val opened = capture(captures, destination, "$stem-${step.id}", openedCheck, safe, colors.surface.toArgb(),
                                                    platform = step.platform, requirePlatformContrast = expanded && key == "forms/time",
                                                    dateFields = fields, dateInk = colors.onSurface.toArgb() to colors.onSurfaceVariant.toArgb(),
                                                    expectedFontScale = fontScale)
                                                expansion?.let { opened.put("sheetExpansion", it) }
                                                if (editDateRange) {
                                                    val applied = changeGalleryDateRange(safe, stacked = !expanded)
                                                    capture(captures, destination, "$stem-applied", GalleryCaptureCheck(targets = listOf(applied)),
                                                        safe, colors.surface.toArgb(), expectedFontScale = fontScale)
                                                } else if (step.platform) robot.back()
                                                else step.dismiss?.let(robot::click)
                                            }
                                        } catch (failure: AssertionError) {
                                            val message = "$stem: ${failure.message}"
                                            if (!collectFailures) throw AssertionError(message, failure)
                                            failures += message
                                            scenario.recreate()
                                        }
                                    }
                                    if (!collectFailures) {
                                        val dateRangeResult = if ("forms/date-range" in selected) 1 else 0
                                        assertEquals(selected.size + selected.count { checks.getValue(it).next != null } + dateRangeResult,
                                            captures.length())
                                    }
                                }
                            }
                }
                assertTrue(failures.joinToString("\n"), failures.isEmpty())
                assertEquals(schemes.size * modes.size * widths.size * fonts.size, report.length())
            }
        } finally {
            if (originalFont == "null") robot.shell("settings delete system font_scale")
            else robot.shell("settings put system font_scale $originalFont")
            File(destination, "matrix.json").writeText(report.toString(2))
            assertEquals("System font scale restored", originalFont, robot.shell("settings get system font_scale"))
        }
    }

    @Test fun nativePickersKeepActionsReachableAtSystemFont200() {
        val app = robot.instrumentation.targetContext
        val original = robot.shell("settings get system font_scale")
        val destination = File(app.getExternalFilesDir(null), "plan3-native-pickers-200").apply { mkdirs() }
        val report = JSONArray()
        val failures = mutableListOf<String>()
        val inputs = mapOf("date" to "День покупки: 2028-06-03", "time" to "Время покупки: 09:00")
        try {
            robot.shell("settings put system font_scale 2.0")
            withoutAnimations {
                for (expanded in listOf(false, true)) {
                    val intent = Intent(app, MiniAppGalleryActivity::class.java).putExtra("scheme", "fixed")
                        .putExtra("large", true).putExtra("expanded", expanded).putExtra("page", "forms")
                    ActivityScenario.launch<MiniAppGalleryActivity>(intent).use { scenario ->
                        robot.rotate(scenario, landscape = expanded)
                        robot.waitUntil {
                            var scale = 0f
                            scenario.onActivity { scale = it.resources.configuration.fontScale }
                            scale == 2f && !ValueAnimator.areAnimatorsEnabled()
                        }
                        lateinit var safe: Rect
                        lateinit var cancel: String
                        lateinit var confirm: String
                        var height = 0
                        scenario.onActivity {
                            safe = safeArea(it)
                            height = it.resources.configuration.screenHeightDp
                            cancel = it.getString(android.R.string.cancel)
                            confirm = it.getString(android.R.string.ok)
                        }
                        assertTrue("System window height: $height dp", if (expanded) height < 400 else height >= 400)
                        for ((section, label) in inputs) {
                            val stem = "${if (expanded) "short" else "normal"}-$section"
                            scenario.onActivity { it.section = section }
                            try {
                                robot.assertTarget(label)
                                robot.click(label)
                                val observed = capture(report, destination, stem, GalleryCaptureCheck(), safe,
                                    AlphaLightColors.surface.toArgb(), platform = true, requirePlatformContrast = true, expectedFontScale = 2f)
                                    .put("systemFontScale", 2).put("windowHeightDp", height)
                                val actions = observed.getJSONObject("controls").keys().asSequence().toList()
                                val positive = actions.singleOrNull { it.equals(confirm, ignoreCase = true) }
                                val negative = actions.singleOrNull { it.equals(cancel, ignoreCase = true) }
                                assertNotNull("Visible confirmation action: $actions", positive)
                                assertNotNull("Visible cancellation action: $actions", negative)
                                robot.click(checkNotNull(if (section == "time") positive else negative))
                                robot.assertTarget(label)
                            } catch (failure: AssertionError) {
                                failures += "$stem: ${failure.message}"
                                scenario.recreate()
                            }
                        }
                    }
                }
            }
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
            assertEquals(4, report.length())
        } finally {
            if (original == "null") robot.shell("settings delete system font_scale")
            else robot.shell("settings put system font_scale $original")
            val restored = robot.shell("settings get system font_scale")
            File(destination, "matrix.json").writeText(JSONObject().put("previousSystemFontScale", original)
                .put("restoredSystemFontScale", restored).put("captures", report).toString(2))
            assertEquals("System font scale restored", original, restored)
        }
    }

    private fun safeArea(activity: Activity): Rect {
        val view = activity.window.decorView
        val location = IntArray(2).also(view::getLocationOnScreen)
        val insets = checkNotNull(ViewCompat.getRootWindowInsets(view))
            .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        return Rect(location[0] + insets.left, location[1] + insets.top,
            location[0] + view.width - insets.right, location[1] + view.height - insets.bottom)
    }

    private fun withoutAnimations(block: () -> Unit) {
        val scales = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
        val originals = scales.associateWith { robot.shell("settings get global $it") }
        try {
            // Capture final pixels after Android and Compose transitions.
            if (Build.VERSION.SDK_INT >= 33) robot.automation.setAnimationScale(0f)
            else for (scale in scales) robot.shell("settings put global $scale 0")
            block()
        } finally {
            for ((scale, original) in originals) {
                if (original == "null") robot.shell("settings delete global $scale")
                else robot.shell("settings put global $scale $original")
            }
        }
    }

    private fun capture(report: JSONArray, directory: File, stem: String, check: GalleryCaptureCheck, safe: Rect, surface: Int,
        roleBackgrounds: Map<String, Int> = emptyMap(), platform: Boolean = false, requirePlatformContrast: Boolean = false,
        dateFields: List<AccessibilityNodeInfo> = emptyList(), dateInk: Pair<Int, Int>? = null,
        expectedFontScale: Float? = null, floatingDateLabels: List<String> = emptyList()): JSONObject {
        val measurements = JSONObject()
        val controls = JSONObject()
        val graphics = JSONObject()
        val record = JSONObject().put("state", stem).put("bodyAndControlText", measurements)
            .put("controls", controls).put("graphics", graphics).put("platformPicker", platform).put("complete", false)
        report.put(record)
        val labels = linkedMapOf<String, Pair<Rect, Boolean>>()
        for (label in check.body) labels[label] = visible(label, textOnly = true).screenBounds() to false
        for (label in check.targets) {
            controls.put(label, robot.assertTarget(label).toShortString())
            // Icon-only controls still have a named accessible action, without a text color pair.
            findVisible(label, textOnly = true)?.let { labels[label] = it.screenBounds() to true }
        }
        for (field in dateFields) {
            val value = field.text.toString()
            controls.put(value, field.screenBounds().toShortString())
            labels[value] = field.screenBounds() to true
        }
        if (platform) {
            var buttons = emptyList<AccessibilityNodeInfo>()
            robot.waitUntil {
                buttons = robot.diagnostics.freshRoot()?.let(::descendants)?.filter {
                    it.isVisibleToUser && it.isEnabled && it.isClickable &&
                        it.className == "android.widget.Button" && !it.text.isNullOrBlank()
                }?.toList().orEmpty()
                buttons.size >= 2
            }
            for (button in buttons) {
                val label = button.text.toString()
                val bounds = button.screenBounds()
                controls.put(label, bounds.toShortString())
                labels[label] = bounds to true
            }
        }
        for (label in check.disabled) {
            val control = visible(label, disabled = true)
            controls.put(label, JSONObject().put("disabled", true).put("boundsPx", control.screenBounds().toShortString()))
            labels.putIfAbsent(label, visible(label, textOnly = true).screenBounds() to true)
        }
        check.editable?.let { label ->
            val field = robot.field(label)
            assertTrue("Editable action for $label", field.isVisibleToUser &&
                field.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT))
            val bounds = field.screenBounds()
            val minSize = (48 * robot.instrumentation.targetContext.resources.displayMetrics.density).toInt()
            assertTrue("48dp editable target $label: $bounds", safe.contains(bounds) &&
                bounds.width() >= minSize && bounds.height() >= minSize)
            controls.put(label, bounds.toShortString())
        }
        check.adjust?.let { label ->
            visible(label)
            // Each slider section has one slider. Android can expose its labelled wrapper separately from the thumbs.
            val nodes = descendants(checkNotNull(robot.diagnostics.freshRoot())).filter { it.isVisibleToUser }.toList()
            val bounds = nodes.filter { node ->
                node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id }
            }.map { it.screenBounds() }
            val thumbs = if (label == "Диапазон цены") 2 else 1
            assertEquals("Adjustable actions for $label: ${nodes.filter { it.rangeInfo != null }.map { it.className to it.actionList.map { action -> action.id } }}",
                thumbs, bounds.size)
            assertTrue("Adjustable bounds for $label: $bounds", bounds.all { !it.isEmpty && safe.contains(it) })
            controls.put(label, JSONArray(bounds.map(Rect::toShortString)))
        }
        val shapes = check.graphics.associateWith { visible(it).screenBounds() }
        record.put("windowFontScale", awaitRenderedWindow(checkNotNull(robot.diagnostics.freshRoot()).windowId, expectedFontScale).toDouble())
        val screenshot = checkNotNull(robot.automation.takeScreenshot())
        try {
            screenshot.save(File(directory, "$stem.png"))
            record.put("file", "$stem.png")
            for ((label, shape) in shapes) {
                assertTrue("Graphic bounds for $label: $shape", !shape.isEmpty && safe.contains(shape))
                val pixels = screenshot.colorCounts(shape).filterKeys { it != surface }.values.sum()
                assertTrue("Visible pixels for $label", pixels > 0)
                graphics.put(label, JSONObject().put("boundsPx", shape.toShortString()).put("nonSurfacePixels", pixels))
            }
            for ((label, measurement) in labels) {
                val (bounds, controlText) = measurement
                assertTrue("Visible text bounds for $label: $bounds", !bounds.isEmpty && safe.contains(bounds))
                val pixels = textPixels(screenshot, bounds, controlText)
                val inactive = label in check.disabled && label !in check.body
                // Material intentionally dims inactive control labels. Body text in a disabled container remains readable.
                val minimum = if (inactive || platform && !requirePlatformContrast) null else 4.5
                measurements.put(label, JSONObject().put("ratio", pixels.ratio).put("foregroundArgb", pixels.foreground)
                    .put("backgroundArgb", pixels.background).put("boundsPx", bounds.toShortString())
                    .put("classification", when {
                        platform -> "platform-action"
                        inactive -> "inactive-control"
                        label in check.body -> "body"
                        else -> "app-action"
                    }).put("minimum", minimum ?: JSONObject.NULL))
                assertTrue("$label renders distinct foreground pixels", pixels.foreground != pixels.background)
                if (minimum != null) assertTrue("$label contrast=${pixels.ratio}", pixels.ratio >= minimum)
                roleBackgrounds[label]?.let { assertEquals("$label uses its role surface", it, pixels.background) }
            }
            if (dateFields.isNotEmpty()) {
                record.put("dateFieldText", dateFieldPixels(screenshot, dateFields, checkNotNull(dateInk), checkNotNull(expectedFontScale), floatingDateLabels))
            }
            val visibleCharacters = JSONObject()
            for (label in check.unclipped) {
                val chars = textCharacterBounds(label, safe)
                val background = measurements.getJSONObject(label).getInt("backgroundArgb")
                for (bounds in chars) {
                    val area = Rect().apply { bounds.roundOut(this) }
                    assertTrue("Rendered glyph at $bounds for $label", screenshot.colorCounts(area).keys.any {
                        ColorUtils.calculateContrast(it, background) > 1.25
                    })
                }
                visibleCharacters.put(label, chars.size)
            }
            if (visibleCharacters.length() > 0) record.put("visibleCharacters", visibleCharacters)
            record.put("complete", true)
        } finally { screenshot.recycle() }
        return record
    }

    private fun dateFieldPixels(bitmap: Bitmap, fields: List<AccessibilityNodeInfo>, ink: Pair<Int, Int>, fontScale: Float,
        floatingLabels: List<String>, viewport: Rect? = null): JSONArray {
        val app = robot.instrumentation.targetContext
        val context = app.createConfigurationContext(Configuration().apply { this.fontScale = fontScale })
        val density = Density(context.resources.displayMetrics.density, fontScale)
        val inset = (14 * density.density).toInt()
        val report = JSONArray()
        for ((index, field) in fields.withIndex()) {
            val bounds = field.screenBounds()
            val area = Rect(bounds.left + inset, bounds.top - inset, bounds.right - inset, bounds.bottom)
            if (viewport != null) assertTrue("Visible date ink area $area inside $viewport", area.intersect(viewport))
            val background = bitmap.colorCounts(area).maxBy { it.value }.key
            // Material uses different ink colors for the floating label and the unfocused value.
            val texts = listOf(Triple(field.text.toString(), ink.first, 16f)) +
                listOfNotNull(floatingLabels.getOrNull(index)?.let { Triple(it, ink.second, 12f) })
            for ((text, color, textSizeSp) in texts) {
                val font = Paint().apply { textSize = with(density) { textSizeSp.sp.toPx() } }
                val expected = Rect().also { font.getTextBounds(text, 0, text.length, it) }
                val painted = Rect()
                for (y in area.top until area.bottom) for (x in area.left until area.right) {
                    if (bitmap.getPixel(x, y) == color) painted.union(x, y, x + 1, y + 1)
                }
                // Keep the solid ink's horizontal span. Include contiguous antialiased rows above and below it.
                val opaqueHeight = painted.height()
                fun rowHasInk(y: Int) = (painted.left until painted.right).any { x ->
                    ColorUtils.calculateContrast(bitmap.getPixel(x, y), background) >= 1.2
                }
                while (painted.top > area.top && rowHasInk(painted.top - 1)) painted.top--
                while (painted.bottom < area.bottom && rowHasInk(painted.bottom)) painted.bottom++
                assertTrue("Full text $text renders within ${area.toShortString()}: ${painted.toShortString()}, expected ink $expected",
                    painted.width() >= expected.width() * .94f && painted.height() >= expected.height() - 1)
                val ratio = ColorUtils.calculateContrast(color, background)
                assertTrue("$text contrast=$ratio", ratio >= 4.5)
                report.put(JSONObject().put("text", text).put("boundsPx", painted.toShortString())
                    .put("expectedInkWidthPx", expected.width()).put("expectedInkHeightPx", expected.height())
                    .put("opaqueHeightPx", opaqueHeight).put("ratio", ratio))
            }
        }
        return report
    }

    @Suppress("DEPRECATION")
    private fun textCharacterBounds(label: String, safe: Rect): List<RectF> {
        val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        val request = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, label.length)
        }
        var geometry: Array<out android.os.Parcelable>? = null
        robot.waitUntil {
            val node = findVisible(label, textOnly = true)
            if (node?.refreshWithExtraData(key, request) == true) geometry = node.extras.getParcelableArray(key)
            geometry != null
        }
        val positions = checkNotNull(geometry)
        assertEquals("Character count for $label", label.length, positions.size)
        val chars = label.indices.filter { !label[it].isWhitespace() }.map { index ->
            val bounds = positions[index] as? RectF
                ?: throw AssertionError("Clipped character ${label[index]} at index $index in $label")
            val area = Rect().apply { bounds.roundOut(this) }
            assertTrue("Visible character $index at $bounds in $label", !area.isEmpty && safe.contains(area))
            bounds
        }
        val lineHeight = chars.maxOf { it.height() }
        assertTrue("Full character height in $label", chars.all { it.height() >= lineHeight - 1f })
        return chars
    }

    private fun expandSheet(close: String, safe: Rect, requireExpansion: Boolean): JSONObject {
        val action = AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND
        val minSize = (48 * robot.instrumentation.targetContext.resources.displayMetrics.density).toInt()
        fun Rect.fits() = safe.contains(this) && width() >= minSize && height() >= minSize
        fun closeFits() = findVisible(close, textOnly = false)
            ?.let { generateSequence(it) { node -> node.parent }.firstOrNull { it.isClickable } }
            ?.screenBounds()?.fits() == true
        robot.diagnostics.target(close)
        var handle: AccessibilityNodeInfo? = null
        robot.waitUntil {
            handle = robot.diagnostics.freshRoot()?.let(::descendants)?.firstOrNull { node ->
                node.isVisibleToUser && node.actionList.any { it.id == action.id }
            }
            handle != null || !requireExpansion && closeFits()
        }
        val evidence = JSONObject().put("usedExpandAction", handle != null)
        handle?.let {
            val bounds = it.screenBounds()
            val label = it.actionList.single { candidate -> candidate.id == action.id }.label
            assertTrue("Visible 48dp sheet expansion target: $bounds", it.isEnabled && bounds.fits())
            assertFalse("The sheet expansion action has an accessible label", label.isNullOrBlank())
            assertTrue("The sheet accepts the accessible expansion action", it.performAction(action.id))
            evidence.put("label", label.toString()).put("boundsPx", bounds.toShortString())
        }
        robot.waitUntil(::closeFits)
        return evidence
    }

    private fun awaitRenderedWindow(windowId: Int, expectedFontScale: Float?): Float {
        var matchingView: View? = null
        robot.waitUntil {
            robot.instrumentation.runOnMainSync {
                matchingView = WindowInspector.getGlobalWindowViews().firstOrNull {
                    it.isShown && it.isLaidOut && it.createAccessibilityNodeInfo().windowId == windowId
                }
            }
            matchingView != null
        }
        val rendered = CountDownLatch(1)
        var fontScale = 0f
        robot.instrumentation.runOnMainSync {
            val view = checkNotNull(matchingView)
            fontScale = view.resources.configuration.fontScale
            expectedFontScale?.let { assertEquals("Effective font scale in window $windowId", it, fontScale) }
            assertTrue("Window $windowId supports a frame commit", view.isHardwareAccelerated && view.viewTreeObserver.isAlive)
            view.viewTreeObserver.registerFrameCommitCallback { rendered.countDown() }
            view.postInvalidateOnAnimation()
        }
        assertTrue("Window $windowId submitted a frame", rendered.await(5, TimeUnit.SECONDS))
        robot.awaitFrames(2)
        return fontScale
    }

    private fun visible(label: String, textOnly: Boolean = false, disabled: Boolean = false): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        robot.diagnostics.target(label)
        try { robot.waitUntil { result = findVisible(label, textOnly, disabled); result != null } }
        catch (failure: AssertionError) { throw AssertionError("Missing visible label $label", failure) }
        return checkNotNull(result)
    }

    private fun findVisible(label: String, textOnly: Boolean, disabled: Boolean = false): AccessibilityNodeInfo? =
        robot.diagnostics.freshRoot()?.let(::descendants)?.firstOrNull { node ->
            node.isVisibleToUser && (node.text?.toString()?.trimEnd() == label.trimEnd() ||
                !textOnly && node.contentDescription?.toString()?.trimEnd() == label.trimEnd()) &&
                (!disabled || generateSequence(node) { it.parent }.any { !it.isEnabled })
        }

    private fun descendants(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let { yieldAll(descendants(it)) }
    }

    private fun AccessibilityNodeInfo.screenBounds() = Rect().also(::getBoundsInScreen)

    @Test fun galleryCanvasDrawsRoleHexAndGradientOnShellSurface() {
        val app = robot.instrumentation.targetContext
        val intent = Intent().setClassName(app, "com.alpha.assistant.MiniAppGalleryActivity")
            .putExtra("scheme", "fixed").putExtra("page", "canvas")
        ActivityScenario.launch<android.app.Activity>(intent).use {
            val canvas = robot.waitFor("Проверка холста. Графика. ")
            val bounds = Rect().also { canvas.getBoundsInScreen(it) }
            val screenshot = checkNotNull(robot.automation.takeScreenshot())
            try {
                fun sample(x: Int, y: Int) = screenshot.getPixel(bounds.left + bounds.width() * x / 100, bounds.top + bounds.height() * y / 100)
                assertEquals(0xFFFDF7FF.toInt(), sample(90, 90))
                assertEquals(0xFF63568F.toInt(), sample(10, 20))
                assertEquals(0xFF123456.toInt(), sample(40, 20))
                assertEquals(0xFFBA1A1A.toInt(), sample(65, 20))
                assertEquals(0xFF1C1B20.toInt(), sample(95, 20))
                val directory = File(app.getExternalFilesDir(null), "plan3-gallery").apply { mkdirs() }
                screenshot.save(File(directory, "canvas.png"))
            } finally { screenshot.recycle() }
        }
    }

    @Test fun galleryCatalogAndReducedMotionKeepNativeActionsAvailable() {
        val app = robot.instrumentation.targetContext
        val intent = Intent().setClassName(app, "com.alpha.assistant.MiniAppGalleryActivity").putExtra("scheme", "fixed")
        ActivityScenario.launch<android.app.Activity>(Intent(intent).putExtra("page", "catalog")).use {
            robot.assertTarget("Открыть диалог")
            robot.click("Открыть диалог")
            robot.waitFor("Данные сохранены")
            robot.assertTarget("Закрыть: Проверка")
            robot.click("Закрыть: Проверка")
            robot.waitGone("Данные сохранены")
        }

        val original = robot.shell("settings get global animator_duration_scale").trim()
        try {
            robot.shell("settings put global animator_duration_scale 0")
            ActivityScenario.launch<android.app.Activity>(intent).use {
                robot.waitUntil { !ValueAnimator.areAnimatorsEnabled() }
                robot.click("Проверить роли")
                val badge = robot.waitFor("Роли проверены")
                assertTrue(badge.isVisibleToUser)
                val before = Rect().also { badge.getBoundsInScreen(it) }
                robot.automation.waitForIdle(100, 3000)
                val after = Rect().also { robot.waitFor("Роли проверены").getBoundsInScreen(it) }
                assertEquals(before, after)
                robot.click("Проверить роли")
                robot.waitGone("Роли проверены")
            }
            val directory = File(app.getExternalFilesDir(null), "plan3-gallery").apply { mkdirs() }
            File(directory, "reduced-motion.json").writeText("""{"animatorDurationScale":0,"action":"passed","stableBounds":"passed"}""")
        } finally {
            if (original == "null") robot.shell("settings delete global animator_duration_scale")
            else robot.shell("settings put global animator_duration_scale $original")
        }
    }

    private fun Bitmap.save(file: File) {
        file.outputStream().use { assertTrue(compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private data class TextPixels(val foreground: Int, val background: Int) {
        val ratio get() = ColorUtils.calculateContrast(foreground, background)
    }

    private fun textPixels(bitmap: Bitmap, bounds: Rect, control: Boolean): TextPixels {
        val sample = Rect(bounds)
        if (control) sample.inset(bounds.width() / 8, bounds.height() / 5)
        val colors = bitmap.colorCounts(sample)
        val background = checkNotNull(colors.maxByOrNull { it.value }).key
        val foreground = colors.filter { (color, count) -> color != background && count >= 3 }.keys
            .maxByOrNull { ColorUtils.calculateContrast(it, background) }
            ?: throw AssertionError("No foreground pixels in $sample")
        return TextPixels(foreground, background)
    }

    private fun Bitmap.colorCounts(bounds: Rect): Map<Int, Int> {
        assertTrue("Bounds lie inside the screenshot: $bounds", !bounds.isEmpty &&
            bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= height)
        val pixels = IntArray(bounds.width() * bounds.height())
        getPixels(pixels, 0, bounds.width(), bounds.left, bounds.top, bounds.width(), bounds.height())
        val counts = mutableMapOf<Int, Int>()
        for (pixel in pixels) counts[pixel] = (counts[pixel] ?: 0) + 1
        return counts
    }
}
