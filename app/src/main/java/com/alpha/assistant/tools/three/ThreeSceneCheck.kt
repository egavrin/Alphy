package com.alpha.assistant.tools.three

import android.app.AlertDialog
import android.graphics.Bitmap
import android.util.Base64
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.alpha.assistant.tools.BehaviorBindingDiagnostic
import com.alpha.assistant.tools.BehaviorCheckFailure
import com.alpha.assistant.tools.BehaviorComparison
import com.alpha.assistant.tools.BehaviorFailureRule
import com.alpha.assistant.tools.BehaviorObservation
import com.alpha.assistant.tools.ToolGenerationRoute
import com.alpha.assistant.tools.ToolPreview
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolUiCatalog
import com.alpha.assistant.tools.generation.behaviorUiObservation
import com.alpha.assistant.tools.generation.GenerationBehavior
import com.alpha.assistant.tools.generation.GenerationBehavior.Binding
import com.alpha.assistant.tools.generation.GenerationBehavior.Step
import com.alpha.assistant.tools.generation.GeometryScope
import com.alpha.assistant.tools.generation.sameToolJson
import com.alpha.assistant.tools.generation.execute
import com.alpha.assistant.tools.generation.framesSinceCapture
import com.alpha.assistant.cloud.CloudFailure
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A renderer adapter for the shared generation scenario and binding contract. */
internal suspend fun checkThreeScene(
    spec: ToolSpec,
    id: String,
    revision: Int,
    initialState: String,
    scenario: GenerationBehavior.Scenario?,
    bindings: Map<String, Binding>,
    checkActive: suspend () -> Unit,
    visualReview: (suspend (List<String>) -> String?)?,
    visualReviewItems: List<String> = emptyList(),
    liveInputs: Set<String> = emptySet(),
): ToolPreview = withContext(Dispatchers.Main.immediate) {
    val activity = ThreeSceneForeground.activity()
    var session = ThreeSceneSession(activity, spec, id, revision,
        if (scenario == null) initialState else spec.initialState, spec.ui.toString())
    val accepted = CompletableDeferred<Boolean>()
    val lifecycle = (activity as LifecycleOwner).lifecycle
    fun decline() {
        if (accepted.complete(false)) session.close()
    }
    fun declined() = CloudFailure("three_declined", "The 3D preview was declined.")
    val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_PAUSE) decline()
    }
    val height = (300 * activity.resources.displayMetrics.density).toInt()
    val preview = FrameLayout(activity).apply {
        addView(session.web.view, FrameLayout.LayoutParams(-1, height))
    }
    val dialog = AlertDialog.Builder(activity).setTitle(spec.title).setView(preview)
        .setNegativeButton("Cancel") { _, _ -> decline() }
        .apply { if (scenario != null) setPositiveButton("Save scene", null) }
        .setOnCancelListener { decline() }.create()
    dialog.setOnDismissListener { decline() }
    try {
        lifecycle.addObserver(observer)
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            isEnabled = false
            setOnClickListener { accepted.complete(true) }
        }
        val window = checkNotNull(dialog.window)
        suspend fun start() {
            withTimeoutOrNull(2_500) {
                while (!session.web.view.isShown || session.web.view.width == 0 || session.web.view.height == 0) {
                    if (accepted.isCompleted && !accepted.await()) throw declined()
                    delay(16)
                }
            } ?: throw ThreeSceneFailure(ThreeSceneProblem.CAPTURE_FAILED)
            session.start()
        }
        suspend fun reopen(snapshot: ThreeSceneSnapshot, interactive: Boolean = false) {
            session.close()
            preview.removeAllViews()
            session = ThreeSceneSession(activity, spec, id, revision, snapshot.state, snapshot.ui, awaitAudio = !interactive)
            preview.addView(session.web.view, FrameLayout.LayoutParams(-1, height))
            start()
        }
        start()
        val initial = session.snapshot
        val crops = mutableListOf<String>()
        var collectCrops = scenario != null && visualReview != null
        suspend fun checkOpen() {
            checkActive()
            if (accepted.isCompleted && !accepted.await() ||
                !dialog.isShowing || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                throw declined()
        }
        suspend fun capture(region: Int): ScenePixels {
            checkOpen()
            delay(34)
            val bitmap = session.web.capture(window)
            try {
                val pixels = ScenePixels.read(bitmap, region)
                if (pixels.visible && collectCrops && crops.size < 3) crops += bitmap.dataUrl()
                return pixels
            } finally { bitmap.recycle() }
        }
        suspend fun ensureVisible() {
            var firstFrame = capture(0)
            repeat(12) {
                if (!firstFrame.visible) {
                    repeat(8) {
                        checkOpen()
                        session.frame(0)
                        delay(16)
                    }
                    firstFrame = capture(0)
                }
            }
            if (!firstFrame.visible) throw ThreeSceneFailure(ThreeSceneProblem.BLANK_FRAME)
        }
        ensureVisible()
        if (scenario == null) return@withContext ToolPreview(spec, initial.state, initial.ui)

        suspend fun read(slot: String): Any = when (val binding = bindings.getValue(slot)) {
            is Binding.Drawing -> {
                require(binding.surface == "scene" && binding.path.size == 1 && binding.path[0] in 0..4)
                capture(binding.path[0])
            }
            is Binding.Ui -> {
                val state = JSONObject(session.snapshot.state)
                val node = sceneNode(JSONObject(session.snapshot.ui), state, binding.path)
                behaviorUiObservation(node, state, scenario.slots.getValue(slot).kind) { _, _ ->
                    throw ThreeSceneFailure(ThreeSceneProblem.INVALID_BINDING)
                } ?: throw ThreeSceneFailure(ThreeSceneProblem.INVALID_BINDING)
            }
            is Binding.Id -> when (scenario.slots.getValue(slot).kind) {
                GenerationBehavior.Kind.AUDIO -> session.lastAudio ?: JSONObject.NULL
                GenerationBehavior.Kind.STATE -> {
                    val value = JSONObject(session.snapshot.state).opt(binding.value)
                    require(value is String || value is Boolean || value is Number) { "Bind the check to a saved scalar state key." }
                    value
                }
                else -> throw ThreeSceneFailure(ThreeSceneProblem.INVALID_BINDING)
            }
        }
        val activePointers = mutableSetOf<Int>()
        var stepIndex = 0
        fun assertionFailed(slot: String, comparison: BehaviorComparison, observation: BehaviorObservation,
            frames: Int? = null, requiredChanges: Int? = null, changes: Int? = null): Nothing {
            val binding = bindings[slot] as? Binding.Drawing
            val scope = binding?.let { GeometryScope.of(it, threeScene = true) }
            val target = when (scope) {
                GeometryScope.WHOLE -> "the full viewport"
                GeometryScope.SELECTED -> "the selected viewport region"
                null -> "the bound output"
            }
            val interval = frames?.let { " after $it frames" }.orEmpty()
            val reason = if (requiredChanges == null) "Expected ${comparison.wire} for $target$interval. " +
                "Observed ${observation.name.lowercase(java.util.Locale.ROOT).replace('_', ' ')}."
            else "Expected at least $requiredChanges changes in $target$interval. Observed $changes."
            throw BehaviorCheckFailure(stepIndex, reason, BehaviorBindingDiagnostic(ToolGenerationRoute.WHOLE_SCREEN,
                BehaviorFailureRule.ASSERTION_FAILED, stepIndex + 1, scenario.slots.keys.indexOf(slot) + 1,
                scenario.slots.getValue(slot).kind, bindingKind = if (binding != null) "drawing" else null,
                scope = scope, comparison = comparison, observation = observation, frames = frames,
                requiredChanges = requiredChanges, observedChanges = changes))
        }
        try {
            scenario.execute(
                before = { index ->
                    stepIndex = index
                    checkOpen()
                },
                read = ::read,
                perform = { step ->
                    when (step) {
                        is Step.Action -> {
                            val binding = bindings.getValue(step.slot)
                            val action = if (binding is Binding.Id) binding.value else {
                                require(binding is Binding.Ui)
                                sceneNode(JSONObject(session.snapshot.ui), JSONObject(session.snapshot.state), binding.path)
                                    .getString("action")
                            }
                            val value = step.value.takeUnless { it == JSONObject.NULL }
                            val button = if (value != null && step.slot !in liveInputs)
                                ToolUiCatalog.action(JSONObject(session.snapshot.ui), action)
                            else null
                            val press = button?.optString("type") == "button" && button.optString("action") == action
                            session.action(action, if (press) null else value)
                        }
                        is Step.Surface -> {
                            require((bindings.getValue(step.slot) as? Binding.Id)?.value == "scene")
                            when (step.event) {
                                "viewport" -> session.viewport()
                                "reopen" -> {
                                    activePointers.clear()
                                    reopen(session.snapshot)
                                }
                                "pointer" -> {
                                    val phase = checkNotNull(step.phase)
                                    require(when (phase) {
                                        "down" -> activePointers.add(0)
                                        "move" -> 0 in activePointers
                                        else -> activePointers.remove(0)
                                    }) { "The pointer sequence is invalid." }
                                    session.event(session.pointer("pointer$phase", 0, checkNotNull(step.x), checkNotNull(step.y)))
                                }
                                "start", "pause", "resume", "restart" -> session.event(JSONObject().put("type", step.event))
                                else -> error("Unsupported 3D scene action.")
                            }
                        }
                        is Step.Advance -> {
                            require((bindings.getValue(step.slot) as? Binding.Id)?.value == "scene")
                            var previous = step.observe?.let { read(it) as ScenePixels }
                            var changes = 0
                            repeat(step.count) {
                                checkOpen()
                                session.frame(50)
                                step.observe?.let {
                                    val current = read(it) as ScenePixels
                                    if (current.changedFrom(checkNotNull(previous))) changes++
                                    previous = current
                                }
                            }
                            if (changes < step.changes) assertionFailed(checkNotNull(step.observe), BehaviorComparison.MOTION,
                                BehaviorObservation.TOO_FEW_CHANGES, step.count, step.changes, changes)
                        }
                        else -> error("The shared checker handles observations.")
                    }
                },
                assert = { _, step, observed, expected ->
                        val equal = if (observed is ScenePixels && expected is ScenePixels) !observed.changedFrom(expected)
                            else sameToolJson(observed, expected)
                        val passed = when (step.test) {
                            "equals", "unchanged" -> equal
                            "changed" -> !equal
                            "greater" -> (observed as Number).toDouble() > (expected as Number).toDouble()
                            "less" -> (observed as Number).toDouble() < (expected as Number).toDouble()
                            "nonempty" -> when (observed) {
                                is ScenePixels -> observed.visible
                                is String -> observed.isNotBlank()
                                is Number -> observed.toLong() > 0
                                else -> false
                            }
                            else -> false
                        }
                        if (!passed) assertionFailed(step.slot, BehaviorComparison.from(step.test),
                            if (observed is ScenePixels && expected is ScenePixels) {
                                if (equal) BehaviorObservation.UNCHANGED else BehaviorObservation.CHANGED
                            } else BehaviorObservation.COMPARISON_FAILED, frames = scenario.framesSinceCapture(stepIndex, step))
                },
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: CloudFailure) {
            throw failure
        } catch (failure: BehaviorCheckFailure) {
            throw failure
        } catch (failure: ThreeSceneFailure) {
            throw failure.atStep(stepIndex + 1)
        } catch (_: Exception) {
            throw ThreeSceneFailure(ThreeSceneProblem.INVALID_BINDING, step = stepIndex + 1)
        }
        collectCrops = false
        val committed = try {
            reopen(ThreeSceneSnapshot(initialState, spec.ui.toString()), interactive = true)
            session.snapshot.also { ensureVisible() }
        } catch (failure: ThreeSceneFailure) {
            throw ThreeSceneFailure(failure.problem, ThreeSceneOperation.CHECKPOINT)
        }
        if (visualReview != null) {
            val issue = coroutineScope {
                val review = async { visualReview(crops) }
                select {
                    accepted.onAwait {
                        review.cancel()
                        throw declined()
                    }
                    review.onAwait { it }
                }
            }
            checkOpen()
            if (issue != null) throw ThreeSceneFailure(ThreeSceneProblem.VISUAL_CHECK)
        }
        val input = Channel<JSONObject>(32)
        session.web.view.setOnTouchListener { _, event ->
            for (pointer in session.pointerEvents(event)) input.trySend(pointer)
            true
        }
        val visual = visualReviewItems.firstOrNull()?.let { item ->
            val excerpt = item.replace(Regex("\\s+"), " ").take(180)
            val more = if (visualReviewItems.size > 1) " (${visualReviewItems.size - 1} more)" else ""
            "\nVisual review: $excerpt$more"
        } ?: "\nTry the scene and review its appearance."
        dialog.setMessage("Planned checks passed (${scenario.steps.size} steps).$visual")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
        var lastFrame = android.os.SystemClock.uptimeMillis()
        while (!accepted.isCompleted) {
            checkOpen()
            repeat(16) {
                val pointer = input.tryReceive().getOrNull() ?: return@repeat
                session.event(pointer)
            }
            delay(16)
            val now = android.os.SystemClock.uptimeMillis()
            session.frame((now - lastFrame).coerceIn(1, 50).toInt())
            lastFrame = now
        }
        if (!accepted.await()) throw declined()
        ToolPreview(spec, committed.state, committed.ui)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: Exception) {
        if (accepted.isCompleted && !accepted.await()) throw declined()
        throw failure
    } finally {
        lifecycle.removeObserver(observer)
        dialog.dismiss()
        session.close()
    }
}

private fun sceneNode(root: JSONObject, state: JSONObject, path: List<Int>): JSONObject {
    var node = root
    require(ToolUiCatalog.visible(node, state))
    for (index in path) {
        node = node.getJSONArray("children").getJSONObject(index)
        require(ToolUiCatalog.visible(node, state))
    }
    return node
}

private fun Bitmap.dataUrl() = ByteArrayOutputStream().use { out ->
    compress(Bitmap.CompressFormat.PNG, 100, out)
    "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

private class ScenePixels(private val pixels: IntArray) {
    val visible: Boolean
        get() {
            val background = pixels.asIterable().groupingBy { it and 0x00f0f0f0 }.eachCount()
                .maxBy { it.value }.key
            return pixels.count { difference(it, background) > 48 } >= pixels.size / 100
        }

    fun changedFrom(previous: ScenePixels) =
        pixels.indices.count { difference(pixels[it], previous.pixels[it]) > 30 } >= 3

    private fun difference(a: Int, b: Int): Int = (0..16 step 8).sumOf { shift ->
        abs((a shr shift and 255) - (b shr shift and 255))
    }

    companion object {
        fun read(bitmap: Bitmap, region: Int): ScenePixels {
            val offsetX = if (region == 2 || region == 4) bitmap.width / 2 else 0
            val offsetY = if (region == 3 || region == 4) bitmap.height / 2 else 0
            val width = if (region == 0) bitmap.width else bitmap.width / 2
            val height = if (region == 0) bitmap.height else bitmap.height / 2
            require(width > 0 && height > 0)
            return ScenePixels(IntArray(64 * 64) { i ->
                bitmap.getPixel(offsetX + i % 64 * width / 64, offsetY + i / 64 * height / 64)
            })
        }
    }
}
