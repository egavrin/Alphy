package com.alpha.assistant.tools

import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Trusted language and exact user-supplied literals for an app generation operation. */
data class AppLanguage internal constructor(val tag: String, val literals: Set<String>) {
    internal fun encode(): String = JSONObject().put("tag", tag).put("literals", JSONArray(literals.toList())).toString()
    internal companion object {
        fun decode(value: String): AppLanguage = JSONObject(value).let { json ->
            val literals = json.getJSONArray("literals")
            AppLanguage(json.getString("tag"), (0 until literals.length()).map(literals::getString).toSet())
        }
    }
}

/** Language for one generation operation. Location, examples, and model-written briefs are not inputs. */
internal object GenerationLanguage {
    private val quotes = Regex("""“[^”]+”|«[^»]+»|"[^"\r\n]+"|[\x60]+[^\x60]+[\x60]+""")
    private val languageNames = Locale.getISOLanguages().map { Locale.forLanguageTag(it).language }.distinct().associateBy {
        Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT)
    } + mapOf(
        "английском" to "en", "русском" to "ru", "итальянском" to "it", "испанском" to "es",
        "немецком" to "de", "французском" to "fr", "португальском" to "pt", "китайском" to "zh",
        "японском" to "ja", "украинском" to "uk",
    )
    private val namePattern = languageNames.keys.sortedByDescending(String::length)
        .joinToString("|", transform = Regex::escape)
    private val output = "app|widget|calculator|timer|tracker|interface|ui|labels|controls|output"
    private val instruction = Regex(
        """(?iu)(?<!\p{L})(?:(?:$output)\s+(?:language\s*[:=]?\s*|(?:should\s+be\s+)?in\s+)|(?:интерфейс|виджет|приложение|калькулятор|таймер|подписи|кнопки)\s+на\s+)($namePattern)(?!\p{L})"""
    )
    private val directedLanguage = Regex(
        """(?iu)(?<!\p{L})(?:(?:use|write)\s+|(?:сделай|сделайте|напиши|пиши)\s+на\s+)($namePattern)(?!\p{L})(?=\s+(?:for\s+(?:the\s+)?(?:$output)|and\s+(?:$namePattern)|or\s+(?:$namePattern))\b|\s*[.!?;\r\n]|\s*$)"""
    )
    private val negation = Regex("""(?iu)(?<!\p{L})(?:not|never|don['’]t|не)(?!\p{L})""")
    private val clauseBreak = Regex("""(?iu)[.!?;,\r\n]|\s+(?:and|but|и|а|но)\s+""")
    private val mixed = Regex("""(?iu)^\s+(?:and|or|и|или)\s+(?:$namePattern)(?!\p{L})""")
    private val words = Regex("""\p{L}[\p{L}\p{M}]*""")
    private val languageId by lazy {
        LanguageIdentification.getClient(LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(0.75f).build())
    }

    suspend fun resolve(request: String, existingUi: String?, existingLanguage: String?): AppLanguage? {
        val tag = resolveTag(request, existingUi, existingLanguage) ?: return null
        return AppLanguage(tag, quotes.findAll(request).map { it.value.trim(' ', '"', '«', '»', '“', '”', 96.toChar()) }.toSet())
    }

    private suspend fun resolveTag(request: String, existingUi: String?, existingLanguage: String?): String? {
        val text = quotes.replace(request, " ")
        val explicit = (instruction.findAll(text) + directedLanguage.findAll(text)).filter { match ->
            val prefix = text.substring(0, match.range.first)
            !negation.containsMatchIn(prefix.split(clauseBreak).last())
        }.toList()
        if (explicit.any { mixed.containsMatchIn(text.substring(it.range.last + 1)) }) return null
        val tags = explicit.mapNotNull { languageNames[it.groupValues[1].lowercase(Locale.ROOT)] }.distinct()
        if (tags.size > 1) return null
        tags.singleOrNull()?.let { return it }
        val currentLanguage = existingLanguage ?: existingUi?.let { identify(labels(JSONObject(it)).joinToString(". ")) }
        return currentLanguage ?: identify(text)
    }

    fun name(tag: String): String = "${Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH)} ($tag)"

    fun guidance(tag: String): String = """
        The host resolved the mini-app interface language to ${name(tag)} for this operation.
        Write titles, descriptions, controls, options, empty states, errors, and generated UI for every event in that language.
        Keep user-supplied names and quoted content, units, and deliberately multilingual content as requested.
        A location, source language, example, or assistant brief never changes the interface language.
    """.trimIndent()

    /** Checks only authored copy. IDs, bindings, values, and runtime record data are not read. */
    class Check(private val language: AppLanguage) {
        private val checked = mutableSetOf<List<String>>()

        suspend fun mismatches(ui: JSONObject, title: String = "", description: String = "",
            skipNativeParts: Boolean = false): Boolean {
            val controls = labels(ui, skipNativeParts).filterNot(language.literals::contains)
            return mismatches(controls) || mismatches((listOf(title, description) + controls).filterNot(language.literals::contains))
        }

        private suspend fun mismatches(copy: List<String>): Boolean {
            val phrases = copy.distinct().filter { words.findAll(it).count() >= 2 }
            if (phrases.size < 2 || checked.size >= 16 || !checked.add(phrases)) return false
            val detected = identify(phrases.joinToString(". "), minLetters = 24, confidence = 0.90f)
            return detected != null && detected != language.tag
        }
    }

    private fun labels(ui: JSONObject, skipNativeParts: Boolean = false): List<String> = buildList {
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    if (skipNativeParts && value.has("partInstanceId")) return
                    for (key in listOf("text", "emptyText")) {
                        value.optString(key).takeIf(String::isNotBlank)?.let(::add)
                    }
                    for (key in listOf("children", "rowTemplate", "options", "rowActions", "columns", "series")) {
                        visit(value.opt(key))
                    }
                }
                is JSONArray -> repeat(value.length()) { visit(value.opt(it)) }
            }
        }
        visit(ui)
    }

    private suspend fun identify(text: String, minLetters: Int = 12, confidence: Float = 0.85f): String? {
        val sample = text.take(1024)
        if (sample.count(Char::isLetter) < minLetters) return null
        return try {
            val result = languageId.identifyPossibleLanguages(sample).awaitResult()
                .maxByOrNull { it.confidence } ?: return null
            Locale.forLanguageTag(result.languageTag).language.takeIf {
                it.isNotEmpty() && it != "und" && result.confidence >= confidence
            }
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { result ->
            when {
                result.isCanceled -> continuation.cancel()
                result.isSuccessful -> continuation.resume(result.result)
                else -> continuation.resumeWithException(checkNotNull(result.exception))
            }
        }
    }
}
