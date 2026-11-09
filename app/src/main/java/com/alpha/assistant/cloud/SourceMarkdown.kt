package com.alpha.assistant.cloud

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Code
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

internal val markdownParser = Parser.builder().maxOpenBlockParsers(64).maxInlineNesting(64)
    .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
    .extensions(listOf(TablesExtension.builder().maxCells(4096).build(), StrikethroughExtension.create())).build()

internal val sourceReference = Regex("""\[(\d{1,3})]""")
private val sourceUrls = Regex("https?://[^\\s<>\"`]+",RegexOption.IGNORE_CASE)

internal fun validateSourceMarkdown(text: String, path: String, sourceCount: Int, allowed: Set<String>) {
    fun invalid(message: String): Nothing =
        throw CloudFailure("source_attribution", message, ResponseShape("decision.source_attribution", path, "string"))

    fun invalidUrl(): Nothing =
        invalid("The reply cites a URL that was not supplied for this answer. No sourced answer or app was accepted.")

    fun checkBareUrls(literal: String) {
        sourceUrls.findAll(literal).forEach { match ->
            var url = match.value
            while (url !in allowed && url.isNotEmpty() && url.last() in ".,;:!?)]}") url = url.dropLast(1)
            if (url !in allowed) invalidUrl()
        }
    }

    val document = runCatching { markdownParser.parse(text) }
        .getOrElse { invalid("The reply could not be checked for source attribution.") }
    val bareText = text.toCharArray()
    fun checkDestination(node: Node, destination: String, title: String?) {
        if (destination !in allowed || !validSourceUrl(destination)) invalidUrl()
        title?.let(::checkBareUrls)
        // Exclude Markdown destination syntax from the bare URL scan. CommonMark already decoded it.
        node.sourceSpans.forEach { span -> bareText.fill(' ', span.inputIndex, span.inputIndex + span.length) }
    }

    fun visit(node: Node, parentLabel: StringBuilder?) {
        val label = if (node is Link || node is Image) StringBuilder() else parentLabel
        when (node) {
            is Link -> checkDestination(node, node.destination, node.title)
            is Image -> checkDestination(node, node.destination, node.title)
            is LinkReferenceDefinition -> {
                checkDestination(node, node.destination, node.title)
                checkBareUrls(node.label)
            }
            is Text -> if (label != null) label.append(node.literal) else {
                if (sourceReference.findAll(node.literal).any { it.groupValues[1].toInt() !in 1..sourceCount })
                    invalid("The reply uses a citation identifier that search did not return. Use only the displayed source numbers.")
            }
            is Code -> label?.append(node.literal)
            is HtmlInline -> label?.append(node.literal)
            is SoftLineBreak, is HardLineBreak -> label?.append('\n')
        }
        generateSequence(node.firstChild) { it.next }.forEach { visit(it, label) }
        if (label != null && label !== parentLabel) {
            checkBareUrls(label.toString())
            parentLabel?.append(' ')?.append(label)?.append(' ')
        }
    }
    visit(document, null)
    checkBareUrls(bareText.concatToString())
}
