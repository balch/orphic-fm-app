package org.balch.orpheus.djapp.ai.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** One block of a reply: a run of plain lines, a list item, or a blank line between paragraphs. */
@Immutable
internal sealed interface MdBlock {
    data class Text(val text: AnnotatedString) : MdBlock

    /** A bullet ("•") or numbered ("3.") item, [level] 0 at the margin. */
    data class Item(val marker: String, val level: Int, val text: AnnotatedString) : MdBlock

    data object Gap : MdBlock
}

private val BULLET = Regex("""^(\s*)[-*+•]\s+(.*)$""")
private val NUMBERED = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)$""")
private val HEADING = Regex("""^#{1,6}\s+(.*)$""")
private const val MAX_LEVEL = 3

private val BOLD = SpanStyle(fontWeight = FontWeight.Bold)
private val ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
private val CODE = SpanStyle(fontFamily = FontFamily.Monospace)

/** The small Markdown a model writes in chat: bold, italics, code, headings, bullet and numbered lists, line breaks. */
internal fun parseChatMarkdown(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val plain = mutableListOf<String>()
    fun flush() {
        if (plain.isEmpty()) return
        out += MdBlock.Text(inline(plain.joinToString("\n")))
        plain.clear()
    }
    for (line in src.replace("\r\n", "\n").trim('\n').lines()) {
        val bullet = BULLET.matchEntire(line)
        val numbered = NUMBERED.matchEntire(line)
        val heading = HEADING.matchEntire(line)
        when {
            line.isBlank() -> {
                flush()
                if (out.isNotEmpty() && out.last() != MdBlock.Gap) out += MdBlock.Gap
            }
            bullet != null -> {
                flush()
                out += MdBlock.Item("•", level(bullet.groupValues[1]), inline(bullet.groupValues[2]))
            }
            numbered != null -> {
                flush()
                out += MdBlock.Item("${numbered.groupValues[2]}.", level(numbered.groupValues[1]), inline(numbered.groupValues[3]))
            }
            heading != null -> {
                flush()
                out += MdBlock.Text(buildAnnotatedString { withStyle(BOLD) { append(inline(heading.groupValues[1])) } })
            }
            else -> plain += line
        }
    }
    flush()
    return out
}

private fun level(indent: String) = (indent.replace("\t", "  ").length / 2).coerceAtMost(MAX_LEVEL)

/** Emphasis opens only at a word's start and closes at its end, so `5*3*2` and `snake_case` stay literal. */
private fun inline(s: String): AnnotatedString = buildAnnotatedString { appendInline(s) }

private fun AnnotatedString.Builder.appendInline(s: String) {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '`') {
            val end = s.indexOf('`', i + 1)
            if (end > i + 1) {
                withStyle(CODE) { append(s.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }
        if (c == '*' || c == '_') {
            val n = if (s.getOrNull(i + 1) == c) 2 else 1
            val close = if (opens(s, i, n)) closeOf(s, i + n, c, n) else -1
            if (close >= 0) {
                withStyle(if (n == 2) BOLD else ITALIC) { appendInline(s.substring(i + n, close)) }
                i = close + n
                continue
            }
            // An unmatched run stays literal as a whole, so "2**8" is not half an opener.
            append(s.substring(i, i + n))
            i += n
            continue
        }
        append(c)
        i++
    }
}

private fun opens(s: String, at: Int, n: Int): Boolean {
    val before = s.getOrNull(at - 1)
    val after = s.getOrNull(at + n)
    return after != null && !after.isWhitespace() && (before == null || !before.isLetterOrDigit())
}

private fun closeOf(s: String, from: Int, c: Char, n: Int): Int {
    var j = from + 1
    while (j + n <= s.length) {
        val run = (0 until n).all { s[j + it] == c } && s.getOrNull(j + n) != c && s.getOrNull(j - 1) != c
        if (run && !s[j - 1].isWhitespace() && s.getOrNull(j + n)?.isLetterOrDigit() != true) return j
        j++
    }
    return -1
}

/** [text] as rendered Markdown in [style]; list items hang their wrapped lines under the text, not the marker. */
@Composable
internal fun ChatMarkdown(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseChatMarkdown(text) }
    val lineDp = with(LocalDensity.current) { style.fontSize.toDp() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(lineDp * 0.15f)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Text -> Text(block.text, style = style)
                is MdBlock.Item -> Row(Modifier.padding(start = lineDp * block.level)) {
                    Text(block.marker, style = style, modifier = Modifier.padding(end = lineDp * 0.4f))
                    Text(block.text, style = style, modifier = Modifier.weight(1f))
                }
                MdBlock.Gap -> Spacer(Modifier.height(lineDp * 0.35f))
            }
        }
    }
}
