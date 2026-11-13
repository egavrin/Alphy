package com.alpha.assistant.tools

import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.alpha.assistant.R
import com.alpha.assistant.capabilities.AudioContract
import com.alpha.assistant.capabilities.CapabilityEffect
import com.alpha.assistant.media.MAX_TONE_MILLIS
import com.alpha.assistant.tools.generation.sameToolJson
import com.alpha.assistant.ui.ShellRobot
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Frozen Plan 5 interactions. Only the test host supplies these names and expected results. */
internal class Plan5WorkflowChecks(
    private val robot: ShellRobot,
    private val proof: JSONObject,
    private val saved: () -> ToolRecord,
    private val screenshot: (String) -> Boolean,
    private val toolDatabase: String,
) {
    companion object {
        val cases = setOf("extra:reference-points-ledger", "compose:stopwatch", "extra:russian-grid-game",
            "extra:russian-embedded-drawing", "unseen:controlled-clock", "fresh:health-4", "fresh:cooking-1", "fresh:logs-3", "fresh:logs-5")

        fun reopenSaved(robot: ShellRobot, title: String, fromChat: Boolean = false) {
            val refine = robot.text(R.string.tool_refine)
            val library = robot.text(R.string.desktop_open_library)
            val search = robot.text(R.string.tool_search)
            robot.back()
            robot.waitGone(refine)
            if (fromChat) {
                robot.click(robot.text(R.string.tool_open))
            } else {
                robot.waitUntil { robot.find(search) != null || robot.find(library) != null }
                if (robot.find(search) == null) robot.click(library)
                robot.waitFor(search)
                robot.click(title)
            }
            robot.waitFor(refine)
        }
    }

    private val checks = JSONArray().also { proof.put("checks", it) }
    private val actions = JSONArray().also { proof.put("actions", it) }
    private val context get() = robot.instrumentation.targetContext
    private val numbers = Regex("""-?\d+(?:[.,]\d+)?""")

    fun run(case: String, reopen: () -> Unit, verifyCoffeeDismissals: Boolean = false, refine: (String) -> Unit) = when (case) {
        "extra:reference-points-ledger" -> ledger(reopen, refine)
        "compose:stopwatch", "unseen:controlled-clock" -> stopwatch()
        "extra:russian-grid-game" -> grid(reopen)
        "extra:russian-embedded-drawing" -> drawing()
        "fresh:health-4" -> calorieBudget(reopen)
        "fresh:cooking-1" -> recipeScaler(reopen)
        "fresh:logs-3" -> readingLog(reopen)
        "fresh:logs-5" -> coffeeCounter(reopen, verifyCoffeeDismissals)
        else -> error("No executable Plan 5 workflow for this case.")
    }

    private fun step(name: String, verify: () -> Any) {
        proof.put("step", name)
        try {
            checks.put(JSONObject().put("name", name).put("observed", verify()))
        } catch (failure: Throwable) {
            if (!proof.has("first_failed_boundary")) {
                proof.put("first_failed_boundary", name)
                    .put("failure_type", failure.javaClass.name)
                    .put("failure_detail", failure.message?.take(600))
                    .put("state_at_failure", runCatching { saved().state.take(4096) }.getOrNull())
                    .put("failure_screenshot", runCatching { screenshot("workflow-failure") }.getOrDefault(false))
            }
            throw failure
        }
    }

    private fun uiNodes(node: JSONObject): Sequence<JSONObject> = sequence {
        yield(node)
        node.optJSONArray("children")?.let { children ->
            repeat(children.length()) { yieldAll(uiNodes(children.getJSONObject(it))) }
        }
    }

    private fun accessibility(node: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
        if (node != null) {
            yield(node)
            repeat(node.childCount) { yieldAll(accessibility(node.getChild(it))) }
        }
    }

    private fun matching(expression: String): Regex = Regex(expression, RegexOption.IGNORE_CASE)

    private fun controls() = uiNodes(JSONObject(saved().ui)).filter { it.optString("type") == "button" }
    private fun controlLabel(control: JSONObject) = control.getString("text")

    private fun button(expression: String) {
        val pattern = matching(expression)
        val control = controls().single { pattern.containsMatchIn(controlLabel(it)) }
        clickControl(control)
    }

    private fun clickControl(control: JSONObject) {
        val label = controlLabel(control)
        robot.reveal(label, null)
        check(accessibility(robot.diagnostics.freshRoot()).count {
            it.isVisibleToUser && it.text?.toString() == label
        } == 1) { "Ambiguous generated control." }
        val delivery = JSONObject().put("step", proof.getString("step"))
            .put("action", control.getString("action")).put("delivery", "attempted")
        actions.put(delivery)
        try {
            robot.click(label)
            delivery.put("delivery", "delivered")
        } catch (failure: Throwable) {
            delivery.put("delivery", "failed_or_unconfirmed").put("failure_type", failure.javaClass.name)
                .put("failure_detail", failure.message?.take(600))
            throw failure
        }
        robot.waitGone(robot.text(R.string.tool_working))
    }

    private fun visibleValue(label: String, expected: Double): String {
        val name = matching(label)
        val inlinePattern = matching("""^\s*(?:$label)\s*[:=]\s*(-?\d+(?:[.,]\d+)?)\s*$""")
        var observed = ""
        val matchingLabel = uiNodes(JSONObject(saved().ui)).map { it.optString("text") }
            .firstOrNull { name.matches(it.trim()) }
        if (matchingLabel != null) robot.reveal(matchingLabel, null)
        robot.waitUntil {
            val tool = saved()
            val state = projectToolState(ToolSpec.parsePersisted(tool.spec), JSONObject(tool.state), localPartClock(context))
            val matchingNode = uiNodes(JSONObject(tool.ui)).firstOrNull { node ->
                val text = node.optString("text")
                val value = node.optString("bind").takeIf(String::isNotEmpty)?.let { ToolUiCatalog.value(state, it) }
                name.matches(text.trim()) && (value as? Number)?.toDouble() == expected
            }
            val texts = robot.texts()
            val inline = texts.firstOrNull {
                inlinePattern.matchEntire(it)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() == expected
            }
            val metricVisible = matchingNode?.let { metric ->
                val labelNode = robot.find(metric.optString("text")) ?: return@let false
                accessibility(labelNode.parent).any {
                    it.isVisibleToUser && numeric(it.text?.toString().orEmpty()).singleOrNull() == expected
                }
            } == true
            observed = inline ?: matchingNode?.optString("text").orEmpty()
            inline != null || metricVisible
        }
        return "$observed = ${expected.toInt()}"
    }

    private fun numeric(text: String) = numbers.findAll(text).mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }.toList()

    private fun generatedInput(type: String, labelPattern: String): JSONObject =
        uiNodes(JSONObject(saved().ui)).single {
            it.optString("type") == type && matching(labelPattern).containsMatchIn(it.optString("text"))
        }

    private fun editGeneratedInput(control: JSONObject, value: String) {
        val label = controlLabel(control)
        robot.reveal(label, null)
        robot.edit(label, value)
        val apply = robot.text(R.string.tool_generated_apply_en, label)
        val finish = robot.text(R.string.tool_live_input_finish_en, label)
        if (ToolUiCatalog.isLiveInput(control)) {
            if (robot.find(finish) != null) robot.click(finish)
        } else {
            robot.reveal(apply, null)
            robot.click(apply)
        }
        robot.waitGone(robot.text(R.string.tool_working))
    }

    private fun verifyLogRows(original: ToolRecord, expectedRows: Map<String, Int>) {
        val record = saved()
        check(record.id == original.id && record.revision == original.revision)
        val state = JSONObject(record.state)
        val collection = uiNodes(JSONObject(record.ui)).single {
            it.optString("type") in setOf("list", "table", "lazyList", "lazyGrid", "groupedList")
        }
        val rows = ToolUiCatalog.records(collection, state)
        check(rows.length() == expectedRows.size) { "The stored log has ${rows.length()} rows." }
        for ((name, amount) in expectedRows) {
            val row = (0 until rows.length()).map(rows::getJSONObject).single {
                it.keys().asSequence().filter { key -> key != "id" }.any { key -> it.opt(key) == name }
            }
            check(row.keys().asSequence().any { (row.opt(it) as? Number)?.toDouble() == amount.toDouble() }) {
                "The stored entry for $name has the wrong amount."
            }
            val nameNode = robot.reveal(name, null)
            val rowText = accessibility(nameNode.parent).filter { it.isVisibleToUser }
                .flatMap { numeric(it.text?.toString().orEmpty()).asSequence() }
            check(rowText.any { it == amount.toDouble() }) { "The visible entry for $name does not show $amount." }
        }
    }

    private fun calorieBudget(reopen: () -> Unit) {
        val original = saved()
        proof.put("artifact_id", original.id).put("artifact_revision", original.revision)
        val expectedRows = linkedMapOf<String, Int>()
        fun verify(remaining: Int, image: String): JSONObject {
            val balance = visibleValue(""".*\b(?:remaining|left)\b.*""", remaining.toDouble())
            val valueLabel = remaining.toString()
            if (robot.find(valueLabel) != null) robot.reveal(valueLabel, null)
            check(screenshot("$image-balance"))
            verifyLogRows(original, expectedRows)
            check(screenshot(image))
            return JSONObject().put("remaining", remaining).put("visible_balance", balance)
                .put("rows", JSONObject(expectedRows))
        }
        step("calorie_initial") { verify(2000, "calorie-initial") }
        for ((name, amount, remaining) in listOf(Triple("Oatmeal", 500, 1500), Triple("Lunch", 850, 650))) {
            val image = "calorie-${name.lowercase()}"
            step(image) {
                editGeneratedInput(generatedInput("input", "food|meal|name"), name)
                editGeneratedInput(generatedInput("numberInput", """^(?!.*(?:goal|target|budget)).*(?:calorie|kcal).*"""), amount.toString())
                button("""^\s*(?:add|log|record)(?:\s+.*)?$""")
                expectedRows[name] = amount
                verify(remaining, image)
            }
        }
        step("calorie_reopened") {
            reopen()
            verify(650, "calorie-reopened")
        }
    }

    private fun recipeScaler(reopen: () -> Unit) {
        val original = saved()
        proof.put("artifact_id", original.id).put("artifact_revision", original.revision)
        fun servings() = generatedInput("numberInput", "desired|target|new|wanted")
        fun verify(target: Int, flour: Int, image: String): JSONObject {
            val control = controlLabel(servings())
            robot.reveal(control, null)
            check(robot.field(control).text?.toString()?.toDoubleOrNull() == target.toDouble()) {
                "The visible target serving count is not $target."
            }
            val result = visibleValue(""".*\b(?:flour|scaled amount|scaled quantity)\b.*""", flour.toDouble())
            if (robot.find(flour.toString()) != null) robot.reveal(flour.toString(), null)
            check(screenshot(image))
            val grams = matching("""\b(?:g|grams?)\b""")
            if (robot.texts().none(grams::containsMatchIn)) robot.reveal("g", null)
            check(robot.texts().any(grams::containsMatchIn)) { "The recipe does not display grams." }
            val record = saved()
            check(record.id == original.id && record.revision == original.revision)
            return JSONObject().put("servings", target).put("flour_grams", flour).put("visible_result", result)
        }
        step("recipe_initial") { verify(6, 450, "recipe-initial") }
        step("recipe_adjusted") {
            editGeneratedInput(servings(), "8")
            verify(8, 600, "recipe-adjusted")
        }
        step("recipe_reopened") {
            reopen()
            verify(8, 600, "recipe-reopened")
        }
    }

    private fun readingLog(reopen: () -> Unit) {
        val original = saved()
        proof.put("artifact_id", original.id).put("artifact_revision", original.revision)
        val expectedRows = linkedMapOf<String, Int>()
        fun verify(total: Int, image: String): JSONObject {
            val visibleTotal = visibleValue(""".*\b(?:(?:total|sum).*pages|pages.*total)\b.*""", total.toDouble())
            if (total > 0 && robot.find(total.toString()) != null) robot.reveal(total.toString(), null)
            check(screenshot("$image-total"))
            verifyLogRows(original, expectedRows)
            check(screenshot(image))
            return JSONObject().put("total_pages", total).put("visible_total", visibleTotal)
                .put("rows", JSONObject(expectedRows))
        }
        step("reading_initial") { verify(0, "reading-initial") }
        for ((name, pages, total) in listOf(Triple("Dune", 412, 412), Triple("Emma", 320, 732))) {
            val image = "reading-${name.lowercase()}"
            step(image) {
                editGeneratedInput(generatedInput("input", "book|title|name"), name)
                editGeneratedInput(generatedInput("numberInput", """\bpages?\b"""), pages.toString())
                button("""^\s*(?:add|log|record)(?:\s+.*)?$""")
                expectedRows[name] = pages
                verify(total, image)
            }
        }
        step("reading_reopened") {
            reopen()
            verify(732, "reading-reopened")
        }
    }

    private fun coffeeCounter(reopen: () -> Unit, verifyDismissals: Boolean) {
        val original = saved()
        proof.put("artifact_id", original.id).put("artifact_revision", original.revision)
            .put("audio_verification", "Native tone receipts. Physical audibility is unverified.")
        val receiptKeys = mutableListOf<String>()
        fun verify(count: Int, image: String): JSONObject {
            val visibleCount = visibleValue(""".*\b(?:cups?|count)\b.*""", count.toDouble())
            val current = saved()
            check(current.id == original.id && current.revision == original.revision)
            val receipts = ToolStore(context, toolDatabase).use { store ->
                var tones = emptyList<CapabilityEffect>()
                robot.waitUntil {
                    tones = store.effects(original.id).filter { it.request.capability == AudioContract.TONE }
                    tones.size >= count && tones.all { it.phase == "terminal" }
                }
                check(tones.size == count) { "Expected $count tone receipts, found ${tones.size}." }
                for (tone in tones) {
                    check(tone.result.status == "succeeded") { "Native tone result was ${tone.result.status}." }
                    check(tone.request.args.getString("tone") == "beep")
                    check(tone.request.args.getInt("durationMillis") in 1..MAX_TONE_MILLIS)
                    check(tone.owner == original.id && tone.revision == original.revision)
                }
                tones
            }
            check(receipts.map { it.key }.take(receiptKeys.size) == receiptKeys)
            receiptKeys.clear()
            receiptKeys.addAll(receipts.map { it.key })
            check(screenshot(image))
            return JSONObject().put("cups", count).put("visible_count", visibleCount)
                .put("tone_receipts", JSONArray(receipts.map {
                    JSONObject().put("key", it.key).put("request", it.request.json()).put("result", it.result.json())
                }))
        }
        step("coffee_initial") { verify(0, "coffee-initial") }
        for (count in 1..2) {
            step("coffee_add_$count") {
                button("""^\s*(?:(?:add|log|record)(?:\s+.*)?|\+\s*1(?:\s+cup)?)\s*$""")
                verify(count, "coffee-$count")
            }
        }
        step("coffee_reopened") {
            reopen()
            verify(2, "coffee-reopened")
        }
        if (verifyDismissals) {
            val dialog = "Start a new day?"
            val close = robot.text(R.string.tool_generated_dialog_close, dialog)
            step("coffee_cancel_reset") {
                button("^Reset day$")
                robot.waitFor(dialog)
                button("^Cancel$")
                robot.waitGone(dialog)
                verify(2, "coffee-cancel-reset")
            }
            step("coffee_close_reset") {
                button("^Reset day$")
                robot.waitFor(dialog)
                robot.click(close)
                robot.waitGone(dialog)
                verify(2, "coffee-close-reset")
            }
        }
    }

    fun grid(reopen: () -> Unit, verifyHostRestart: Boolean = false,
        refineVictory: ((String) -> Unit)? = null) = ToolStore(context, toolDatabase).use { store ->
        val spec = ToolSpec.parsePersisted(saved().spec)
        fun cellControl(cell: Int) = controls().singleOrNull {
            matching("""^\s*$cell(?:[^\p{L}\p{N}]|$)""").containsMatchIn(controlLabel(it))
        }
        val drawnBoard = (1..9).none { cellControl(it) != null }
        check(if (drawnBoard) spec.surfaces.size == 1 else (1..9).all { cellControl(it) != null })
        fun tapCell(cell: Int) {
            if (!drawnBoard) {
                clickControl(checkNotNull(cellControl(cell)))
                return
            }
            val bounds = canvas()
            val current = artifactSnapshot(store)
            val (id, declared) = spec.surfaces.entries.single()
            val placement = surfacePlacements(listOf(current.viewport ?: declared)).single()
            val commands = current.surfaces.getJSONObject(id).getJSONArray("commands")
            val (path, number) = drawingLeaves(commands).single { (_, command) ->
                command.optString("type") == "text" && command.optString("text") == cell.toString()
            }
            var children = commands
            val transforms = path.dropLast(1).mapNotNull { index ->
                val group = children.getJSONObject(index)
                children = group.getJSONArray("children")
                group.optJSONArray("transform")
            }
            var x = number.getDouble("x")
            var y = number.getDouble("y") - number.getDouble("size") / 2
            for (matrix in transforms.asReversed()) {
                val mappedX = matrix.getDouble(0) * x + matrix.getDouble(2) * y + matrix.getDouble(4)
                y = matrix.getDouble(1) * x + matrix.getDouble(3) * y + matrix.getDouble(5)
                x = mappedX
            }
            touch(bounds, ((placement.x + x) / (placement.x + placement.surface.width)).toFloat(),
                ((placement.y + y) / (placement.y + placement.surface.height)).toFloat())
        }
        fun score(value: Int): String {
            val label = "очки|сч[её]т|баллы"
            if (!drawnBoard) return visibleValue(label, value.toDouble())
            canvas()
            robot.waitUntil { gameNumber(label) == value }
            return "Canvas score = $value"
        }
        fun labels() = robot.texts().flatMap { it.split(". ") }.map(String::trim).toSet()
        var initialLabels = emptySet<String>()
        var victoryLabels = emptySet<String>()
        val victoryMessage = "Победа! Все три звезды найдены!"
        if (refineVictory != null) step("edit_victory") {
            score(0)
            tapCell(2)
            score(1)
            val before = JSONObject(saved().state)
            refineVictory("После победы показывай «$victoryMessage». Сохрани текущие очки и открытые клетки.")
            check(sameToolJson(before, JSONObject(saved().state)))
            score(1)
            robot.waitFor("2 ★")
        }
        step("score") {
            val scores = mutableListOf(score(if (refineVictory == null) 0 else 1))
            initialLabels = labels()
            val moves = if (refineVictory == null) listOf(1 to 0, 2 to 1, 2 to 1, 5 to 2) else listOf(2 to 1, 5 to 2)
            for ((cell, value) in moves) {
                tapCell(cell)
                scores += score(value)
            }
            scores.joinToString(", ")
        }
        step("terminal") {
            tapCell(8)
            score(3)
            val victory = matching("побед|выигр")
            robot.waitUntil {
                victoryLabels = (labels() - initialLabels).filter(victory::containsMatchIn).toSet()
                victoryLabels.isNotEmpty()
            }
            if (refineVictory != null) check(robot.texts().any { victoryMessage in it })
            for (cell in listOf(7, 2)) {
                if (drawnBoard) tapCell(cell) else {
                    val control = checkNotNull(cellControl(cell))
                    val label = controlLabel(control)
                    val node = robot.reveal(label, null)
                    val target = generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
                    if (target?.isEnabled == true) clickControl(control)
                }
            }
            robot.waitGone(robot.text(R.string.tool_working))
            score(3)
            check(screenshot("grid-victory"))
            "Score 3 with victory and no further scoring"
        }
        if (verifyHostRestart) step("host_restart") {
            val hostRestart = robot.text(R.string.tool_game_restart)
            robot.reveal(hostRestart, null)
            robot.click(hostRestart)
            robot.waitGone(robot.text(R.string.tool_working))
            score(3)
            check(labels().intersect(victoryLabels).isNotEmpty())
            "Graphics restart leaves the score at 3"
        }
        step("restart_delivered") { button("заново|перезапус|новая игра"); "delivered" }
        step("restart_zero_observed") { score(0) }
        step("restart_victory_cleared") {
            robot.waitUntil { labels().intersect(victoryLabels).isEmpty() }
            true
        }
        step("restart_cell2_located") {
            if (drawnBoard) canvas() else robot.reveal(controlLabel(checkNotNull(cellControl(2))), null)
            true
        }
        step("restart_cell2_delivery") { tapCell(2); "delivered" }
        step("restart_score1_observed") { score(1) }
        step("restart_reopen_completed") { reopen(); true }
        step("restart_score1_after_reopen") { score(1) }
    }

    fun ledger(reopen: () -> Unit, refine: ((String) -> Unit)? = null) {
        val teams = listOf("Янтарь", "Мел")
        fun teamName(text: String): String? {
            val label = text.trim().replace(matching("^(?:баланс +)?(?:команда +|команды +)?"), "")
                .trim('«', '»', ' ', '"')
            return teams.singleOrNull { it.equals(label, true) }
        }
        fun toolUi() = JSONObject(saved().ui)
        fun nodeText(node: AccessibilityNodeInfo) = node.text?.toString().orEmpty()
        fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
        fun nodes() = accessibility(robot.diagnostics.freshRoot()).filter { it.isVisibleToUser }.toList()
        fun clickable(node: AccessibilityNodeInfo) = generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
        fun teamOf(node: AccessibilityNodeInfo, visible: List<AccessibilityNodeInfo>): String? {
            val control = bounds(node)
            return visible.filter { bounds(it).top < control.top && teamName(nodeText(it)) != null }
                .minWithOrNull(compareBy({ control.top - bounds(it).top }, {
                    kotlin.math.abs(control.centerX() - bounds(it).centerX())
                }))?.let { teamName(nodeText(it)) }
        }
        fun chooseTeam(team: String): Boolean {
            val choice = uiNodes(toolUi()).firstOrNull { control ->
                control.optJSONArray("options")?.let { options ->
                    (0 until options.length()).any { teamName(options.getJSONObject(it).optString("text")) == team }
                } == true
            } ?: return false
            val options = choice.getJSONArray("options")
            val option = (0 until options.length()).map(options::getJSONObject)
                .single { teamName(it.getString("text")) == team }
            val label = option.getString("text")
            fun selected() = sameToolJson(
                ToolUiCatalog.value(JSONObject(saved().state), choice.getString("bind")), option.get("value"),
            )
            if (selected()) return true
            robot.reveal(choice.getString("text"), null)
            var target: AccessibilityNodeInfo? = null
            robot.waitUntil {
                target = nodes().filter { nodeText(it) == label }.mapNotNull(::clickable)
                    .filter { it.isEnabled && it.isVisibleToUser }.distinct().singleOrNull()
                if (target == null) robot.scrollForward()
                target != null
            }
            val action = choice.getString("action")
            actions.put(JSONObject().put("step", proof.getString("step")).put("action", action).put("team", team))
            check(checkNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            robot.waitUntil { selected() }
            robot.waitGone(robot.text(R.string.tool_working))
            return true
        }
        fun action(team: String, expression: String, allowDisabled: Boolean = false) {
            if (chooseTeam(team)) {
                if (!allowDisabled) {
                    button(expression)
                    return
                }
            }
            val pattern = matching(expression)
            val labels = uiNodes(toolUi()).filter { it.optString("type") == "button" }.map { it.optString("text") }
                .filter(pattern::containsMatchIn).toList()
            val scoped = labels.size > 1
            val label = labels.firstOrNull() ?: error("Ledger action not rendered.")
            robot.reveal(label, null)
            repeat(8) {
                val visible = nodes()
                fun buttonNode(node: AccessibilityNodeInfo) = generateSequence(node) { it.parent }.firstOrNull {
                    it.isClickable || allowDisabled && !it.isEnabled
                }
                val control = visible.firstOrNull { nodeText(it) in labels &&
                    buttonNode(it) != null && (!scoped || teamOf(it, visible) == team) }
                if (control != null) {
                    val target = checkNotNull(buttonNode(control))
                    if (target.isEnabled) {
                        val label = nodeText(control)
                        controls().singleOrNull { controlLabel(it) == label }
                            ?.let { actions.put(JSONObject().put("step", proof.getString("step")).put("action", it.optString("action"))) }
                        check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        robot.automation.waitForIdle(100, 3_000)
                        robot.waitGone(robot.text(R.string.tool_working))
                    } else check(allowDisabled)
                    return
                }
                if (!robot.scrollForward()) error("Ledger action for $team is outside the available controls.")
            }
            error("Ledger action for $team is unavailable.")
        }
        fun balance(team: String, expected: Int): String {
            val metrics = uiNodes(toolUi()).filter { teamName(it.optString("text")) == team }.flatMap(::uiNodes)
                .filter { it.has("bind") && it.optString("type") in setOf("metric", "text", "fact", "badge") }.toList()
            if (metrics.isEmpty()) return visibleValue(team, expected.toDouble())
            val metric = metrics.single()
            robot.reveal(metric.getString("text"), null)
            robot.waitUntil {
                val record = saved()
                val value = ToolUiCatalog.value(JSONObject(record.state), metric.getString("bind"))
                (value as? Number)?.toInt() == expected && nodes().any { node ->
                    val label = metric.getString("text")
                    nodeText(node) == label && accessibility(node.parent).any {
                        it.isVisibleToUser && numeric(nodeText(it)).singleOrNull() == expected.toDouble()
                    } || nodeText(node) == "$label: $value"
                }
            }
            return "$team = $expected"
        }
        data class History(val team: String, val operation: String, val points: Int, val rendered: Map<String, String>)
        fun history(verifyRendered: Boolean = true): List<History> {
            val record = saved()
            val collections = uiNodes(JSONObject(record.ui)).filter { node ->
                node.has("bind") && node.optString("type") in setOf("list", "table", "groupedList", "lazyList", "lazyGrid") &&
                    matching("истор|операц|журнал|запис").containsMatchIn(node.optString("text"))
            }.toList()
            check(collections.isNotEmpty())
            return collections.flatMap { collection ->
                val title = collection.getString("text")
                if (verifyRendered) robot.reveal(title, null)
                val sectionTeam = teams.singleOrNull { title.contains(it, ignoreCase = true) }
                val columns = collection.optJSONArray("columns")?.let { fields ->
                    (0 until fields.length()).map(fields::getJSONObject)
                }
                val rows = ToolUiCatalog.records(collection, JSONObject(record.state))
                val visibleRows = mutableListOf<List<String>>()
                val history = (0 until rows.length()).map { index ->
                    val row = rows.getJSONObject(index)
                    val rendered = if (columns == null) {
                        row.keys().asSequence().filter { it != "id" }.associateWith { row.get(it).toString() }
                    } else columns.associate { column ->
                        column.getString("text") to ToolUiCatalog.value(row, column.getString("field")).toString()
                    }.also { check(it.size == columns.size) }
                    fun field(expression: String): String? {
                        val pattern = matching(expression)
                        return rendered.entries.singleOrNull { pattern.containsMatchIn(it.key) }?.value
                    }
                    val operation = checkNotNull(field("действ|операц|событи|^action$|^operation$|^kind$"))
                    val owner = field("команд|^team$")?.let(::teamName) ?: sectionTeam
                    val points = field("очк|изменен|разниц|сумм|^points$|^change$|^delta$") ?: operation
                    val signed = points.replace('−', '-')
                    val delta = signed.trim().toIntOrNull()
                        ?: Regex("""[+-]\s*\d+""").findAll(signed).singleOrNull()?.value?.replace(" ", "")?.toIntOrNull()
                    if (verifyRendered) visibleRows += if (columns == null) {
                        listOf(rendered.entries.joinToString(" · ") { "${it.key}: ${it.value}" })
                    } else rendered.values.toList()
                    History(checkNotNull(owner), operation, checkNotNull(delta), rendered)
                }
                for ((fields, count) in visibleRows.groupingBy { it }.eachCount()) {
                    robot.reveal(title, null)
                    // A fresh node for the same source and window compares equal across scroll steps.
                    val renderedRows = mutableSetOf<AccessibilityNodeInfo>()
                    for (page in 0..10) {
                        val visible = nodes().filter { nodeText(it).isNotBlank() }
                        visible.windowed(fields.size).filter { row -> row.map(::nodeText) == fields }
                            .mapTo(renderedRows) { it.first() }
                        if (renderedRows.size >= count || !robot.scrollForward()) break
                    }
                    check(renderedRows.size >= count) { "Required ledger history rows are not visible." }
                }
                history
            }
        }
        step("balance_guard") {
            balance("Янтарь", 0)
            balance("Мел", 0)
            val before = history(verifyRendered = false)
            action("Янтарь", "подсказ", allowDisabled = true)
            balance("Янтарь", 0)
            balance("Мел", 0)
            check(history(verifyRendered = false) == before)
        }
        step("earn_spend") {
            action("Янтарь", "викторин")
            balance("Янтарь", 3)
            action("Янтарь", "викторин")
            balance("Янтарь", 6)
            action("Янтарь", "подсказ")
            balance("Янтарь", 1)
        }
        step("independent_undo") {
            action("Мел", "пазл")
            balance("Мел", 2)
            button("отмен")
            balance("Мел", 0)
            balance("Янтарь", 1)
        }
        lateinit var original: List<History>
        step("history") {
            original = history()
            val amber = original.filter { it.team == "Янтарь" }
            check(amber.size == 3)
            check(amber.count { it.points == 3 && matching("викторин").containsMatchIn(it.operation) } == 2)
            check(amber.count { it.points == -5 && matching("подсказ").containsMatchIn(it.operation) } == 1)
            val chalk = original.filter { it.team == "Мел" }
            check(chalk.size <= 1 && chalk.all {
                matching("пазл").containsMatchIn(it.operation) && matching("отмен").containsMatchIn(it.operation)
            })
            check(screenshot("ledger-history"))
            original.joinToString()
        }
        if (refine == null) {
            reopen()
            balance("Янтарь", 1)
            balance("Мел", 0)
            check(history() == original)
            return
        }
        step("change_quiz_points") {
            refine("За будущие викторины начисляй 4 очка. Сохрани существующие балансы и все записи истории.")
            balance("Янтарь", 1)
            balance("Мел", 0)
            check(history() == original)
            action("Янтарь", "викторин")
            balance("Янтарь", 5)
            reopen()
            balance("Янтарь", 5)
            balance("Мел", 0)
            val revised = history().toMutableList()
            val added = revised.single { it.team == "Янтарь" && it.points == 4 && matching("викторин").containsMatchIn(it.operation) }
            revised.remove(added)
            check(revised == original)
            check(screenshot("ledger-refined"))
            added.toString()
        }
    }

    private fun stopwatch() {
        fun elapsed(): Double? {
            val values = robot.texts()
            val clock = Regex("""^(?:(\d+):)?(\d+):(\d+)(?:[.,](\d+))?$""")
            val formatted = values.firstNotNullOfOrNull { clock.matchEntire(it.trim()) }
            if (formatted != null) {
                val (h, m, s, fraction) = formatted.destructured
                return (h.toIntOrNull() ?: 0) * 3600.0 + m.toInt() * 60.0 + s.toInt() +
                    (("0.$fraction").toDoubleOrNull() ?: 0.0)
            }
            return values.firstNotNullOfOrNull { text ->
                numeric(text.trim()).singleOrNull()?.takeIf { text.trim().matches(Regex("""\d+(?:[.,]\d+)?""")) }
            }
        }
        fun value(expected: Double) = robot.waitUntil { elapsed() == expected }
        step("timer") {
            value(0.0)
            button("^start$|^старт$|^начать$")
            robot.waitUntil(8_000) { (elapsed() ?: 0.0) > 0.0 }
            button("^stop$|^pause$|^пауза$|^остановить$")
            SystemClock.sleep(250)
            val stopped = checkNotNull(elapsed())
            check(stopped > 0)
            SystemClock.sleep(1_200)
            check(elapsed() == stopped)
            check(screenshot("stopwatch-stopped"))
            button("^reset$|^сброс")
            value(0.0)
            "Stopped at $stopped, held that value, then reset to zero"
        }
    }

    private fun canvas(): Rect {
        val title = ToolSpec.parsePersisted(saved().spec).title
        var bounds = Rect()
        robot.waitUntil {
            val node = accessibility(robot.diagnostics.freshRoot()).firstOrNull {
                it.isVisibleToUser && it.contentDescription?.startsWith("$title.") == true
            }
            bounds = Rect().also { node?.getBoundsInScreen(it) }
            if (bounds.width() >= 64 && bounds.height() >= 64) true
            else {
                if (!robot.scrollForward()) robot.scrollBackward()
                false
            }
        }
        return bounds
    }

    private fun touch(bounds: Rect, fractionX: Float, fractionY: Float) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                bounds.left + bounds.width() * fractionX, bounds.top + bounds.height() * fractionY, 0)
            try {
                check(robot.automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
    }

    private fun drawing() {
        data class CanvasInk(val blank: Boolean, val marks: List<Rect>)

        fun inspect(bounds: Rect): CanvasInk {
            val bitmap = checkNotNull(robot.automation.takeScreenshot())
            try {
                val stride = 4
                val interior = Rect(bounds).apply {
                    inset(stride, stride)
                    check(intersect(0, 0, bitmap.width, bitmap.height))
                }
                val width = interior.width() / stride
                val height = interior.height() / stride
                check(width > 0 && height > 0)
                val orange = mutableSetOf<Int>()
                val background = bitmap.getPixel(interior.left, interior.top)
                var blank = true
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val pixel = bitmap.getPixel(interior.left + x * stride, interior.top + y * stride)
                        val red = Color.red(pixel)
                        val green = Color.green(pixel)
                        val blue = Color.blue(pixel)
                        if (maxOf(kotlin.math.abs(red - Color.red(background)),
                                kotlin.math.abs(green - Color.green(background)),
                                kotlin.math.abs(blue - Color.blue(background))) > 16) blank = false
                        if (red >= 180 && green in 65..192 && red - green >= 30 && green - blue >= 35)
                            orange.add(y * width + x)
                    }
                }
                val marks = mutableListOf<Rect>()
                while (orange.isNotEmpty()) {
                    val first = orange.first()
                    orange.remove(first)
                    val pending = ArrayDeque<Int>().apply { add(first) }
                    val boundsOfMark = Rect()
                    while (pending.isNotEmpty()) {
                        val cell = pending.removeFirst()
                        val x = cell % width
                        val y = cell / width
                        val px = interior.left + x * stride
                        val py = interior.top + y * stride
                        boundsOfMark.union(px, py, px + stride, py + stride)
                        if (x > 0 && orange.remove(cell - 1)) pending.add(cell - 1)
                        if (x + 1 < width && orange.remove(cell + 1)) pending.add(cell + 1)
                        if (y > 0 && orange.remove(cell - width)) pending.add(cell - width)
                        if (y + 1 < height && orange.remove(cell + width)) pending.add(cell + width)
                    }
                    if (boundsOfMark.width() >= 2 * stride && boundsOfMark.height() >= 2 * stride) marks += boundsOfMark
                }
                return CanvasInk(blank, marks)
            } finally {
                bitmap.recycle()
            }
        }

        val countLabel = "(?:число |количество )?касани[йя]"
        fun verifyMark(fractionX: Float, fractionY: Float, count: Double): String {
            val touched = canvas()
            touch(touched, fractionX, fractionY)
            actions.put(JSONObject().put("step", "touch").put("action", "canvasTap")
                .put("x", fractionX.toDouble()).put("y", fractionY.toDouble()))
            val shown = visibleValue(countLabel, count)
            robot.waitUntil {
                val visible = canvas()
                val mark = inspect(visible).marks.singleOrNull()
                val x = visible.left + visible.width() * fractionX
                val y = visible.top + visible.height() * fractionY
                val tolerance = maxOf(8f, minOf(visible.width(), visible.height()) * 0.06f)
                mark != null && kotlin.math.abs(mark.exactCenterX() - x) <= tolerance &&
                    kotlin.math.abs(mark.exactCenterY() - y) <= tolerance
            }
            return shown
        }
        step("initial") {
            robot.waitUntil { inspect(canvas()).blank }
            visibleValue(countLabel, 0.0).also { check(screenshot("drawing-initial")) }
        }
        step("touch") {
            val first = verifyMark(.27f, .32f, 1.0)
            check(screenshot("drawing-first-touch"))
            val second = verifyMark(.71f, .64f, 2.0)
            check(screenshot("drawing-second-touch"))
            JSONArray().put(first).put(second)
        }
        step("reset") {
            button("^сброс")
            robot.waitUntil { inspect(canvas()).blank }
            visibleValue(countLabel, 0.0).also { check(screenshot("drawing-reset")) }
        }
    }

    private fun drawingLeaves(commands: JSONArray, parent: List<Int> = emptyList()): Sequence<Pair<List<Int>, JSONObject>> =
        sequence {
            for (index in 0 until commands.length()) {
                val command = commands.getJSONObject(index)
                val path = parent + index
                val children = command.optJSONArray("children")
                if (children == null) yield(path to command) else yieldAll(drawingLeaves(children, path))
            }
        }

    private fun artifactSnapshot(store: ToolStore): ArtifactSnapshot {
        val coordinator = ArtifactCoordinator.get(store, saved().id)
        return runBlocking { coordinator.ordered { snapshot(store) } }
    }

    private fun gameNumber(label: String): Int? {
        val pattern = matching("""(?:$label)\s*[:=]?\s*(\d+)""")
        return robot.texts().firstNotNullOfOrNull { pattern.find(it)?.groupValues?.get(1)?.toIntOrNull() }
    }
}
