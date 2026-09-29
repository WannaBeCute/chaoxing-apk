package com.cxrunner.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 极简 Markdown 渲染器（纯 Compose 实现，不依赖任何第三方库）。
 *
 * 支持的语法：
 *   `#`~`####` 标题、正文段落、`-` / `1.` 列表（两级缩进）、`>` 引用、
 *   ``` 代码块、`---` 分隔线、`![alt](assets路径)` 图片，
 *   行内 **加粗**、*斜体*、`行内代码`、[文字](链接)，以及裸 http(s) 链接。
 *
 * 说明：`<!-- -->` HTML 注释会被忽略，可用来在 md 文件里写「图片槽位」提示。
 */
@Composable
fun MarkdownView(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val onLink: (String) -> Unit = { url -> runCatching { uriHandler.openUri(url) } }

    val textColor = MaterialTheme.colorScheme.onBackground
    val dimColor = MaterialTheme.colorScheme.onSurfaceVariant
    val accentColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val ruleColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)

    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = accentColor, textDecoration = TextDecoration.Underline)
    )

    val blocks = remember(markdown) { parseMarkdown(markdown) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val size = when (block.level) {
                        1 -> 20.sp
                        2 -> 17.sp
                        3 -> 15.sp
                        else -> 14.sp
                    }
                    val annotated = renderInline(block.text, linkStyles, accentColor, codeBg, dimColor, onLink)
                    Text(
                        text = annotated,
                        fontSize = size,
                        lineHeight = (size.value + 6).sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (block.level <= 2) 6.dp else 2.dp),
                    )
                }

                is MdBlock.Paragraph -> Text(
                    text = renderInline(block.text, linkStyles, accentColor, codeBg, dimColor, onLink),
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                    color = textColor,
                )

                is MdBlock.Quote -> Row(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(20.dp)
                            .background(accentColor.copy(alpha = 0.55f), RoundedCornerShape(2.dp))
                    )
                    Box(modifier = Modifier.width(8.dp))
                    Text(
                        text = renderInline(block.text, linkStyles, accentColor, codeBg, dimColor, onLink),
                        fontSize = 12.sp,
                        lineHeight = 19.sp,
                        color = dimColor,
                        modifier = Modifier.weight(1f),
                    )
                }

                is MdBlock.ListItem -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = (block.depth * 16).dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = block.marker,
                        fontSize = 12.sp,
                        lineHeight = 19.sp,
                        fontFamily = FontFamily.Monospace,
                        color = accentColor,
                        modifier = Modifier.width(if (block.ordered) 22.dp else 16.dp),
                    )
                    Text(
                        text = renderInline(block.text, linkStyles, accentColor, codeBg, dimColor, onLink),
                        fontSize = 12.sp,
                        lineHeight = 19.sp,
                        color = textColor,
                        modifier = Modifier.weight(1f),
                    )
                }

                is MdBlock.CodeBlock -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(codeBg.copy(alpha = 0.5f))
                        .border(1.dp, ruleColor, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                ) {
                    Text(
                        text = block.text,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        color = textColor,
                    )
                }

                is MdBlock.Image -> MarkdownImage(alt = block.alt, src = block.src, dimColor = dimColor, ruleColor = ruleColor)

                MdBlock.Rule -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(ruleColor)
                )
            }
        }
    }
}

@Composable
private fun MarkdownImage(alt: String, src: String, dimColor: Color, ruleColor: Color) {
    val context = LocalContext.current
    val bitmap = remember(src) {
        runCatching {
            val path = src.trim().removePrefix("/")
            context.assets.open(path).use { stream ->
                BitmapFactory.decodeStream(stream)?.asImageBitmap()
            }
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = alt,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)),
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, ruleColor, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "【图片位】${alt.ifBlank { src }}",
                fontSize = 11.sp,
                color = dimColor,
            )
        }
    }
}

// --------------------------------------------------------------------------- //
// 解析
// --------------------------------------------------------------------------- //

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class ListItem(val depth: Int, val text: String, val marker: String, val ordered: Boolean) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class CodeBlock(val text: String) : MdBlock
    data class Image(val alt: String, val src: String) : MdBlock
    data object Rule : MdBlock
}

private val HTML_COMMENT_RE = Regex("<!--[\\s\\S]*?-->")
private val BARE_IMAGE_RE = Regex("""^!\[(.*?)]\((\S+?)\)$""")
private val BULLET_RE = Regex("""^[-*+]\s+(.+)$""")
private val ORDERED_RE = Regex("""^(\d+)[.)]\s+(.+)$""")
private val BARE_URL_RE = Regex("""(?<![\w"'(\[<])(https?://[^\s<>"')\]]+)""")

private fun parseMarkdown(source: String): List<MdBlock> {
    val text = HTML_COMMENT_RE.replace(source, "")
        .replace("\r\n", "\n")
        .replace('\r', '\n')

    val lines = text.split('\n')
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()
    var inCode = false
    val codeBuffer = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
            paragraph.setLength(0)
        }
    }

    fun flushCode() {
        blocks.add(MdBlock.CodeBlock(codeBuffer.toString()))
        codeBuffer.setLength(0)
    }

    for (raw in lines) {
        val line = raw.trim()

        if (inCode) {
            if (line.startsWith("```")) {
                inCode = false
                flushCode()
            } else {
                if (codeBuffer.isNotEmpty()) codeBuffer.append('\n')
                codeBuffer.append(raw)
            }
            continue
        }

        when {
            line.startsWith("```") -> {
                flushParagraph()
                inCode = true
            }

            line.isEmpty() -> flushParagraph()

            line == "---" || line == "***" || line == "___" -> {
                flushParagraph()
                blocks.add(MdBlock.Rule)
            }

            line.startsWith("#") -> {
                flushParagraph()
                val level = line.takeWhile { it == '#' }.length.coerceIn(1, 4)
                blocks.add(MdBlock.Heading(level, line.drop(level).trim()))
            }

            BARE_IMAGE_RE.matches(line) -> {
                flushParagraph()
                val m = BARE_IMAGE_RE.find(line)!!
                blocks.add(MdBlock.Image(alt = m.groupValues[1], src = m.groupValues[2]))
            }

            line.startsWith(">") -> {
                flushParagraph()
                blocks.add(MdBlock.Quote(line.drop(1).trim()))
            }

            else -> {
                val indent = raw.takeWhile { it == ' ' || it == '\t' }.length
                val depth = (indent / 2).coerceIn(0, 2)
                val bullet = BULLET_RE.find(line)
                val ordered = ORDERED_RE.find(line)
                when {
                    bullet != null -> {
                        flushParagraph()
                        blocks.add(MdBlock.ListItem(depth, bullet.groupValues[1].trim(), "•", ordered = false))
                    }

                    ordered != null -> {
                        flushParagraph()
                        blocks.add(
                            MdBlock.ListItem(
                                depth,
                                ordered.groupValues[2].trim(),
                                "${ordered.groupValues[1]}.",
                                ordered = true,
                            )
                        )
                    }

                    else -> {
                        if (paragraph.isNotEmpty()) paragraph.append(' ')
                        paragraph.append(line)
                    }
                }
            }
        }
    }
    if (inCode) flushCode()
    flushParagraph()
    return blocks
}

// --------------------------------------------------------------------------- //
// 行内样式
// --------------------------------------------------------------------------- //

private fun renderInline(
    text: String,
    linkStyles: TextLinkStyles,
    accent: Color,
    codeBg: Color,
    dim: Color,
    onLink: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    // 先按「链接 / 行内代码 / 加粗」逐个扫描，其余按普通文本 + 裸链接拆分
    var i = 0
    val plain = StringBuilder()

    fun flushPlain() {
        if (plain.isEmpty()) return
        appendWithBareLinks(plain.toString(), linkStyles, onLink)
        plain.setLength(0)
    }

    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i) {
                    flushPlain()
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    plain.append(text[i]); i++
                }
            }

            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    flushPlain()
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            color = dim,
                            background = codeBg.copy(alpha = 0.7f),
                        )
                    ) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    plain.append(text[i]); i++
                }
            }

            text[i] == '[' -> {
                val close = text.indexOf(']', i + 1)
                if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                    val end = text.indexOf(')', close + 2)
                    if (end > close) {
                        flushPlain()
                        val label = text.substring(i + 1, close)
                        val url = text.substring(close + 2, end)
                        withLink(
                            LinkAnnotation.Url(
                                url = url,
                                styles = linkStyles,
                                linkInteractionListener = { onLink(url) },
                            )
                        ) {
                            append(label)
                        }
                        i = end + 1
                    } else {
                        plain.append(text[i]); i++
                    }
                } else {
                    plain.append(text[i]); i++
                }
            }

            else -> {
                plain.append(text[i]); i++
            }
        }
    }
    flushPlain()
}

/** 把裸 http(s) 链接渲染为可点击样式 */
private fun AnnotatedString.Builder.appendWithBareLinks(
    segment: String,
    linkStyles: TextLinkStyles,
    onLink: (String) -> Unit,
) {
    if (segment.isEmpty()) return
    val matches = BARE_URL_RE.findAll(segment).toList()
    if (matches.isEmpty()) {
        append(segment)
        return
    }
    var cursor = 0
    matches.forEach { m ->
        append(segment.substring(cursor, m.range.first))
        val url = m.value
        withLink(
            LinkAnnotation.Url(
                url = url,
                styles = linkStyles,
                linkInteractionListener = { onLink(url) },
            )
        ) {
            append(url)
        }
        cursor = m.range.last + 1
    }
    if (cursor < segment.length) append(segment.substring(cursor))
}

/** 供外部复用的默认正文样式 */
val MarkdownBodyTextStyle = TextStyle(fontSize = 13.sp, lineHeight = 20.sp)
