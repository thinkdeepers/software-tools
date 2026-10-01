package com.cursor.mobile.android.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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

internal fun cleanMessageForDisplay(raw: String): String {
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
    text = Regex("\n{3,}").replace(text, "\n\n")
    return text.trim()
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
    textAlign: androidx.compose.ui.text.style.TextAlign = androidx.compose.ui.text.style.TextAlign.Start
) {
    val parts = remember(text) { splitMessage(text) }
    Column(verticalArrangement = Arrangement.spacedBy(partSpacing)) {
        parts.forEach { part ->
            if (part.code) CodeScroll(part.text, color) else MarkdownBlock(part.text, color, style, textAlign)
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
                if (paragraph.isNotEmpty()) paragraph.append(' ')
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
    textAlign: androidx.compose.ui.text.style.TextAlign
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        markdownLines(text).forEach { line ->
            when (line) {
                is MdLine.Heading -> Text(
                    inlineMarkdown(line.text, color),
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = when (line.level) {
                        1 -> 16.sp
                        2 -> 15.sp
                        else -> 14.sp
                    },
                    lineHeight = style.lineHeight,
                    modifier = Modifier.fillMaxWidth()
                )
                is MdLine.Bullet -> Text(
                    inlineMarkdown("• ${line.text}", color),
                    color = color,
                    style = style,
                    modifier = Modifier.fillMaxWidth()
                )
                is MdLine.Paragraph -> Text(
                    inlineMarkdown(line.text, color),
                    color = color,
                    style = style,
                    textAlign = textAlign,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(msg: ChatMessage) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cleaned = remember(msg.text) { cleanMessageForDisplay(msg.text) }
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
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("消息", cleaned))
                    android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                }
            )
            .padding(horizontal = ChatTextPadding, vertical = 6.dp)
            .animateContentSize()
    ) {
        if (cleaned.isNotBlank()) {
            MessageBody(cleaned, color, chatBody, partSpacing = 2.dp, textAlign = align)
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
            Box(Modifier.padding(top = 2.dp).align(Alignment.Start)) { TypingDots() }
        }
        if (msg.attachment != null) MiniCodeCard(msg.attachment)
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
