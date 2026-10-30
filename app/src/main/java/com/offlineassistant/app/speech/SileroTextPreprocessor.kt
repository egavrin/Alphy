package com.offlineassistant.app.speech

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SileroTextPreprocessor(metadataFile: File) {
    private val metadata = Json.parseToJsonElement(metadataFile.readText()).jsonObject
    private val symbolToId = metadata.getValue("symbol_to_id").jsonObject
        .mapValues { (_, value) -> value.jsonPrimitive.int.toLong() }
    private val startToken = metadata.getValue("sos_token").jsonPrimitive.content
    private val endToken = metadata.getValue("eos_token").jsonPrimitive.content

    fun prepare(originalText: String, accentedText: String): SileroSynthesisInput {
        val normalized = accentedText
            .lowercase()
            .replace('—', '–')
            .replace('‑', '-')
            .filter { character -> character.toString() in symbolToId || character == '^' }
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('^')
        require(normalized.any { it in 'а'..'я' }) { "Silero text contains no Russian speech" }
        val symbols = buildList {
            if (startToken.isNotEmpty()) add(startToken)
            normalized.forEach { add(it.toString()) }
            if (endToken.isNotEmpty()) add(endToken)
        }
        val sequence = symbols.map { symbol ->
            symbolToId[symbol] ?: error("Unknown Silero symbol: $symbol")
        }.toLongArray()
        return SileroSynthesisInput(
            sequence = sequence,
            durationRate = FloatArray(sequence.size) { 1f },
            pitchCoefficients = FloatArray(sequence.size) { 1f },
            typeIds = SileroSentenceClassifier.typeIds(originalText, sequence.size, startToken.isNotEmpty())
        )
    }
}

internal object SileroSentenceClassifier {
    private val sentenceSplit = Regex("(?<=[.!?])\\s+")
    private val words = Regex("[а-яёa-z]+", RegexOption.IGNORE_CASE)
    private val tagPatterns = listOf(
        Regex("[,\\s]+(правда|верно|да)\\s*\\?$", RegexOption.IGNORE_CASE),
        Regex("[,\\s]+(не\\s+так\\s+ли|не\\s+правда\\s+ли|разве\\s+не\\s+так|ведь\\s+так)\\s*\\?$", RegexOption.IGNORE_CASE),
        Regex("[,\\s]+ведь\\s*\\?$", RegexOption.IGNORE_CASE),
        Regex("[,\\s]+а\\s*\\?$", RegexOption.IGNORE_CASE)
    )
    private val whForms = setOf(
        "кто", "кого", "кому", "кем", "ком", "что", "чего", "чему", "чем", "чём", "чё", "чо",
        "где", "куда", "откуда", "когда", "почему", "зачем", "как", "почём", "отчего", "насколько",
        "сколько", "скольких", "скольким", "сколькими", "какой", "какая", "какое", "какие",
        "какого", "каких", "какому", "каким", "какими", "каком", "какую", "чей", "чья", "чьё",
        "чье", "чьи", "чьего", "чьей", "чьих", "чьему", "чьим", "чьими", "чьём", "чьем", "чью",
        "который", "которая", "которое", "которые", "которого", "которой", "которых", "которому",
        "которым", "которыми", "котором", "которую", "каков", "какова", "каково", "каковы"
    )
    private val leadingFillers = setOf(
        "а", "ну", "и", "так", "вот", "слышь", "слушай", "скажите", "скажи", "пожалуйста",
        "вобще", "вообще", "типа", "короче"
    )

    fun typeIds(text: String, sequenceLength: Int, hasStartToken: Boolean): LongArray {
        val sentences = text.trim().takeIf(String::isNotEmpty)?.split(sentenceSplit) ?: listOf("")
        val types = sentences.map(::classify)
        val perCharacter = buildList {
            sentences.forEachIndexed { index, sentence ->
                repeat(sentence.length) { add(types[index]) }
                if (index != sentences.lastIndex) add(types[index])
            }
        }
        val defaultType = types.firstOrNull() ?: Statement
        return LongArray(sequenceLength) { index ->
            val characterIndex = index - if (hasStartToken) 1 else 0
            when {
                index == 0 && hasStartToken -> defaultType
                characterIndex in perCharacter.indices -> perCharacter[characterIndex]
                else -> defaultType
            }.toLong()
        }
    }

    private fun classify(sentence: String): Int {
        var value = sentence.trim().trimOuterQuotes()
        if (value.isEmpty()) return Statement
        if (value.endsWith("?!") || value.endsWith("?…") || value.endsWith("?...")) {
            value = value.trimEnd('.', '!', '…')
        }
        if (value.endsWith('?')) {
            val normalized = value.trimOuterQuotes().replace("+", "").replace('ё', 'е')
                .replace(Regex("\\s+"), " ").trim()
            if (tagPatterns.any { it.containsMatchIn(normalized) }) return TagQuestion
            val content = words.findAll(normalized.lowercase()).map { it.value }
                .filterNot { it in leadingFillers }.take(4).toList()
            if (content.any { it in whForms }) return WhQuestion
            if (Regex("\\bили\\b", RegexOption.IGNORE_CASE).containsMatchIn(normalized)) return AlternativeQuestion
            return GeneralQuestion
        }
        return if (value.endsWith('!')) Exclamation else Statement
    }

    private fun String.trimOuterQuotes(): String {
        var value = trim()
        val opening = setOf('"', '«', '“', '„')
        val closing = setOf('"', '»', '”', '’')
        while (value.firstOrNull() in opening) value = value.drop(1).trim()
        while (value.lastOrNull() in closing) value = value.dropLast(1).trim()
        return value
    }

    private const val Statement = 0
    private const val WhQuestion = 1
    private const val GeneralQuestion = 2
    private const val AlternativeQuestion = 3
    private const val TagQuestion = 4
    private const val Exclamation = 5
}
