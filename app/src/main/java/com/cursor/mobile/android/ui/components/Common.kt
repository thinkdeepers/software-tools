package com.cursor.mobile.android.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
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

private fun splitMessage(raw: String): List<TextPart> {
    val text = normalizeBreaks(raw)
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
    return parts
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
            if (part.code) CodeScroll(part.text, color) else PlainScroll(part.text, color, style, textAlign)
        }
    }
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
        lineHeight = 18.sp,
        softWrap = false,
        overflow = TextOverflow.Visible,
        maxLines = Int.MAX_VALUE,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
            .then(if (long) Modifier.height(220.dp).verticalScroll(vertical) else Modifier)
            .horizontalScroll(horizontal)
            .padding(8.dp)
    )
}

private val chatBody = TextStyle(fontSize = 13.sp, lineHeight = 17.sp)

@Composable
fun ChatBubble(msg: ChatMessage) {
    val user = msg.sender == Sender.USER
    val align = if (user) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start
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
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .animateContentSize()
    ) {
        if (msg.text.isNotBlank()) {
            MessageBody(msg.text, color, chatBody, partSpacing = 2.dp, textAlign = align)
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
