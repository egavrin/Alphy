package com.offlineassistant.app.speech

import java.io.File
import kotlin.math.exp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class SileroNgramBatch(
    val ids: LongArray,
    val mask: FloatArray,
    val rowCount: Int,
    val columnCount: Int
)

data class SileroAccentLogits(
    val stress: FloatArray,
    val stressClasses: Int,
    val yo: FloatArray,
    val yoClasses: Int
)

data class SileroHomographBatch(
    val inputIds: LongArray,
    val rowCount: Int,
    val columnCount: Int,
    val starts: LongArray,
    val ends: LongArray
)

interface SileroLinguisticInference : AutoCloseable {
    fun warmUp()

    fun accent(batch: SileroNgramBatch): SileroAccentLogits

    fun resolveHomographs(batch: SileroHomographBatch): FloatArray
}

data class SileroFrontendBundle(
    val metadata: File,
    val accentor: File,
    val accentorNgrams: File,
    val accentorExceptions: File,
    val homosolver: File,
    val homosolverVocabulary: File,
    val homographs: File
) {
    fun requireComplete(): SileroFrontendBundle = apply {
        files().forEach { file -> require(file.isFile) { "Missing Silero frontend file: ${file.absolutePath}" } }
    }

    private fun files(): List<File> = listOf(
        metadata,
        accentor,
        accentorNgrams,
        accentorExceptions,
        homosolver,
        homosolverVocabulary,
        homographs
    )

    companion object {
        fun fromDirectory(directory: File): SileroFrontendBundle = SileroFrontendBundle(
            metadata = File(directory, "frontend.json"),
            accentor = File(directory, "accentor.onnx"),
            accentorNgrams = File(directory, "accentor-ngrams.json"),
            accentorExceptions = File(directory, "accentor-exceptions.json"),
            homosolver = File(directory, "homosolver.onnx"),
            homosolverVocabulary = File(directory, "homosolver-vocab.txt"),
            homographs = File(directory, "homographs.json")
        )
    }
}

class SileroRussianTextFrontend(
    bundle: SileroFrontendBundle,
    private val inference: SileroLinguisticInference
) : SileroTextFrontend {
    private val completeBundle = bundle.requireComplete()
    private val preprocessor = SileroTextPreprocessor(completeBundle.metadata)
    private val homographResolver = SileroHomographResolver(
        tokenizer = SileroWordPieceTokenizer(completeBundle.homosolverVocabulary),
        homographs = readStringLists(completeBundle.homographs),
        inference = inference
    )
    private val accentor = SileroAccentor(
        ngrams = readIntMap(completeBundle.accentorNgrams),
        exceptions = readIntLists(completeBundle.accentorExceptions),
        inference = inference
    )

    override fun prepare(text: String): SileroSynthesisInput {
        val homographsResolved = homographResolver.resolve(text)
        val accented = accentor.accent(homographsResolved)
        return preprocessor.prepare(text, accented)
    }

    override fun warmUp() = inference.warmUp()

    override fun close() = inference.close()

    private companion object {
        fun readIntMap(file: File): Map<String, Int> = Json.parseToJsonElement(file.readText()).jsonObject
            .mapValues { (_, value) -> value.jsonPrimitive.int }

        fun readIntLists(file: File): Map<String, IntArray> = Json.parseToJsonElement(file.readText()).jsonObject
            .mapValues { (_, value) -> value.jsonArray.map { it.jsonPrimitive.int }.toIntArray() }

        fun readStringLists(file: File): Map<String, List<String>> = Json.parseToJsonElement(file.readText()).jsonObject
            .mapValues { (_, value) -> value.jsonArray.map { it.jsonPrimitive.content } }
    }
}

internal class SileroHomographResolver(
    private val tokenizer: SileroWordPieceTokenizer,
    private val homographs: Map<String, List<String>>,
    private val inference: SileroLinguisticInference
) {
    private val wordPattern = Regex("(?=.*[а-яё])[а-яё+]+", RegexOption.IGNORE_CASE)

    fun resolve(sentence: String): String {
        val candidates = wordPattern.findAll(sentence).mapNotNull { match ->
            val variants = homographs[match.value.lowercase()] ?: return@mapNotNull null
            val marked = sentence.substring(0, match.range.first) + " [HOMO] " + match.value + " [/HOMO] " +
                sentence.substring(match.range.last + 1)
            val ids = tokenizer.encode(marked)
            Candidate(
                start = match.range.first,
                endExclusive = match.range.last + 1,
                original = match.value,
                variants = variants.sorted(),
                ids = ids,
                homoStart = ids.indexOf(tokenizer.homoStartId).toLong(),
                homoEnd = ids.indexOf(tokenizer.homoEndId).toLong()
            ).also {
                require(it.homoStart >= 0 && it.homoEnd >= 0) { "Silero homograph markers were not tokenized" }
            }
        }.toList()
        if (candidates.isEmpty()) return sentence

        val width = candidates.maxOf { it.ids.size }
        val padded = LongArray(candidates.size * width) { tokenizer.padId }
        candidates.forEachIndexed { row, candidate ->
            candidate.ids.copyInto(padded, row * width)
        }
        val logits = inference.resolveHomographs(
            SileroHomographBatch(
                inputIds = padded,
                rowCount = candidates.size,
                columnCount = width,
                starts = candidates.map { it.homoStart }.toLongArray(),
                ends = candidates.map { it.homoEnd }.toLongArray()
            )
        )
        require(logits.size == candidates.size) { "Silero homosolver returned ${logits.size} rows" }

        var resolved = sentence
        var offset = 0
        candidates.forEachIndexed { index, candidate ->
            require(candidate.variants.size == 2) { "Silero homograph requires exactly two variants" }
            val predicted = candidate.variants[if (logits[index] > 0f) 1 else 0]
            val stressIndex = predicted.indexOf('+')
            require(stressIndex >= 0) { "Silero homograph variant has no stress marker: $predicted" }
            val unmarked = predicted.replace("+", "")
            val caseRestored = candidate.original.zip(unmarked).joinToString("") { (source, target) ->
                if (source.isLowerCase()) target.lowercase() else target.uppercase()
            }
            val replacement = caseRestored.substring(0, stressIndex) + "+" + caseRestored.substring(stressIndex)
            val start = candidate.start + offset
            val end = candidate.endExclusive + offset
            resolved = resolved.replaceRange(start, end, replacement)
            offset += replacement.length - candidate.original.length
        }
        return resolved
    }

    private data class Candidate(
        val start: Int,
        val endExclusive: Int,
        val original: String,
        val variants: List<String>,
        val ids: LongArray,
        val homoStart: Long,
        val homoEnd: Long
    )
}

internal class SileroAccentor(
    private val ngrams: Map<String, Int>,
    private val exceptions: Map<String, IntArray>,
    private val inference: SileroLinguisticInference
) {
    private val separatorPattern = Regex("[\\s.,!?;:<>=()/\\\\]+")
    private val nonRussian = Regex("[^А-Яа-яёЁ]")

    fun accent(sentence: String): String {
        val tokens = tokenize(sentence)
        val batch = ngramBatch(tokens.map(Token::clean))
        val predictions = inference.accent(batch)
        require(predictions.stress.size == tokens.size * predictions.stressClasses)
        require(predictions.yo.size == tokens.size * predictions.yoClasses)
        return tokens.mapIndexed { index, token -> accentToken(token, index, predictions) }.joinToString("")
    }

    private fun accentToken(token: Token, index: Int, logits: SileroAccentLogits): String {
        var raw = token.raw
        val lower = raw.lowercase()
        if (!token.process) return raw
        val hasStress = '+' in lower
        val hasYo = 'ё' in lower
        if (hasStress && hasYo) return raw
        if (!hasStress && hasYo) {
            val yoPositions = lower.indices.filter { lower[it] == 'ё' }
            yoPositions.forEachIndexed { inserted, position ->
                raw = raw.substring(0, position + inserted) + "+" + raw.substring(position + inserted)
            }
            return raw
        }

        exceptions[token.clean]?.let { exception ->
            return accentException(raw, hasStress, exception)
        }

        val stressPrediction = logits.argmax(index, logits.stressClasses, stress = true)
        val yoPrediction = logits.argmax(index, logits.yoClasses, stress = false)
        var stressVowelIds = listOf(stressPrediction.index)
        var setStress = stressPrediction.probability > ConfidenceThreshold && !hasStress
        val setYo = yoPrediction.probability > ConfidenceThreshold
        if (hasStress) {
            stressVowelIds = lower.split('+').map { part ->
                Vowels.sumOf { vowel -> part.count { character -> character == vowel } }
            }
        }
        val positions = positions(lower, stressVowelIds, listOf(yoPrediction.index))
        positions.yo.forEach { position ->
            if (position in positions.stress && setYo && lower[position] == 'е') {
                raw = raw.replaceRange(position, position + 1, if (raw[position].isLowerCase()) "ё" else "Ё")
            }
        }
        var stressPositions = positions.stress
        if (positions.vowelCount == 1) {
            stressPositions = listOf(positions.firstVowel)
            setStress = true
        }
        if (!hasStress && setStress) {
            stressPositions.forEachIndexed { inserted, position ->
                raw = raw.substring(0, position + inserted) + "+" + raw.substring(position + inserted)
            }
        }
        return raw
    }

    private fun accentException(raw: String, hasStress: Boolean, exception: IntArray): String {
        require(exception.size >= 2) { "Invalid Silero accent exception" }
        val stress = exception[0]
        val yo = exception[1]
        if (!hasStress) {
            val yoRestored = if (yo >= 0) {
                raw.replaceRange(yo, yo + 1, if (raw[yo].isLowerCase()) "ё" else "Ё")
            } else {
                raw
            }
            return yoRestored.substring(0, stress) + "+" + yoRestored.substring(stress)
        }
        val stressPositions = raw.indices.filter { raw[it] == '+' }
        var result = raw.replace("+", "")
        if (yo >= 0 && yo + 1 in stressPositions) {
            result = result.replaceRange(yo, yo + 1, if (result[yo].isLowerCase()) "ё" else "Ё")
        }
        stressPositions.forEach { position -> result = result.substring(0, position) + "+" + result.substring(position) }
        return result
    }

    private fun positions(word: String, stressIds: List<Int>, yoIds: List<Int>): Positions {
        val vowelPositions = word.indices.filter { word[it] in Vowels }
        val yePositions = word.indices.filter { word[it] == 'е' }
        return Positions(
            stress = stressIds.filter { it in vowelPositions.indices }.map(vowelPositions::get),
            yo = yoIds.filter { it > 0 && it - 1 in yePositions.indices }.map { yePositions[it - 1] },
            vowelCount = vowelPositions.size,
            firstVowel = vowelPositions.firstOrNull() ?: -1
        )
    }

    private fun tokenize(sentence: String): List<Token> {
        val segments = mutableListOf<String>()
        var start = 0
        separatorPattern.findAll(sentence).forEach { match ->
            segments += sentence.substring(start, match.range.first)
            segments += match.value
            start = match.range.last + 1
        }
        segments += sentence.substring(start)
        return segments.flatMap { segment ->
            val parts = splitPreservingEmpty(segment, '-')
            parts.mapIndexed { index, part ->
                val raw = if (index < parts.lastIndex) "$part-" else part
                val clean = nonRussian.replace(raw.lowercase(), "")
                Token(raw, clean, clean.isNotEmpty() && (index != parts.lastIndex || part != "то"))
            }
        }
    }

    private fun ngramBatch(words: List<String>): SileroNgramBatch {
        val unknown = ngrams.getValue("UNK")
        val rows = words.map { word ->
            val wrapped = "<$word>"
            buildList {
                for (size in 1..word.length + 3) {
                    for (start in 0..wrapped.length - size) {
                        ngrams[wrapped.substring(start, start + size)]?.let(::add)
                    }
                }
                if (word.isEmpty()) ngrams[word]?.let(::add)
            }.ifEmpty { listOf(unknown) }
        }
        val width = rows.maxOf { it.size }
        val ids = LongArray(rows.size * width)
        val mask = FloatArray(rows.size * width)
        rows.forEachIndexed { row, values ->
            values.forEachIndexed { column, value ->
                val index = row * width + column
                ids[index] = value.toLong()
                mask[index] = 1f
            }
        }
        return SileroNgramBatch(ids, mask, rows.size, width)
    }

    private fun SileroAccentLogits.argmax(row: Int, classes: Int, stress: Boolean): Prediction {
        val values = if (stress) this.stress else yo
        val offset = row * classes
        var best = 0
        for (index in 1 until classes) if (values[offset + index] > values[offset + best]) best = index
        val max = values[offset + best]
        var denominator = 0.0
        for (index in 0 until classes) denominator += exp((values[offset + index] - max).toDouble())
        return Prediction(best, (1.0 / denominator).toFloat())
    }

    private fun splitPreservingEmpty(value: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        value.forEachIndexed { index, character ->
            if (character == delimiter) {
                result += value.substring(start, index)
                start = index + 1
            }
        }
        result += value.substring(start)
        return result
    }

    private data class Token(val raw: String, val clean: String, val process: Boolean)
    private data class Prediction(val index: Int, val probability: Float)
    private data class Positions(
        val stress: List<Int>,
        val yo: List<Int>,
        val vowelCount: Int,
        val firstVowel: Int
    )

    private companion object {
        const val ConfidenceThreshold = 0.5f
        const val Vowels = "аоуыэиеяёю"
    }
}
