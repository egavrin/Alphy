package com.alpha.assistant.courses

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class CourseCue(val text: String, val label: String? = null, val checkedClip: String? = null) {
    init {
        courseText(text, 320, "Speech text")
        label?.let { courseText(it, 60, "Speech label") }
        require(checkedClip == null || checkedClip.matches(Regex("[a-z0-9_-]{1,48}")))
    }

    internal fun encode() = JSONObject().put("text", text).put("label", label).put("clip", checkedClip)

    internal companion object {
        fun decode(value: JSONObject) = CourseCue(value.getString("text"),
            value.opt("label") as? String, value.opt("clip") as? String)
    }
}

data class CourseNarration(
    val instruction: CourseCue,
    val examples: List<CourseCue> = emptyList(),
    val answer: CourseCue? = null,
) {
    init {
        require(examples.size <= 5)
    }

    internal fun encode() = JSONObject().put("instruction", instruction.encode())
        .put("examples", JSONArray(examples.map(CourseCue::encode))).put("answer", answer?.encode()).toString()

    internal companion object {
        fun decode(raw: String): CourseNarration {
            val value = JSONObject(raw)
            val examples = value.getJSONArray("examples")
            require(examples.length() <= 5)
            return CourseNarration(CourseCue.decode(value.getJSONObject("instruction")),
                List(examples.length()) { CourseCue.decode(examples.getJSONObject(it)) },
                value.optJSONObject("answer")?.let(CourseCue::decode))
        }
    }
}

/** The foreground player calls this session on Main. Only the current visit can publish playback. */
internal class CourseSpeechSession(
    private val scope: CoroutineScope,
    private val synthesize: suspend (CourseCue) -> ByteArray,
    private val play: suspend (ByteArray, () -> Unit) -> Unit,
) {
    data class State(val busy: Boolean = false, val active: CourseCue? = null, val failed: Boolean = false)

    private val status = MutableStateFlow(State())
    val state = status.asStateFlow()
    private val cache = mutableMapOf<CourseCue, ByteArray>()
    private var plan: CourseNarration? = null
    private var visible = false
    private var job: Job? = null
    private var epoch = 0L
    private var last = emptyList<CourseCue>()

    fun show(narration: CourseNarration?, visible: Boolean) {
        if (plan == narration && this.visible == visible) return
        stop()
        plan = narration
        this.visible = visible
        cache.clear()
        last = emptyList()
        if (visible) replay()
    }

    fun replay() {
        val current = plan ?: return
        run(listOf(current.instruction) + current.examples)
    }

    fun cue(cue: CourseCue) = run(listOf(cue))

    fun feedback(correct: Boolean, revision: Long) {
        val answer = plan?.answer.takeIf { correct }
        val words = readingFeedback(correct, revision)
        run(listOfNotNull(CourseCue(if (correct) "[warmly, with a gentle smile] $words" else words), answer))
    }

    fun retry() = run(last)

    fun stop() {
        epoch++
        job?.cancel()
        status.value = State()
    }

    private fun run(cues: List<CourseCue>) {
        if (!visible || cues.isEmpty()) return
        val previous = job
        stop()
        val id = epoch
        last = cues
        status.value = State(busy = true)
        job = scope.launch {
            var attempted: CourseCue? = null
            try {
                previous?.join()
                for (cue in cues) {
                    if (id != epoch) return@launch
                    status.value = State(busy = true)
                    attempted = cue
                    val audio = cache[cue] ?: synthesize(cue).also {
                        if (id == epoch) cache[cue] = it
                    }
                    if (id != epoch) return@launch
                    play(audio) {
                        if (id == epoch) status.value = State(busy = true, active = cue)
                    }
                }
                if (id == epoch) status.value = State()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (id == epoch) {
                    cache.remove(attempted)
                    status.value = State(failed = true)
                }
            }
        }
    }
}

private val praise = listOf(
    "Правильно, молодец.",
    "Умница. Всё верно.",
    "У тебя получилось. Я тобой горжусь.",
    "Отличная работа. Ты хорошо стараешься.",
)
private val encouragement = listOf(
    "Хорошая попытка! Попробуй ещё раз.",
    "У тебя получится. Посмотри на карточки и попробуй снова.",
    "Ты стараешься, и это здорово. Попробуй ещё раз.",
    "Не спеши. Послушай задание ещё раз и попробуй снова.",
)

internal fun readingFeedback(correct: Boolean, revision: Long): String {
    val phrases = if (correct) praise else encouragement
    return phrases[(revision % phrases.size).toInt()]
}
