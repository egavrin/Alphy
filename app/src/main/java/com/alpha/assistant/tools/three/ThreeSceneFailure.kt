package com.alpha.assistant.tools.three

internal enum class ThreeSceneProblem(val guidance: String) {
    MODULE_LOAD("The scene module could not load. Export createScene and use the bundled imports."),
    WEBGL_UNAVAILABLE("The scene could not open a WebGL 2 context."),
    INIT_FAILED("createScene threw an error or returned invalid handlers."),
    EVENT_FAILED("The scene event handler failed."),
    FRAME_FAILED("The scene frame handler failed."),
    SNAPSHOT_FAILED("The scene snapshot handler failed."),
    DISPOSE_FAILED("The scene dispose handler failed."),
    CONTEXT_LOST("The scene lost its WebGL context."),
    INVALID_RESPONSE("The worker sent an invalid response."),
    WORKER_FAILED("The scene worker could not run."),
    RENDERER_CLOSED("The 3D renderer closed."),
    CLOSED("The 3D scene closed."),
    TIMEOUT("The scene worker did not answer in time."),
    FOREGROUND("Open Alphy in the foreground to check the 3D scene."),
    CAPTURE_FAILED("Alphy could not capture the 3D viewport."),
    BLANK_FRAME("Render visible content in the scene viewport."),
    INVALID_SNAPSHOT("Return valid state and optional native UI from snapshot."),
    STARTUP_MUTATION("Keep every supplied state value unchanged through createScene and open."),
    INVALID_AUDIO("Request an authorized audio clip only after a foreground action or drop."),
    INVALID_BINDING("Bind the planned check to a supported scene control or observable output."),
    TEXTURE_BINDING("Bind every approved texture to its intended UV-mapped mesh with await context.bindTexture, then render it on the supplied canvas. Keep the bound map attached."),
    ASSERTION_FAILED("The observed output did not match the planned check."),
    VISUAL_CHECK("The rendered scene did not pass visual review."),
    ;

    val code get() = "three_" + name.lowercase()

    companion object {
        fun fromWorker(code: String) = when (code) {
            "module_load" -> MODULE_LOAD
            "webgl_unavailable" -> WEBGL_UNAVAILABLE
            "init_failed" -> INIT_FAILED
            "texture_binding" -> TEXTURE_BINDING
            "event_failed" -> EVENT_FAILED
            "frame_failed" -> FRAME_FAILED
            "snapshot_failed" -> SNAPSHOT_FAILED
            "dispose_failed" -> DISPOSE_FAILED
            "context_lost" -> CONTEXT_LOST
            "invalid_response" -> INVALID_RESPONSE
            else -> WORKER_FAILED
        }
    }
}

internal enum class ThreeSceneOperation { INIT, OPEN, EVENT, FRAME, DISPOSE, CHECKPOINT }

internal class ThreeSceneFailure(
    val problem: ThreeSceneProblem,
    val operation: ThreeSceneOperation? = null,
    val step: Int? = null,
) : IllegalStateException(listOfNotNull(
    step?.let { "Step $it." }, operation?.let { "Operation ${it.name.lowercase()}." }, problem.guidance,
).joinToString(" ")) {
    fun atStep(index: Int) = ThreeSceneFailure(problem, operation, index)
}
