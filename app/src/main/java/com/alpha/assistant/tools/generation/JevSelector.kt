package com.alpha.assistant.tools.generation

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import kotlin.math.abs

/** Return the answers JSON. Return an empty string if the backend cannot answer. */
internal typealias JevAsk = suspend (body: String) -> String

internal data class JevQuestion(val id: String, val prompt: String, val choices: List<String> = emptyList()) {
    init {
        require(choices.isEmpty() || choices.size in 2..26)
    }

    val letters = choices.indices.map { ('A' + it).toString() }

    fun json(): JSONObject = JSONObject().put("id", id).put("prompt", prompt).apply {
        if (choices.isEmpty()) {
            put("type", "noul")
        } else {
            put("type", "choice")
            put("choices", JSONObject().apply { letters.zip(choices).forEach { (letter, text) -> put(letter, text) } })
        }
    }
}

internal sealed interface JevAnswer {
    data class Choice(val index: Int) : JevAnswer
    data class Noul(val probability: Double) : JevAnswer
}

internal data class JevResult(
    val answers: Map<String, JevAnswer>,
    val failure: PartsContractFailure? = null
) {
    fun choice(id: String): Int? = (answers[id] as? JevAnswer.Choice)?.index
    fun probability(id: String): Double? = (answers[id] as? JevAnswer.Noul)?.probability
}

internal object JevSelector {
    suspend fun ask(
        backend: JevAsk, stage: String, plan: NeedsPlan, questions: List<JevQuestion>,
        presentationQuestions: List<JevQuestion> = emptyList()
    ): JevResult {
        val requested = questions + presentationQuestions
        if (requested.isEmpty()) return JevResult(emptyMap())
        val path = "$.selector.$stage"
        val body = JSONObject().put("stage", stage).put("title", plan.title).put("description", plan.description)
            .put("questions", jsonArray(requested.map(JevQuestion::json)))
        val raw = try {
            backend(body.toString())
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            ""
        }
        return try {
            requireParts(raw.isNotBlank(), path, "a selector response")
            parse(raw, path, requested, presentationQuestions.map(JevQuestion::id).toSet())
        } catch (failure: PartsContractFailure) {
            JevResult(emptyMap(), failure)
        }
    }

    private fun probability(value: Any?, path: String): Double {
        val number = (value as? Number)?.toDouble()
        requireParts(number != null && number in 0.0..1.0, path, "a probability from 0 to 1")
        return checkNotNull(number)
    }

    private fun parse(raw: String, path: String, questions: List<JevQuestion>, presentationIds: Set<String>): JevResult {
        val requested = questions.associateBy(JevQuestion::id)
        val root = partsObject(raw, path)
        root.requireKeys(path, setOf("answers"))
        val seen = mutableSetOf<String>()
        val answers = mutableMapOf<String, JevAnswer>()
        var presentationFailure: PartsContractFailure? = null
        root.items("answers", (questions.size - presentationIds.size)..questions.size, "$path.answers").forEachIndexed { index, item ->
            val at = "$path.answers[$index]"
            val row = item as? JSONObject ?: throw PartsContractFailure(at, "an answer object")
            val id = row.opt("id") as? String
            val question = requested[id] ?: throw PartsContractFailure("$at.id", "a requested question ID")
            try {
                requireParts(seen.add(question.id), "$at.id", "one answer per question")
                answers[question.id] = parseAnswer(row, question, at)
            } catch (failure: PartsContractFailure) {
                if (question.id !in presentationIds) throw failure
                presentationFailure = presentationFailure ?: failure
            }
        }
        requireParts(requested.keys.all { it in presentationIds || it in answers },
            "$path.answers", "one answer per functional question")
        if (!answers.keys.containsAll(presentationIds) && presentationFailure == null) {
            presentationFailure = PartsContractFailure("$path.answers", "one answer per presentation question")
        }
        if (presentationFailure != null) presentationIds.forEach(answers::remove)
        return JevResult(answers, presentationFailure)
    }

    private fun parseAnswer(row: JSONObject, question: JevQuestion, path: String): JevAnswer {
        if (question.choices.isEmpty()) {
            row.requireKeys(path, setOf("id", "probability"))
            return JevAnswer.Noul(probability(row.opt("probability"), "$path.probability"))
        }
        row.requireKeys(path, setOf("id", "choice", "confidence", "probabilities"))
        val chosen = question.letters.indexOf(row.opt("choice"))
        requireParts(chosen >= 0, "$path.choice", "one of the offered choices")
        probability(row.opt("confidence"), "$path.confidence")
        val scores = row.optJSONObject("probabilities")
            ?: throw PartsContractFailure("$path.probabilities", "a probability object")
        scores.requireKeys("$path.probabilities", question.letters.toSet())
        val distribution = question.letters.map { probability(scores.opt(it), "$path.probabilities.$it") }
        requireParts(abs(distribution.sum() - 1.0) <= 0.005, "$path.probabilities", "probabilities that sum to 1")
        requireParts(distribution.indices.all { it == chosen || distribution[it] < distribution[chosen] },
            "$path.choice", "the sole highest-probability choice")
        return JevAnswer.Choice(chosen)
    }
}
