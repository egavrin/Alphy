package com.alpha.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alpha.assistant.R
import com.alpha.assistant.cloud.markdownParser
import com.alpha.assistant.cloud.sourceReference
import com.alpha.assistant.cloud.validSourceUrl
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.node.*

private data class MarkdownBody(val text: AnnotatedString, val tables: List<MarkdownTable>)
private data class MarkdownTable(val offset: Int, val rows: List<List<MarkdownCell>>)
private data class MarkdownCell(val text: AnnotatedString, val alignment: TableCell.Alignment?)

private fun Node.childNodes() = generateSequence(firstChild) { it.next }

/** HTML and image references remain text. */
@Composable internal fun MarkdownText(source: String, modifier: Modifier, announce: Boolean,
    sources: List<com.alpha.assistant.data.Source> = emptyList(), linkError: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val color = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val document = remember(source) { runCatching { markdownParser.parse(source) }.getOrNull() }
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val imagePrefix = stringResource(R.string.chat_markdown_image_prefix)
    val body = remember(document,source,sources,color,codeBackground,codeColor,uriHandler,linkError,imagePrefix) {
        if (document == null) MarkdownBody(AnnotatedString(source), emptyList()) else {
            val tables = mutableListOf<MarkdownTable>()
            lateinit var render: AnnotatedString.Builder.(Node, Int) -> Unit
            fun AnnotatedString.Builder.children(node: Node, depth: Int) {
                node.childNodes().forEach { child -> render(child, depth) }
            }
            fun link(url: String) = LinkAnnotation.Url(url,
                TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))) {
                runCatching { uriHandler.openUri(url) }.onFailure { linkError() }
            }
            fun AnnotatedString.Builder.citedText(node: org.commonmark.node.Text) {
                val literal = node.literal
                if (sources.isEmpty() || generateSequence(node.parent) { it.parent }.any { it is Link || it is Image }) {
                    append(literal)
                    return
                }
                var start = 0
                for (match in sourceReference.findAll(literal)) {
                    val url = sources.getOrNull(match.groupValues[1].toInt() - 1)?.url?.takeIf(::validSourceUrl) ?: continue
                    append(literal.substring(start, match.range.first))
                    pushLink(link(url))
                    append(match.value)
                    pop()
                    start = match.range.last + 1
                }
                append(literal.substring(start))
            }
            val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace,color = codeColor,background = codeBackground)
            render = { node,depth ->
                when(node) {
                    is org.commonmark.node.Text -> citedText(node)
                    is Code -> withStyle(codeStyle) { append(node.literal) }
                    is FencedCodeBlock -> { withStyle(codeStyle) { append(node.literal.trimEnd('\n')) }; append("\n\n") }
                    is IndentedCodeBlock -> { withStyle(codeStyle) { append(node.literal.trimEnd('\n')) }; append("\n\n") }
                    is Heading -> { withStyle(SpanStyle(fontWeight = FontWeight.Bold,fontSize = (25 - node.level).sp)) { children(node,depth) }; append("\n\n") }
                    is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { children(node,depth) }
                    is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { children(node,depth) }
                    is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { children(node,depth) }
                    is Link -> {
                        if(validSourceUrl(node.destination)) {
                            pushLink(link(node.destination))
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
                    is TableBlock -> {
                        val rows = node.childNodes().flatMap { it.childNodes() }.map { row ->
                            row.childNodes().map { cell ->
                                cell as TableCell
                                MarkdownCell(buildAnnotatedString { children(cell, depth) }, cell.alignment)
                            }.toList()
                        }.toList()
                        tables += MarkdownTable(length, rows)
                    }
                    else -> children(node,depth)
                }
            }
            MarkdownBody(buildAnnotatedString { render(document, 0) }, tables)
        }
    }
    val style = LocalTextStyle.current.copy(fontSize = 15.sp, lineHeight = 23.sp)
    val endOfText = body.text.text.trimEnd('\n').length
    if (body.tables.isEmpty()) {
        Text(body.text.subSequence(0, endOfText), modifier.then(if (announce) PoliteLiveRegion else Modifier), style = style)
    } else {
        val container = if (announce) modifier.semantics { liveRegion = LiveRegionMode.Polite } else modifier
        Column(container, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var start = 0
            for (table in body.tables) {
                val end = start + body.text.text.substring(start, table.offset).trimEnd('\n').length
                if (start < end) Text(body.text.subSequence(start, end), style = style)
                MarkdownTable(table, style)
                start = table.offset
            }
            if (start < endOfText) Text(body.text.subSequence(start, endOfText), style = style)
        }
    }
}

@Composable private fun MarkdownTable(table: MarkdownTable, style: TextStyle) {
    val columnCount = table.rows.first().size
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints {
        val columnWidth = (maxWidth / columnCount).coerceIn(128.dp, 240.dp)
        Column(Modifier.horizontalScroll(rememberScrollState()).semantics {
            collectionInfo = CollectionInfo(rowCount = table.rows.size, columnCount = columnCount)
        }) {
            table.rows.forEachIndexed { rowIndex, row ->
                val background = when {
                    rowIndex == 0 -> colors.surfaceVariant
                    rowIndex % 2 == 1 -> colors.surface
                    else -> Color.Transparent
                }
                Row(Modifier.background(background)) {
                    row.forEachIndexed { columnIndex, cell ->
                        Text(
                            cell.text,
                            Modifier.width(columnWidth).padding(horizontal = 10.dp, vertical = 6.dp).semantics {
                                collectionItemInfo = CollectionItemInfo(rowIndex, 1, columnIndex, 1)
                                if (rowIndex == 0) heading()
                            },
                            fontWeight = if (rowIndex == 0) FontWeight.Bold else null,
                            textAlign = when (cell.alignment) {
                                TableCell.Alignment.LEFT -> TextAlign.Left
                                TableCell.Alignment.CENTER -> TextAlign.Center
                                TableCell.Alignment.RIGHT -> TextAlign.Right
                                null -> TextAlign.Start
                            },
                            style = style,
                        )
                    }
                }
            }
        }
    }
}
