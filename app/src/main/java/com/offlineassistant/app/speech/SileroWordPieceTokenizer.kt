package com.offlineassistant.app.speech

import java.io.File

internal class SileroWordPieceTokenizer(vocabularyFile: File) {
    private val vocabulary = vocabularyFile.readLines().mapIndexed { index, token -> token to index }.toMap()
    private val neverSplit = setOf("[HOMO]", "[/HOMO]")
    private val unknownId = vocabulary.getValue("[UNK]")
    private val startId = vocabulary.getValue("[CLS]")
    private val endId = vocabulary.getValue("[SEP]")
    val padId: Long = vocabulary.getValue("[PAD]").toLong()
    val homoStartId: Long = vocabulary.getValue("[HOMO]").toLong()
    val homoEndId: Long = vocabulary.getValue("[/HOMO]").toLong()

    fun encode(text: String): LongArray {
        val basicTokens = basicTokenize(text)
        val pieces = basicTokens.flatMap(::wordPieces)
        return buildList {
            add(startId.toLong())
            pieces.forEach { token -> add((vocabulary[token] ?: unknownId).toLong()) }
            add(endId.toLong())
        }.toLongArray()
    }

    private fun basicTokenize(text: String): List<String> {
        val cleaned = buildString {
            text.forEach { character ->
                when {
                    character.code == 0 || character.code == 0xfffd || character.isControl() -> Unit
                    character.isTokenizerWhitespace() -> append(' ')
                    character.code.isChineseCodePoint() -> append(' ').append(character).append(' ')
                    else -> append(character)
                }
            }
        }
        return cleaned.trim().split(Regex("\\s+")).flatMap { token ->
            if (token in neverSplit) listOf(token) else token.splitOnPunctuation()
        }
    }

    private fun wordPieces(token: String): List<String> {
        if (token in neverSplit) return listOf(token)
        if (token.length > MaxCharactersPerWord) return listOf("[UNK]")
        val pieces = mutableListOf<String>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var match: String? = null
            while (start < end) {
                val candidate = token.substring(start, end).let { if (start == 0) it else "##$it" }
                if (candidate in vocabulary) {
                    match = candidate
                    break
                }
                end--
            }
            if (match == null) return listOf("[UNK]")
            pieces += match
            start = end
        }
        return pieces
    }

    private fun String.splitOnPunctuation(): List<String> {
        val output = mutableListOf<String>()
        val current = StringBuilder()
        forEach { character ->
            if (character.isTokenizerPunctuation()) {
                if (current.isNotEmpty()) output += current.toString().also { current.clear() }
                output += character.toString()
            } else {
                current.append(character)
            }
        }
        if (current.isNotEmpty()) output += current.toString()
        return output
    }

    private fun Char.isControl(): Boolean {
        if (this == '\t' || this == '\n' || this == '\r') return false
        return Character.getType(this) in setOf(
            Character.CONTROL.toInt(),
            Character.FORMAT.toInt(),
            Character.PRIVATE_USE.toInt(),
            Character.SURROGATE.toInt(),
            Character.UNASSIGNED.toInt()
        )
    }

    private fun Char.isTokenizerWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r' || Character.getType(this) == Character.SPACE_SEPARATOR.toInt()

    private fun Char.isTokenizerPunctuation(): Boolean {
        val code = code
        if (code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126) return true
        return Character.getType(this) in setOf(
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt()
        )
    }

    private fun Int.isChineseCodePoint(): Boolean = this in 0x4E00..0x9FFF || this in 0x3400..0x4DBF || this in 0x20000..0x2A6DF ||
        this in 0x2A700..0x2B73F || this in 0x2B740..0x2B81F || this in 0x2B820..0x2CEAF ||
        this in 0xF900..0xFAFF || this in 0x2F800..0x2FA1F

    private companion object {
        const val MaxCharactersPerWord = 100
    }
}
