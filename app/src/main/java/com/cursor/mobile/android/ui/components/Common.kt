package com.cursor.mobile.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cursor.mobile.android.data.AgentStatus
import com.cursor.mobile.android.data.ChatMessage
import com.cursor.mobile.android.data.DiffFile
import com.cursor.mobile.android.data.Sender

@Composable
fun StatusChip(status: AgentStatus) {
    val label = when (status) {
        AgentStatus.WORKING -> "运行中"
        AgentStatus.NEEDS_INPUT -> "等你确认"
        AgentStatus.READY_FOR_REVIEW -> "待 Review"
        AgentStatus.DONE -> "已完成"
        AgentStatus.FAILED -> "失败"
    }
    AssistChip(onClick = {}, label = { Text(label) })
}

@Composable
fun ChatBubble(msg: ChatMessage) {
    val isUser = msg.sender == Sender.USER
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(if (isUser) 18.dp else 14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isUser) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (isUser) "你 · ${msg.time}" else if (msg.sender == Sender.AGENT) "Agent · ${msg.time}" else msg.time,
                style = MaterialTheme.typography.labelSmall,
                color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                msg.text,
                color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium
            )
            if (msg.attachment != null) {
                Text("📎 ${msg.attachment}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            }
            if (msg.isStreaming) Text("输入中…", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun DiffRow(file: DiffFile) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(file.path, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(file.status, style = MaterialTheme.typography.labelMedium)
            }
            Text("+${file.additions} −${file.deletions}", style = MaterialTheme.typography.labelSmall)
            Text(file.preview, style = MaterialTheme.typography.bodySmall)
        }
    }
}
