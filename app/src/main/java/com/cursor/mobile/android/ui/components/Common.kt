package com.cursor.mobile.android.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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

@Composable
fun ChatBubble(msg: ChatMessage) {
    val isUser = msg.sender == Sender.USER
    if (isUser) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF3B63F2), Color(0xFF7C3AED))
                    )
                )
                .padding(14.dp)
                .animateContentSize()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("你 · ${msg.time}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                Text(msg.text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }
    } else if (msg.sender == Sender.SYSTEM) {
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(
                msg.text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    } else {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)),
            shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp
        ) {
            Column(
                Modifier
                    .padding(14.dp)
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(accentGradient()),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("◈", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("Agent · ${msg.time}", style = MaterialTheme.typography.labelMedium)
                    }
                    if (msg.isStreaming) TypingDots()
                }
                Text(msg.text, style = MaterialTheme.typography.bodyMedium)
                if (msg.attachment != null) {
                    MiniCodeCard(msg.attachment)
                }
            }
        }
    }
}

@Composable
fun MiniCodeCard(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.background)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 28.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(accentGradient())
        )
        Text(text, fontFamily = CodeFont, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
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
            modifier = Modifier.fillMaxWidth().padding(12.dp),
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
                .padding(12.dp)
        )
        Text(
            "+$additions  −$deletions",
            fontFamily = CodeFont,
            fontSize = 11.sp,
            color = CursorPalette.Success,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun DiffRow(file: DiffFile) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
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
fun EmptyState(title: String, hint: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(accentGradient()),
            contentAlignment = Alignment.Center
        ) {
            Text("◈", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
