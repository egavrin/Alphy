package com.alpha.assistant.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.alpha.assistant.R
import com.alpha.assistant.cloud.validSourceUrl
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.*
import org.commonmark.parser.Parser

private val markdownParser = Parser.builder().maxOpenBlockParsers(64).maxInlineNesting(64)
    .extensions(listOf(TablesExtension.builder().maxCells(4096).build(),StrikethroughExtension.create())).build()

/** HTML and image references remain text. */
@Composable internal fun rememberMarkdownText(source: String, linkError: () -> Unit): AnnotatedString {
    val uriHandler = LocalUriHandler.current
    val color = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val document = remember(source) { runCatching { markdownParser.parse(source) }.getOrNull() }
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val imagePrefix = stringResource(R.string.chat_markdown_image_prefix)
    return remember(document,source,color,codeBackground,codeColor,uriHandler,linkError,imagePrefix) {
        if(document == null) AnnotatedString(source) else buildAnnotatedString {
            lateinit var render: (Node,Int) -> Unit
            fun children(node: Node, depth: Int) {
                var child = node.firstChild
                while(child != null) { render(child,depth); child = child.next }
            }
            val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace,color = codeColor,background = codeBackground)
            render = { node,depth ->
                when(node) {
                    is org.commonmark.node.Text -> append(node.literal)
                    is Code -> withStyle(codeStyle) { append(node.literal) }
                    is FencedCodeBlock -> { withStyle(codeStyle) { append(node.literal.trimEnd('\n')) }; append("\n\n") }
                    is IndentedCodeBlock -> { withStyle(codeStyle) { append(node.literal.trimEnd('\n')) }; append("\n\n") }
                    is Heading -> { withStyle(SpanStyle(fontWeight = FontWeight.Bold,fontSize = (25 - node.level).sp)) { children(node,depth) }; append("\n\n") }
                    is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { children(node,depth) }
                    is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { children(node,depth) }
                    is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { children(node,depth) }
                    is Link -> {
                        if(validSourceUrl(node.destination)) {
                            pushLink(LinkAnnotation.Url(node.destination,TextLinkStyles(SpanStyle(color = color,textDecoration = TextDecoration.Underline))) {
                                runCatching { uriHandler.openUri(node.destination) }.onFailure { linkError() }
                            })
                            children(node,depth); pop()
                        } else children(node,depth)
                    }
                    is Image -> { append(imagePrefix); children(node,depth); append("]") }
                    is HtmlInline -> append(node.literal)
                    is HtmlBlock -> { withStyle(codeStyle) { append(node.literal) }; append("\n") }
                    is SoftLineBreak -> append("\n")
                    is HardLineBreak -> append("\n")
                    is ThematicBreak -> append("────────\n\n")
                    is Paragraph -> { children(node,depth); append(if(node.parent is ListItem) "\n" else "\n\n") }
                    is BlockQuote -> { append("│ "); children(node,depth) }
                    is BulletList, is OrderedList -> {
                        var item = node.firstChild
                        var index = if(node is OrderedList) node.markerStartNumber else 1
                        while(item != null) {
                            append("  ".repeat(depth.coerceAtMost(16)))
                            append(if(node is OrderedList) "${index++}. " else "• ")
                            children(item,depth+1)
                            item = item.next
                        }
                        append("\n")
                    }
                    is TableBlock -> { withStyle(codeStyle) { children(node,depth) }; append("\n") }
                    is TableRow -> { append("| "); children(node,depth); append("\n") }
                    is TableCell -> {
                        if(node.isHeader) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { children(node,depth) }
                        else children(node,depth)
                        append(" | ")
                    }
                    else -> children(node,depth)
                }
            }
            render(document,0)
        }
    }
}
