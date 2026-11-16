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

internal data class AppLanguage(val tag: String)

/** Model-selected language and output checks for one generation operation. */
internal object GenerationLanguage {
    fun parse(tag: Any?): AppLanguage {
        require(tag is String && validAppLanguageTag(tag)) { "$.language requires a lowercase two- or three-letter language tag." }
        return AppLanguage(tag)
    }

    private val words = Regex("""\p{L}[\p{L}\p{M}]*""")
    private val languageId by lazy {
        LanguageIdentification.getClient(LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(0.75f).build())
    }

    fun name(tag: String): String = "${Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH)} ($tag)"

    fun guidance(tag: String): String = """
        The selected mini-app interface language is ${name(tag)} for this operation.
        Write titles, descriptions, controls, options, empty states, errors, and generated UI for every event in that language.
        Keep user-supplied names and quoted content, units, and deliberately multilingual content as requested.
        A location, source language, example, or assistant brief never changes the interface language.
    """.trimIndent()

    /** Checks only authored copy. IDs, bindings, values, and runtime record data are not read. */
    class Check(private val language: AppLanguage) {
        private val checked = mutableSetOf<List<String>>()

        suspend fun mismatches(ui: JSONObject, title: String = "", description: String = ""): Boolean {
            val controls = labels(ui)
            return mismatches(controls) || mismatches(listOf(title, description) + controls)
        }

        private suspend fun mismatches(copy: List<String>): Boolean {
            val phrases = copy.distinct().filter { words.findAll(it).count() >= 2 }
            if (phrases.size < 2 || checked.size >= 16 || !checked.add(phrases)) return false
            val detected = identify(phrases.joinToString(". "), minLetters = 24, confidence = 0.90f)
            return detected != null && detected != language.tag
        }
    }

    private fun labels(ui: JSONObject): List<String> = buildList {
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
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

    /** The language tag of [text], or null when the text is too short or the identification is not confident. */
    // A one-word request such as "stopwatch" needs a statement too. The confidence limit rejects ambiguous words such as "bmi".
    suspend fun identifyText(text: String): String? = identify(text, minLetters = 4)

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
