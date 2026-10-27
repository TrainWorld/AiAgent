package com.example.aiagent.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * AI 回复消息气泡，支持基础 Markdown 渲染。
 *
 * 支持的 Markdown 语法：
 * - ```代码块```（等宽字体 + 背景色）
 * - `行内代码`
 * - **粗体**
 * - *斜体*
 * - # 标题（加大字号）
 */
@Composable
fun AiMarkdownText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val annotated = remember_markdown(text)
    Text(
        text = annotated,
        modifier = modifier,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

/**
 * 缓存 Markdown 解析结果，仅在 text 变化时重新解析。
 */
@Composable
private fun remember_markdown(text: String): AnnotatedString {
    return androidx.compose.runtime.remember(text) {
        buildMarkdownAnnotatedString(text)
    }
}

// ──────────────────── Markdown 解析 ────────────────────

private val CODE_BLOCK_REGEX = Regex("```\\w*\\n?([\\s\\S]*?)```")
private val INLINE_CODE_REGEX = Regex("`([^`\\n]+)`")
private val BOLD_REGEX = Regex("\\*\\*(.+?)\\*\\*")
private val ITALIC_REGEX = Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)")
private val HEADER_REGEX = Regex("^(#{1,6})\\s+(.+)$")

private fun buildMarkdownAnnotatedString(text: String): AnnotatedString {
    if (!text.contains('`') && !text.contains('*') && !text.contains('#')) {
        return AnnotatedString(text)
    }

    return buildAnnotatedString {
        // Pass 0: Append full text
        append(text)

        // Pass 1: Code blocks (monospace + background)
        val codeRanges = mutableListOf<IntRange>()
        CODE_BLOCK_REGEX.findAll(text).forEach { match ->
            val range = match.range
            codeRanges.add(range)
            addStyle(
                SpanStyle(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 13.sp,
                    background = Color(0x22888888),
                ),
                range.first,
                range.last + 1,
            )
        }

        // Pass 2: Inline code (monospace, skip if inside code block)
        INLINE_CODE_REGEX.findAll(text).forEach { match ->
            val range = match.range
            if (codeRanges.none { it.contains(range.first) }) {
                addStyle(
                    SpanStyle(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 13.sp,
                        background = Color(0x16888888),
                    ),
                    range.first,
                    range.last + 1,
                )
            }
        }

        // Pass 3: Bold (**text**)
        BOLD_REGEX.findAll(text).forEach { match ->
            val contentStart = match.range.first + 2
            val contentEnd = match.range.last - 1
            if (contentStart <= contentEnd) {
                addStyle(
                    SpanStyle(fontWeight = FontWeight.Bold),
                    contentStart,
                    contentEnd + 1,
                )
            }
        }

        // Pass 4: Italic (*text*) — avoid matching inside ** **
        ITALIC_REGEX.findAll(text).forEach { match ->
            val range = match.range
            // Skip if this overlaps with any bold range
            val overlapsBold = BOLD_REGEX.findAll(text).any { boldMatch ->
                range.first >= boldMatch.range.first && range.last <= boldMatch.range.last
            }
            if (!overlapsBold) {
                val contentStart = match.range.first + 1
                val contentEnd = match.range.last - 1
                if (contentStart <= contentEnd) {
                    addStyle(
                        SpanStyle(fontStyle = FontStyle.Italic),
                        contentStart,
                        contentEnd + 1,
                    )
                }
            }
        }

        // Pass 5: Headers (# text) — larger font, bold
        text.lines().forEach { line ->
            val headerMatch = HEADER_REGEX.matchEntire(line.trim())
            if (headerMatch != null) {
                val level = headerMatch.groupValues[1].length
                val lineStart = text.indexOf(line)
                if (lineStart >= 0) {
                    val fontSize = when (level) {
                        1 -> 22.sp; 2 -> 20.sp; 3 -> 18.sp
                        else -> 16.sp
                    }
                    addStyle(
                        SpanStyle(fontWeight = FontWeight.Bold, fontSize = fontSize),
                        lineStart,
                        lineStart + line.length,
                    )
                }
            }
        }
    }
}
