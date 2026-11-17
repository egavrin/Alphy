package com.alpha.assistant.tools.agent

/** Bracket scan for model replies that are almost JSON. Brackets inside strings are ignored. */
internal object ReplyJson {
    data class Opener(val index: Int, val bracket: Char, val excerpt: String)

    private class Scan(val open: List<Int>, val inString: Boolean, val mismatch: Char?)

    private fun scan(text: String): Scan {
        val open = ArrayList<Int>()
        var inString = false
        var escaped = false
        for ((i, c) in text.withIndex()) {
            when {
                escaped -> escaped = false
                inString -> if (c == '\\') escaped = true else if (c == '"') inString = false
                c == '"' -> inString = true
                c == '{' || c == '[' -> open.add(i)
                c == '}' || c == ']' -> {
                    if (open.isEmpty() || text[open.last()] != if (c == '}') '{' else '[') return Scan(open, false, c)
                    open.removeAt(open.lastIndex)
                }
            }
        }
        return Scan(open, inString, null)
    }

    /** The closers that complete [text], or null when a string is open, a closer mismatches, or nothing is missing. */
    fun closers(text: String): String? {
        val scan = scan(text)
        if (scan.inString || scan.mismatch != null || scan.open.isEmpty()) return null
        return scan.open.reversed().joinToString("") { if (text[it] == '{') "}" else "]" }
    }

    /**
     * The array that a `}` closes by mistake. This is the one fault whose parser position is far from the cause.
     * Null for every other fault, so the parser position applies.
     */
    fun unclosedOpener(text: String): Opener? {
        val scan = scan(text)
        val index = scan.open.lastOrNull()?.takeIf { scan.mismatch == '}' } ?: return null
        return Opener(index, text[index], text.substring(maxOf(0, index - 39), index + 1))
    }
}
