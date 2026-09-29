package com.cxrunner.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * 日志 ANSI 转义序列渲染。
 *
 * 上游 `api/logger.py` 用 `logger.add(..., colorize=True)`，loguru 会给每条日志
 * 加上 `\x1b[34m\x1b[1mDEBUG\x1b[0m` 这样的 SGR 序列。之前日志窗口是按纯文本
 * 直接画的，于是：
 *   * 颜色码原样显示成 `[34m[1m` 这种噪声；
 *   * `ESC`(U+001B) 是控制字符、没有字形，被字体画成「长方形方格」，
 *     用户看到的"空格变方块"其实就是它（tqdm 的 `\r` 同理）。
 * 这里把 SGR 解析成 SpanStyle，并把其余控制字符清掉。
 */

/** 深色 / 浅色两套 ANSI 调色板：原版 16 色在另一种底色的主题上会看不清 */
class AnsiPalette(val basic: List<Color>, val bright: List<Color>)

// 深色底：参照 VS Code Dark+ / GitHub Dark
private val PaletteDark = AnsiPalette(
    basic = listOf(
        Color(0xFF8B949E), // 30 黑 -> 深灰，纯黑在深色底上看不见
        Color(0xFFF85149), // 31 红
        Color(0xFF3FB950), // 32 绿
        Color(0xFFD29922), // 33 黄
        Color(0xFF58A6FF), // 34 蓝
        Color(0xFFBC8CFF), // 35 品红
        Color(0xFF39C5CF), // 36 青
        Color(0xFFC9D1D9), // 37 白
    ),
    bright = listOf(
        Color(0xFF6E7681), Color(0xFFFF7B72), Color(0xFF56D364), Color(0xFFE3B341),
        Color(0xFF79C0FF), Color(0xFFD2A8FF), Color(0xFF56D4DD), Color(0xFFFFFFFF),
    ),
)

// 浅色底：白色/黄色压深，否则在白底上几乎看不见
private val PaletteLight = AnsiPalette(
    basic = listOf(
        Color(0xFF24292F), Color(0xFFCF222E), Color(0xFF1A7F37), Color(0xFF9A6700),
        Color(0xFF0969DA), Color(0xFF8250DF), Color(0xFF1B7C83), Color(0xFF6E7781),
    ),
    bright = listOf(
        Color(0xFF57606A), Color(0xFFA40E26), Color(0xFF116329), Color(0xFF7D4E00),
        Color(0xFF0550AE), Color(0xFF6639BA), Color(0xFF0F5C63), Color(0xFF32383F),
    ),
)

/** 根据当前主题底色自动选择调色板 */
@Composable
fun rememberAnsiPalette(): AnsiPalette {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return remember(dark) { if (dark) PaletteDark else PaletteLight }
}

/**
 * 把带 ANSI 颜色码的日志文本转成可直接渲染的 [AnnotatedString]。
 *
 * @param fallbackColor 未指定颜色时的文字颜色（跟随主题）
 * @param codeBackground 行内代码/背景色叠加用的底色
 */
fun ansiToAnnotatedString(
    source: String,
    palette: AnsiPalette,
    fallbackColor: Color,
    codeBackground: Color = Color.Unspecified,
): AnnotatedString {
    // ---- 1) 清理控制字符 ----
    //  \r  -> 按终端语义处理：把"当前这一行"擦掉，让后续内容从行首重画。
    //         tqdm 的进度条就是靠 \r 原地刷新的，直接删掉会把所有帧连成一长串；
    //  其余 C0 / DEL -> 丢弃（ESC 先留着给第 2 步解析）
    val clean = StringBuilder(source.length)
    for (ch in source) {
        when {
            ch == '\r' -> {
                val lastNewline = clean.lastIndexOf("\n")
                clean.setLength(if (lastNewline >= 0) lastNewline + 1 else 0)
            }
            ch == '\n' || ch == '\t' -> clean.append(ch)
            ch == '\u001B' -> clean.append(ch)
            ch.code < 0x20 || ch.code == 0x7F -> Unit
            else -> clean.append(ch)
        }
    }
    val text = clean.toString()

    // ---- 2) 解析 SGR ----
    return buildAnnotatedString {
        var bold = false
        var dim = false
        var italic = false
        var underline = false
        var fg: Color? = null
        var bg: Color? = null

        fun style(): SpanStyle {
            val base = fg ?: fallbackColor
            return SpanStyle(
                color = if (dim) base.copy(alpha = 0.72f) else base,
                background = bg ?: codeBackground,
                fontWeight = if (bold) FontWeight.Bold else null,
                fontStyle = if (italic) FontStyle.Italic else null,
                textDecoration = if (underline) TextDecoration.Underline else null,
            )
        }

        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isEmpty()) return
            withStyle(style()) { append(buffer.toString()) }
            buffer.setLength(0)
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\u001B' && i + 1 < text.length && text[i + 1] == '[') {
                var j = i + 2
                while (j < text.length && text[j] !in '@'..'~') j++
                if (j >= text.length) break // 截断的转义序列，丢弃
                val finalChar = text[j]
                val params = text.substring(i + 2, j)
                if (finalChar == 'm') {
                    flush()
                    val codes = if (params.isEmpty()) {
                        listOf(0)
                    } else {
                        params.split(';').map { it.toIntOrNull() ?: 0 }
                    }
                    for (p in codes) {
                        when {
                            p == 0 -> {
                                bold = false; dim = false; italic = false
                                underline = false; fg = null; bg = null
                            }
                            p == 1 -> bold = true
                            p == 2 -> dim = true
                            p == 3 -> italic = true
                            p == 4 -> underline = true
                            p == 22 -> { bold = false; dim = false }
                            p == 23 -> italic = false
                            p == 24 -> underline = false
                            p == 39 -> fg = null
                            p == 49 -> bg = null
                            p in 30..37 -> fg = palette.basic[p - 30]
                            p in 90..97 -> fg = palette.bright[p - 90]
                            // 背景色只取淡淡一层，避免整行被色块糊住
                            p in 40..47 -> bg = palette.basic[p - 40].copy(alpha = 0.20f)
                            p in 100..107 -> bg = palette.bright[p - 100].copy(alpha = 0.20f)
                            // 38/48 的 256 色与 truecolor 这里不展开，直接忽略
                        }
                    }
                }
                // 其它 CSI 序列（\x1b[K / \x1b[H 等）一律丢弃
                i = j + 1
                continue
            }
            buffer.append(c)
            i++
        }
        flush()
    }
}
