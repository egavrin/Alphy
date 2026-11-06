package com.alpha.assistant.memory

import java.text.Normalizer
import java.util.Locale

internal object RussianMemoryMatcher {
    fun match(
        query: String,
        purpose: MemoryPurpose,
        facts: () -> List<MemoryFact>,
    ): List<MemoryFact> {
        when (purpose) {
            MemoryPurpose.CHAT_DECISION, MemoryPurpose.CHAT_ANSWER, MemoryPurpose.AGENT_PLAN -> Unit
            MemoryPurpose.MINI_APP_GENERATION, MemoryPurpose.LESSON, MemoryPurpose.AUTOMATION -> return emptyList()
        }
        val queryTerms = stems(query)
        if (queryTerms.isEmpty()) return emptyList()

        return facts().asSequence()
            .map { fact -> fact to stems(fact.text).count(queryTerms::contains) }
            .filter { (_, overlap) -> overlap > 0 }
            .sortedWith(compareByDescending<Pair<MemoryFact, Int>> { it.second }.thenBy { it.first.id })
            .map { it.first }
            .distinctBy { it.text }
            .take(MAX_MATCHES)
            .toList()
    }

    private fun stems(text: String): Set<String> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT).replace('ё', 'е')
        return WORD.findAll(normalized)
            .map { it.value }
            .filter { word -> word.all { it in 'а'..'я' } }
            .map(::stem)
            .toSet()
    }

    // Snowball Russian uses RV for suffix rules and restricts derivational suffixes to R2.
    // https://snowballstem.org/algorithms/russian/stemmer.html
    private fun stem(word: String): String {
        val firstVowel = word.indexOfFirst { it in VOWELS }
        if (firstVowel == -1) return word
        val rv = firstVowel + 1
        val r2 = regionStart(word, regionStart(word, 0))
        val suffix = word.substring(rv)

        var stem = suffix.withoutEnding(PERFECTIVE_GERUND) ?: removeInflection(suffix)
        stem = stem.removeSuffix("и")
        val derivational = DERIVATIONAL.find(stem)
        if (derivational != null && rv + derivational.range.first >= r2) {
            stem = stem.take(derivational.range.first)
        }
        return word.take(rv) + tidyUp(stem)
    }

    // R1 starts after the first vowel-consonant pair. R2 applies that rule inside R1.
    private fun regionStart(word: String, start: Int): Int {
        for (index in start + 1 until word.length) {
            if (word[index - 1] in VOWELS && word[index] !in VOWELS) return index + 1
        }
        return word.length
    }

    private fun removeInflection(suffix: String): String {
        val withoutReflexive = suffix.withoutEnding(REFLEXIVE) ?: suffix
        val adjective = withoutReflexive.withoutEnding(ADJECTIVE)
        if (adjective != null) return adjective.withoutEnding(PARTICIPLE) ?: adjective
        return withoutReflexive.withoutEnding(VERB) ?: withoutReflexive.withoutEnding(NOUN) ?: withoutReflexive
    }

    private fun tidyUp(stem: String): String {
        if (stem.endsWith("нн")) return stem.dropLast(1)
        val withoutSuperlative = stem.withoutEnding(SUPERLATIVE) ?: return stem.removeSuffix("ь")
        return if (withoutSuperlative.endsWith("нн")) withoutSuperlative.dropLast(1) else withoutSuperlative
    }

    private fun String.withoutEnding(ending: Regex): String? =
        ending.find(this)?.let { take(it.range.first) }

    private const val MAX_MATCHES = 5
    private const val VOWELS = "аеиоуыэюя"
    private val WORD = Regex("[\\p{L}\\p{M}\\p{N}\\p{Pc}]+")

    // End anchors select the longest matching suffix. Lookbehind stays within RV.
    private val PERFECTIVE_GERUND = Regex("(?:[иы]в(?:шись|ши)?|(?<=[ая])в(?:шись|ши)?)$")
    private val REFLEXIVE = Regex("(?:ся|сь)$")
    private val ADJECTIVE = Regex("(?:ее|ие|ые|ое|ими|ыми|ей|ий|ый|ой|ем|им|ым|ом|его|ого|ему|ому|их|ых|ую|юю|ая|яя|ою|ею)$")
    private val PARTICIPLE = Regex("(?:ивш|ывш|ующ|(?<=[ая])(?:ем|нн|вш|ющ|щ))$")
    private val VERB = Regex(
        "(?:ила|ыла|ена|ейте|уйте|ите|или|ыли|ей|уй|ил|ыл|им|ым|ен|ило|ыло|ено|ят|ует|уют|ит|ыт|ены|ить|ыть|ишь|ую|ю" +
            "|(?<=[ая])(?:ла|на|ете|йте|ли|й|л|ем|н|ло|но|ет|ют|ны|ть|ешь|нно))$",
    )
    private val NOUN = Regex(
        "(?:а|ев|ов|ие|ье|е|иями|ями|ами|еи|ии|и|ией|ей|ой|ий|й|иям|ям|ием|ем|ам|ом|о|у|ах|иях|ях|ы|ь|ию|ью|ю|ия|ья|я)$",
    )
    private val DERIVATIONAL = Regex("ость?$")
    private val SUPERLATIVE = Regex("ейше?$")
}
