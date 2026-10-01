package com.cursor.mobile.android.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cursor.mobile.android.data.AgentStatus
import com.cursor.mobile.android.data.ChatMessage
import com.cursor.mobile.android.data.DiffFile
import com.cursor.mobile.android.data.Sender
import com.cursor.mobile.android.ui.theme.CodeFont
import com.cursor.mobile.android.ui.theme.CursorPalette
import com.cursor.mobile.android.ui.theme.accentGradient

fun statusColor(status: AgentStatus): Color = when (status) {
    AgentStatus.WORKING -> CursorPalette.NeonCyan
    AgentStatus.NEEDS_INPUT -> CursorPalette.Warning
    AgentStatus.READY_FOR_REVIEW -> CursorPalette.NeonViolet
    AgentStatus.DONE -> CursorPalette.Success
    AgentStatus.FAILED -> CursorPalette.Danger
}

fun statusLabel(status: AgentStatus): String = when (status) {
    AgentStatus.WORKING -> "运行中"
    AgentStatus.NEEDS_INPUT -> "等你确认"
    AgentStatus.READY_FOR_REVIEW -> "待 REVIEW"
    AgentStatus.DONE -> "已完成"
    AgentStatus.FAILED -> "失败"
}

@Composable
fun StatusChip(status: AgentStatus) {
    val color = statusColor(status)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.13f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            statusLabel(status),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.6.sp),
            color = color
        )
    }
}

@Composable
fun Kicker(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            modifier = Modifier
                .size(width = 18.dp, height = 3.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(accentGradient())
        )
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private data class TextPart(val code: Boolean, val text: String)

private fun normalizeBreaks(raw: String): String =
    raw.replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace("\\r\\n", "\n")
        .replace("\\n", "\n")
        .replace("\\t", "\t")

private val dropWholeBlock = listOf(
    "timestamp",
    "user_info",
    "system_reminder",
    "communication",
    "rules",
    "agent_skills",
    "agent_skill",
    "open_and_recently_viewed_files",
    "attached_files",
    "image_files"
)

private val cleanCache = object : LinkedHashMap<String, String>(128, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 160
}

internal fun cleanMessageForDisplay(raw: String): String {
    synchronized(cleanCache) { cleanCache[raw]?.let { return it } }
    var text = normalizeBreaks(raw)
    dropWholeBlock.forEach { tag ->
        text = Regex("(?is)<$tag\\b[^>]*>.*?</$tag>").replace(text, "")
        text = Regex("(?is)<$tag\\b[^>]*>").replace(text, "")
        text = Regex("(?is)</$tag>").replace(text, "")
    }
    text = Regex("(?is)</?[a-zA-Z][a-zA-Z0-9_:-]*(?:\\s[^>]*)?>").replace(text, "")
    text = Regex("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)").replace(text) { match ->
        val label = match.groupValues[1].trim()
        val url = match.groupValues[2].trim()
        if (label.isBlank() || label == url) url else "$label $url"
    }
    text = Regex("`(https?://[^`]+)`").replace(text) { it.groupValues[1] }
    text = text.lineSequence()
        .map { it.trim() }
        .filterNot { line -> line.matches(Regex("^/task(?:-[A-Za-z0-9_]+)?$", RegexOption.IGNORE_CASE)) }
        .filterNot { line -> line.equals("time.", ignoreCase = true) || line.equals("time", ignoreCase = true) }
        .joinToString("\n")
    text = Regex("[ \\t]{2,}").replace(text, " ")
    text = Regex("(?<=[\\u4e00-\\u9fff\\u3000-\\u303f\\uff00-\\uffef]) +(?=[\\u4e00-\\u9fff\\u3000-\\u303f\\uff00-\\uffef])").replace(text, "")
    text = Regex(" +(?=[，。！？、；：])").replace(text, "")
    text = Regex("\n{3,}").replace(text, "\n\n")
    val cleaned = text.trim()
    synchronized(cleanCache) { cleanCache[raw] = cleaned }
    return cleaned
}

private fun splitMessage(raw: String): List<TextPart> {
    val text = cleanMessageForDisplay(raw)
    if (text.isEmpty()) return emptyList()
    val parts = mutableListOf<TextPart>()
    val fence = Regex("```[a-zA-Z0-9_-]*\\n?")
    var index = 0
    var code = false
    while (index < text.length) {
        val match = fence.find(text, index) ?: break
        val chunk = text.substring(index, match.range.first)
        if (chunk.isNotBlank()) parts += TextPart(code, chunk.trim('\n'))
        code = !code
        index = match.range.last + 1
    }
    val tail = text.substring(index)
    if (tail.isNotBlank()) parts += TextPart(code, tail.trim('\n'))
    if (parts.isEmpty()) parts += TextPart(looksLikeCode(text), text)
    if (parts.size == 1 && !parts[0].code && looksLikeCode(parts[0].text)) {
        return listOf(TextPart(true, parts[0].text))
    }
    return parts.map { part ->
        val trimmed = part.text.trim()
        if (part.code && trimmed.matches(Regex("https?://\\S+"))) TextPart(false, trimmed) else part
    }.filter { it.text.isNotBlank() }
}

private fun looksLikeCode(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.startsWith("diff ") || trimmed.startsWith("@@") || trimmed.contains("\n@@")
}

@Composable
fun MessageBody(
    text: String,
    color: Color,
    style: TextStyle,
    partSpacing: androidx.compose.ui.unit.Dp = 6.dp,
    textAlign: androidx.compose.ui.text.style.TextAlign = androidx.compose.ui.text.style.TextAlign.Start,
    justify: Boolean = false,
    contentWidthPx: Int = 0
) {
    val parts = remember(text) { splitMessage(text) }
    Column(verticalArrangement = Arrangement.spacedBy(partSpacing)) {
        parts.forEach { part ->
            when {
                part.code -> CodeScroll(part.text, color)
                justify -> MarkdownBlock(part.text, color, style, textAlign, contentWidthPx)
                else -> PlainScroll(part.text, color, style, textAlign)
            }
        }
    }
}

private sealed class MdLine {
    data class Heading(val level: Int, val text: String) : MdLine()
    data class Bullet(val text: String) : MdLine()
    data class Paragraph(val text: String) : MdLine()
}

private fun markdownLines(raw: String): List<MdLine> {
    val lines = normalizeBreaks(raw).split('\n')
    val out = mutableListOf<MdLine>()
    val paragraph = StringBuilder()
    fun flush() {
        val text = paragraph.toString().trim()
        if (text.isNotBlank()) out += MdLine.Paragraph(text)
        paragraph.clear()
    }
    lines.forEach { line ->
        val trimmed = line.trim()
        val heading = Regex("^(#{1,3})\\s+(.+)$").find(trimmed)
        val bullet = Regex("^([-*+]|\\d+[.)])\\s+(.+)$").find(trimmed)
        when {
            trimmed.isEmpty() -> flush()
            heading != null -> {
                flush()
                out += MdLine.Heading(heading.groupValues[1].length, heading.groupValues[2])
            }
            bullet != null -> {
                flush()
                out += MdLine.Bullet(bullet.groupValues[2])
            }
            else -> {
                if (paragraph.isNotEmpty()) {
                    val prev = paragraph.last()
                    val next = trimmed.first()
                    if (!isCjk(prev) && !isCjk(next)) paragraph.append(' ')
                }
                paragraph.append(trimmed)
            }
        }
    }
    flush()
    return out
}

@Composable
private fun MarkdownBlock(
    text: String,
    color: Color,
    style: TextStyle,
    textAlign: androidx.compose.ui.text.style.TextAlign,
    contentWidthPx: Int
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        markdownLines(text).forEach { line ->
            when (line) {
                is MdLine.Heading -> JustifiedText(
                    line.text,
                    color,
                    style.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = when (line.level) {
                            1 -> 16.sp
                            2 -> 15.sp
                            else -> 14.sp
                        }
                    ),
                    contentWidthPx
                )
                is MdLine.Bullet -> JustifiedText("• ${line.text}", color, style, contentWidthPx)
                is MdLine.Paragraph -> JustifiedText(line.text, color, style, contentWidthPx)
            }
        }
    }
}

private data class Piece(
    val text: String,
    val glue: Boolean,
    val bold: Boolean = false,
    val mono: Boolean = false
)

private fun isCjk(c: Char): Boolean {
    val code = c.code
    return code in 0x2E80..0x9FFF || code in 0xF900..0xFAFF || code in 0xFF00..0xFFEF || code in 0x3000..0x303F
}

private fun tokenize(text: String): List<Piece> {
    val out = mutableListOf<Piece>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == ' ' || c == '\t') {
            out += Piece(" ", glue = false)
            i++
            while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
            continue
        }
        if (text.startsWith("https://", i) || text.startsWith("http://", i)) {
            val end = (i until text.length).firstOrNull { text[it].isWhitespace() } ?: text.length
            val url = text.substring(i, end)
            val scheme = url.indexOf("://").let { if (it < 0) 0 else it + 3 }
            val path = url.indexOf('/', scheme)
            if (path < 0) {
                out += Piece(url, glue = true)
            } else {
                out += Piece(url.substring(0, path), glue = true)
                var cursor = path
                while (cursor < url.length) {
                    val next = url.indexOf('/', cursor + 1).let { if (it < 0) url.length else it }
                    out += Piece(url.substring(cursor, next), glue = true)
                    cursor = next
                }
            }
            i = end
            continue
        }
        if (c == '/') {
            val start = i
            i++
            while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '-' || text[i] == '.')) i++
            out += Piece(text.substring(start, i), glue = true)
            continue
        }
        if (isCjk(c)) {
            out += Piece(c.toString(), glue = false)
            i++
            continue
        }
        if (c.isLetterOrDigit() || c == '_' || c == '\'') {
            val start = i
            i++
            while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '\'' || text[i] == '-')) i++
            out += Piece(text.substring(start, i), glue = false)
            continue
        }
        if (out.isNotEmpty() && out.last().text != " ") {
            val prev = out.removeAt(out.lastIndex)
            out += prev.copy(text = prev.text + c)
        } else {
            out += Piece(c.toString(), glue = false)
        }
        i++
    }
    return out
}

private fun styledPieces(text: String): List<Piece> {
    val pattern = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
    val spans = mutableListOf<Triple<String, Boolean, Boolean>>()
    var index = 0
    pattern.findAll(text).forEach { match ->
        if (match.range.first > index) spans += Triple(text.substring(index, match.range.first), false, false)
        val bold = match.groupValues[1]
        val code = match.groupValues[2]
        if (bold.isNotEmpty()) spans += Triple(bold, true, false) else spans += Triple(code, false, true)
        index = match.range.last + 1
    }
    if (index < text.length) spans += Triple(text.substring(index), false, false)
    return spans.flatMap { (value, bold, mono) ->
        tokenize(value).map { it.copy(bold = bold || it.bold, mono = mono || it.mono) }
    }
}

private data class LaidLine(val pieces: List<Piece>, val contentWidth: Int)

private val lineCache = object : LinkedHashMap<String, List<LaidLine>>(128, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LaidLine>>?) = size > 120
}

private fun breakLines(text: String, maxWidth: Int, styleKey: Int, widthOf: (Piece) -> Int): List<LaidLine> {
    val key = "$styleKey|$maxWidth|$text"
    synchronized(lineCache) { lineCache[key]?.let { return it } }
    val pieces = styledPieces(text).filter { it.text.isNotEmpty() }
    if (pieces.isEmpty() || maxWidth <= 0) return emptyList()
    val lines = mutableListOf<LaidLine>()
    var current = mutableListOf<Piece>()
    var width = 0
    fun flush() {
        if (current.isNotEmpty() && current.last().text == " ") current.removeAt(current.lastIndex)
        if (current.isNotEmpty()) lines += LaidLine(current.toList(), width)
        current = mutableListOf()
        width = 0
    }
    for (piece in pieces) {
        if (piece.text == " " && current.isEmpty()) continue
        var part = piece
        var partWidth = widthOf(part)
        if (partWidth > maxWidth && part.text.length > 1 && part.text != " ") {
            if (current.isNotEmpty()) flush()
            var start = 0
            val raw = part.text
            while (start < raw.length) {
                var end = start + 1
                while (end < raw.length && widthOf(part.copy(text = raw.substring(start, end + 1))) <= maxWidth) end++
                val slice = part.copy(text = raw.substring(start, end))
                lines += LaidLine(listOf(slice), widthOf(slice))
                start = end
            }
            continue
        }
        if (current.isNotEmpty() && width + partWidth > maxWidth) {
            flush()
            if (part.text == " ") continue
            partWidth = widthOf(part)
        }
        current += part
        width += partWidth
    }
    flush()
    synchronized(lineCache) { lineCache[key] = lines }
    return lines
}

@Composable
private fun JustifiedText(text: String, color: Color, style: TextStyle, contentWidthPx: Int) {
    val measurer = rememberTextMeasurer()
    val drawStyle = style.copy(
        color = color,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )
    val fallback = if (contentWidthPx > 0) contentWidthPx else 0
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxWidth = if (fallback > 0) fallback else constraints.maxWidth
        val lines = remember(text, maxWidth, drawStyle) {
            val styleKey = drawStyle.fontSize.hashCode() xor (drawStyle.fontWeight?.weight ?: 400)
            breakLines(text, maxWidth, styleKey) { piece ->
                val pieceStyle = drawStyle.copy(
                    fontWeight = if (piece.bold) FontWeight.Bold else drawStyle.fontWeight,
                    fontFamily = if (piece.mono) CodeFont else drawStyle.fontFamily
                )
                measurer.measure(
                    piece.text,
                    style = pieceStyle,
                    softWrap = false,
                    maxLines = 1,
                    overflow = TextOverflow.Visible
                ).size.width
            }
        }
        Column {
            lines.forEachIndexed { index, line ->
                PieceLine(line, drawStyle, color, maxWidth, justify = index != lines.lastIndex)
            }
        }
    }
}

@Composable
private fun PieceLine(
    line: LaidLine,
    style: TextStyle,
    color: Color,
    maxWidth: Int,
    justify: Boolean
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gaps = if (justify) justifyGaps(line.pieces) else 0
    val extra = if (gaps > 0) (maxWidth - line.contentWidth).coerceAtLeast(0).toFloat() / gaps else 0f
    val extraSp = with(density) { extra.toSp() }
    val annotated = androidx.compose.ui.text.AnnotatedString.Builder().apply {
        line.pieces.forEachIndexed { index, piece ->
            val expand = justify && index != line.pieces.lastIndex && expands(line.pieces, index)
            pushStyle(
                androidx.compose.ui.text.SpanStyle(
                    color = color,
                    fontWeight = if (piece.bold) FontWeight.Bold else style.fontWeight,
                    fontFamily = if (piece.mono) CodeFont else style.fontFamily,
                    letterSpacing = if (expand && piece.text.length == 1) extraSp else 0.sp
                )
            )
            append(piece.text)
            pop()
        }
    }.toAnnotatedString()
    Text(
        annotated,
        style = style,
        softWrap = false,
        overflow = TextOverflow.Visible,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun justifyGaps(pieces: List<Piece>): Int {
    var count = 0
    for (index in 0 until pieces.lastIndex) if (expands(pieces, index)) count++
    return count
}

private fun expands(pieces: List<Piece>, index: Int): Boolean {
    val piece = pieces[index]
    val next = pieces.getOrNull(index + 1) ?: return false
    if (piece.glue && next.glue) return false
    if (piece.text.length > 1 && piece.text != " ") return false
    return true
}

private fun inlineMarkdown(text: String, color: Color): androidx.compose.ui.text.AnnotatedString {
    val builder = androidx.compose.ui.text.AnnotatedString.Builder()
    val pattern = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
    var index = 0
    pattern.findAll(text).forEach { match ->
        if (match.range.first > index) builder.append(text.substring(index, match.range.first))
        val bold = match.groupValues[1]
        val code = match.groupValues[2]
        if (bold.isNotEmpty()) {
            builder.pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = color))
            builder.append(bold)
            builder.pop()
        } else {
            builder.pushStyle(
                androidx.compose.ui.text.SpanStyle(
                    fontFamily = CodeFont,
                    color = color
                )
            )
            builder.append(code)
            builder.pop()
        }
        index = match.range.last + 1
    }
    if (index < text.length) builder.append(text.substring(index))
    return builder.toAnnotatedString()
}

@Composable
private fun PlainScroll(
    text: String,
    color: Color,
    style: TextStyle,
    textAlign: androidx.compose.ui.text.style.TextAlign
) {
    val base = style.fontSize.value
    val line = if (!style.lineHeight.value.isNaN()) style.lineHeight
    else if (base.isNaN()) 22.sp
    else (base + 8f).sp
    Text(
        text,
        color = color,
        style = style.copy(lineHeight = line),
        textAlign = textAlign,
        softWrap = true,
        overflow = TextOverflow.Visible,
        maxLines = Int.MAX_VALUE,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun CodeScroll(text: String, color: Color) {
    val long = text.length > 700 || text.count { it == '\n' } >= 12
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    Text(
        text,
        color = color,
        fontFamily = CodeFont,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        softWrap = false,
        overflow = TextOverflow.Visible,
        maxLines = Int.MAX_VALUE,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
            .then(if (long) Modifier.height(220.dp).verticalScroll(vertical) else Modifier)
            .horizontalScroll(horizontal)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

val ChatPagePadding = 8.dp
val ChatTextPadding = 28.dp
private val chatBody = TextStyle(fontSize = 13.sp, lineHeight = 17.sp)

@Composable
fun ChatBubble(msg: ChatMessage, deferFrames: Int = 1, contentWidthPx: Int = 0) {
    val cleaned = remember(msg.text) { cleanMessageForDisplay(msg.text) }
    var ready by remember(msg.id) { mutableStateOf(false) }
    var selecting by remember(msg.id) { mutableStateOf(false) }
    LaunchedEffect(msg.id, deferFrames) {
        repeat(deferFrames.coerceAtLeast(1)) { kotlinx.coroutines.delay(16) }
        ready = true
    }
    val align = androidx.compose.ui.text.style.TextAlign.Start
    if (cleaned.isBlank() && msg.files.isEmpty() && msg.attachment == null && !msg.isStreaming) return
    val background = when (msg.sender) {
        Sender.USER -> MaterialTheme.colorScheme.primaryContainer
        Sender.SYSTEM -> MaterialTheme.colorScheme.surfaceContainer
        Sender.AGENT -> MaterialTheme.colorScheme.surface
    }
    val border = when (msg.sender) {
        Sender.USER -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.outline
    }
    val color = when (msg.sender) {
        Sender.SYSTEM -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .then(
                if (selecting) Modifier else Modifier.pointerInput(msg.id) {
                    detectTapGestures(onLongPress = { selecting = true })
                }
            )
            .padding(horizontal = ChatTextPadding, vertical = 6.dp)
    ) {
        val body = @Composable {
            if (cleaned.isNotBlank()) {
                MessageBody(
                    cleaned,
                    color,
                    chatBody,
                    partSpacing = 2.dp,
                    textAlign = align,
                    justify = ready,
                    contentWidthPx = contentWidthPx
                )
            }
        msg.files.forEach { file ->
            Text(
                "${file.name} · ${file.mime}",
                color = color,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (!file.error.isNullOrBlank()) {
                Text(
                    file.error,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
        if (msg.isStreaming) {
            Box(Modifier.padding(top = 2.dp)) { TypingDots() }
        }
        if (msg.attachment != null) MiniCodeCard(msg.attachment)
        }
        if (selecting) SelectionContainer { body() } else body()
    }
}

@Composable
fun MiniCodeCard(text: String) {
    Row(
        modifier = Modifier
            .wrapContentWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.background)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 24.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(accentGradient())
        )
        MessageBody(text, MaterialTheme.colorScheme.onSurface, TextStyle(fontFamily = CodeFont, fontSize = 12.sp, lineHeight = 18.sp))
    }
}

@Composable
fun CodeBlock(path: String, code: String, additions: Int, deletions: Int, badge: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.background)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(path, fontFamily = CodeFont, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                color = CursorPalette.NeonViolet,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(CursorPalette.NeonViolet.copy(alpha = 0.14f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
        Text(
            code,
            fontFamily = CodeFont,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )
        Text(
            "+$additions  −$deletions",
            fontFamily = CodeFont,
            fontSize = 11.sp,
            color = CursorPalette.Success,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun DiffRow(file: DiffFile) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        CodeBlock(file.path, file.preview, file.additions, file.deletions, file.status)
    }
}

@Composable
fun TypingDots() {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(CursorPalette.NeonCyan.copy(alpha = 0.35f + 0.25f * (i + 1)))
            )
        }
    }
}

@Composable
fun EmptyState(title: String, hint: String, compact: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (compact) 16.dp else 20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(if (compact) 16.dp else 20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(if (compact) 12.dp else 28.dp),
        horizontalAlignment = if (compact) Alignment.Start else Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)
    ) {
        if (!compact) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(accentGradient()),
                contentAlignment = Alignment.Center
            ) {
                Text("◈", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(title, style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
