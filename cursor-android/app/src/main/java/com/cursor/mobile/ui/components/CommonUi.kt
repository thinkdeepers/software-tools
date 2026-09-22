package com.cursor.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cursor.mobile.data.model.AgentSummary
import com.cursor.mobile.data.model.ChatItem
import com.cursor.mobile.ui.theme.SuccessGreen

@Composable
fun LoadingBlock(message: String = "加载中…") {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ErrorBanner(message: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
fun StatusPill(status: String?) {
    val normalized = status?.uppercase().orEmpty()
    val color = when (normalized) {
        "ACTIVE", "RUNNING", "CREATING" -> MaterialTheme.colorScheme.primary
        "IDLE", "FINISHED" -> SuccessGreen
        "ARCHIVED", "CANCELLED", "EXPIRED" -> MaterialTheme.colorScheme.onSurfaceVariant
        "ERROR" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = status ?: "UNKNOWN",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun AgentCard(
    agent: AgentSummary,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = agent.name?.ifBlank { null } ?: agent.id,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(agent.status)
                Text(
                    text = agent.updatedAt?.take(16)?.replace('T', ' ') ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ChatBubble(item: ChatItem, onOpenPull: (String) -> Unit = {}) {
    when (item) {
        is ChatItem.UserMessage -> MessageBubble(
            title = "你",
            text = item.text,
            alignEnd = true,
            container = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        )
        is ChatItem.AssistantMessage -> MessageBubble(
            title = if (item.isStreaming) "云程 · 生成中" else "云程",
            text = item.text.ifBlank { "…" },
            alignEnd = false,
            container = MaterialTheme.colorScheme.surfaceVariant
        )
        is ChatItem.ThinkingMessage -> MessageBubble(
            title = "Thinking",
            text = item.text,
            alignEnd = false,
            container = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
            muted = true
        )
        is ChatItem.ToolCallMessage -> {
            AssistChip(
                onClick = {},
                label = {
                    Text("${item.name} · ${item.status}")
                }
            )
            item.detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it.take(400),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
            }
        }
        is ChatItem.SystemMessage -> MessageBubble(
            title = "系统",
            text = item.text,
            alignEnd = false,
            container = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
            muted = true
        )
        is ChatItem.GitSummary -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp)
            ) {
                Text("代码变更", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                item.branches.forEach { branch ->
                    Text(
                        text = listOfNotNull(
                            branch.repoUrl,
                            branch.branch?.let { "分支: $it" },
                            branch.prUrl?.let { "PR: $it" }
                        ).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    branch.prUrl?.let { url ->
                        androidx.compose.material3.TextButton(onClick = { onOpenPull(url) }) {
                            Text("打开评审")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    title: String,
    text: String,
    alignEnd: Boolean,
    container: androidx.compose.ui.graphics.Color,
    muted: Boolean = false
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp, start = 4.dp, end = 4.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(if (alignEnd) 0.92f else 1f)
                .clip(RoundedCornerShape(16.dp))
                .background(container)
                .padding(12.dp)
        ) {
            Text(
                text = text,
                color = if (muted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}
