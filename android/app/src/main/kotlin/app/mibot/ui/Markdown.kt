package app.mibot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The part of Markdown that bots actually write: paragraphs, headings, lists, quotes, code, tables
 * as plain rows, and inside a line bold, italic, code, links and bare web addresses.
 */

private sealed interface Block {
    class Para(val text: String) : Block
    class Heading(val level: Int, val text: String) : Block
    class Item(val marker: String, val depth: Int, val text: String) : Block
    class Quote(val text: String) : Block
    class Code(val text: String) : Block
    data object Rule : Block
}

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val ITEM = Regex("^(\\s*)([-*+•]|\\d{1,3}[.)])\\s+(.*)$")
private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
private val TABLE_RULE = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

private fun blocks(source: String): List<Block> {
    val out = ArrayList<Block>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += Block.Para(para.toString().trim())
        para.clear()
    }
    val lines = source.replace("\r\n", "\n").split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) code.append(lines[i++]).append('\n')
                out += Block.Code(code.toString().trimEnd('\n'))
            }
            trimmed.isEmpty() -> flush()
            RULE.matches(line) -> { flush(); out += Block.Rule }
            TABLE_RULE.matches(line) && trimmed.contains('-') && trimmed.contains('|') -> { /* the line under a table's header: nothing to show */ }
            HEADING.matches(trimmed) -> {
                flush()
                val m = HEADING.find(trimmed)!!
                out += Block.Heading(m.groupValues[1].length, m.groupValues[2].trimEnd('#', ' '))
            }
            ITEM.matches(line) -> {
                flush()
                val m = ITEM.find(line)!!
                val marker = m.groupValues[2].let { if (it.first().isDigit()) it.dropLast(1) + "." else "•" }
                out += Block.Item(marker, m.groupValues[1].length / 2, m.groupValues[3])
            }
            trimmed.startsWith(">") -> { flush(); out += Block.Quote(trimmed.trimStart('>', ' ')) }
            trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length > 1 -> {
                flush()
                out += Block.Para(trimmed.trim('|').split('|').joinToString("  ·  ") { it.trim() })
            }
            else -> {
                // A line that continues a list item belongs to it.
                val last = out.lastOrNull()
                if (para.isEmpty() && last is Block.Item && line.startsWith("  ")) out[out.lastIndex] = Block.Item(last.marker, last.depth, last.text + " " + trimmed)
                else para.append(if (para.isEmpty()) "" else "\n").append(trimmed)
            }
        }
        i++
    }
    flush()
    return out
}

private val INLINE = Regex(
    "(`[^`\\n]+`)" + // code
        "|(\\*\\*[^*\\n]+?\\*\\*|__[^_\\n]+?__)" + // bold
        "|(\\*[^*\\s][^*\\n]*?\\*|(?<![\\w])_[^_\\s][^_\\n]*?_(?![\\w]))" + // italic
        "|(!?\\[[^\\]\\n]*\\]\\([^)\\s]+\\))" + // link or image
        "|(https?://[^\\s<>()\\[\\]\"']+[^\\s<>()\\[\\]\"'.,;:!?])", // bare address
)

private fun inline(text: String, link: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
    val linkStyles = TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))
    var at = 0
    for (m in INLINE.findAll(text)) {
        append(text.substring(at, m.range.first))
        at = m.range.last + 1
        val s = m.value
        when {
            m.groups[1] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 14.sp)) { append(s.substring(1, s.length - 1)) }
            m.groups[2] != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inline(s.substring(2, s.length - 2), link, codeBg)) }
            m.groups[3] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline(s.substring(1, s.length - 1), link, codeBg)) }
            m.groups[4] != null -> {
                val label = s.substringAfter('[').substringBeforeLast("](")
                val url = s.substringAfterLast("](").dropLast(1)
                withLink(LinkAnnotation.Url(url, linkStyles)) { append(label.ifBlank { url }) }
            }
            else -> withLink(LinkAnnotation.Url(s, linkStyles)) { append(s) }
        }
    }
    append(text.substring(at))
}

/** Text a bot wrote, laid out. `color` is the ink; links take `linkColor`. */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, color: Color = Mi.colors.text, linkColor: Color = color, style: TextStyle = Type.body) {
    val parsed = remember(text) { blocks(text) }
    val codeBg = Mi.colors.text.copy(alpha = 0.09f)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in parsed) {
            when (b) {
                is Block.Para -> Text(inline(b.text, linkColor, codeBg), style = style, color = color)
                is Block.Heading -> Text(
                    inline(b.text, linkColor, codeBg), color = color,
                    style = when (b.level) { 1 -> Type.title; 2 -> Type.heading; else -> Type.bodyStrong },
                    modifier = Modifier.padding(top = 4.dp),
                )
                is Block.Item -> Row(Modifier.padding(start = (b.depth * 16).dp)) {
                    Text(b.marker, style = style, color = color.copy(alpha = 0.7f), modifier = Modifier.width(if (b.marker == "•") 18.dp else 26.dp))
                    Text(inline(b.text, linkColor, codeBg), style = style, color = color)
                }
                is Block.Quote -> Row {
                    Box(Modifier.padding(end = 10.dp).width(3.dp).background(color.copy(alpha = 0.3f), RoundedCornerShape(2.dp))) { Text(" ", style = style) }
                    Text(inline(b.text, linkColor, codeBg), style = style, color = color.copy(alpha = 0.8f))
                }
                is Block.Code -> Text(
                    b.text, style = Type.small.copy(fontFamily = FontFamily.Monospace), color = color,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(codeBg).horizontalScroll(rememberScrollState()).padding(12.dp),
                )
                Block.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(color.copy(alpha = 0.15f)).padding(top = 1.dp))
            }
        }
    }
}
