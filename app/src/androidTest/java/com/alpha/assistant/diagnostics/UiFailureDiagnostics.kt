package com.alpha.assistant.diagnostics

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Test fixtures only. Unknown text is redacted before it reaches an evidence file. */
class UiFailureDiagnostics(private val fixture: String) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val labels = mutableSetOf("Mini-app content", "Mini-app library content", "Mini-app refinement", "Refine this mini-app", "Name", "Changed", "Apply: Name",
        "Сообщение ассистенту", "Отправить сообщение", "Голосовой запрос").apply {
        for (count in listOf(0, 1, 5)) add("$count owned events in this snapshot. Events added after this click remain separate. Uncertain events keep their saved ownership records.")
    }
    private var target: String? = null
    var state: () -> String = { "Model state: unknown" }

    fun target(label: String) { labels += label; target = label }

    fun freshRoot(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
        return automation.rootInActiveWindow?.also { if (android.os.Build.VERSION.SDK_INT < 33) it.refresh() }
    }

    private fun safe(value: CharSequence?): String = value?.toString()?.let {
        if (it.isEmpty() || it in labels) it else "<redacted>"
    } ?: "<null>"

    private fun node(node: AccessibilityNodeInfo?): String {
        if (node == null) return "<absent>"
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return "class=${node.className} text=${safe(node.text)} description=${safe(node.contentDescription)} " +
            "visible=${node.isVisibleToUser} enabled=${node.isEnabled} selected=${node.isSelected} " +
            "editable=${node.isEditable} focused=${node.isFocused} clickable=${node.isClickable} " +
            "bounds=$bounds actions=${node.actionList.map { it.id }} state=${safe(node.stateDescription)}"
    }

    private fun snapshot(selected: AccessibilityNodeInfo?): String = buildString {
        appendLine("uptimeMs=${SystemClock.uptimeMillis()} fixture=$fixture target=${safe(target)}")
        appendLine("revision=${InstrumentationRegistry.getArguments().getString("testedRevision", "not supplied")}")
        appendLine(state())
        appendLine("selected cached node: ${node(selected)}")
        var ancestor = selected?.parent
        var depth = 0
        while (ancestor != null && depth++ < 30) {
            appendLine("cached ancestor[$depth]: ${node(ancestor)}")
            ancestor = ancestor.parent
        }
        val cacheCleared = if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache() else false
        val root = automation.rootInActiveWindow
        appendLine("freshTreeUptimeMs=${SystemClock.uptimeMillis()} cacheCleared=$cacheCleared")
        fun dump(current: AccessibilityNodeInfo?, path: String, depth: Int) {
            if (current == null || depth > 40) return
            if (!cacheCleared) current.refresh()
            appendLine("$path ${node(current)}")
            repeat(current.childCount) { dump(current.getChild(it), "$path/$it", depth + 1) }
        }
        appendLine("fresh root tree:")
        dump(root, "root", 0)
    }

    fun capture(reason: String, selected: AccessibilityNodeInfo? = null, before: String? = null) {
        try {
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "suite-failure-diagnostics/$fixture").apply { mkdirs() }
            val stem = "$reason-${SystemClock.uptimeMillis()}"
            File(directory, "$stem.txt").writeText((before?.let { "Before action:\n$it\nAfter action:\n" } ?: "") + snapshot(selected))
            // Screenshots are permitted only while this disposable fixture owns the active window.
            if (automation.rootInActiveWindow?.packageName?.toString() == instrumentation.targetContext.packageName) {
                automation.takeScreenshot()?.let { bitmap ->
                    try { File(directory, "$stem.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    finally { bitmap.recycle() }
                }
            }
            println("UI_DIAGNOSTIC fixture=$fixture reason=$reason artifact=$stem")
        } catch (failure: Throwable) {
            // Evidence failures must not replace the original test assertion.
            println("UI_DIAGNOSTIC fixture=$fixture captureFailure=${failure.javaClass.name}")
        }
    }

    fun click(label: String, actionNode: AccessibilityNodeInfo?): Boolean {
        target(label)
        val before = try { "uptimeMs=${SystemClock.uptimeMillis()} selected cached node: ${node(actionNode)}" }
        catch (failure: Throwable) { "nodeMetadataFailure=${failure.javaClass.name}" }
        val clicked = try { actionNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
        catch (failure: Throwable) { capture("click-exception", actionNode, before); throw failure }
        if (!clicked) capture("click-failed", actionNode, before)
        return clicked
    }
}
