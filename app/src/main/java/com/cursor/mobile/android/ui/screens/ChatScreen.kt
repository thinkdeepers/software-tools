package com.cursor.mobile.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cursor.mobile.android.data.ChatMessage
import com.cursor.mobile.android.data.FakeRepository
import com.cursor.mobile.android.data.Sender
import com.cursor.mobile.android.ui.components.ChatBubble
import com.cursor.mobile.android.ui.components.ComposerBar
import com.cursor.mobile.android.ui.components.StatusChip
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(sessionId: String, onBack: () -> Unit, onOpenReview: (String) -> Unit) {
    val session = remember(sessionId) { FakeRepository.sessions.firstOrNull { it.id == sessionId } }
    var input by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(session?.model ?: FakeRepository.models.first()) }
    var menuOpen by remember { mutableStateOf(false) }
    val messages = remember(sessionId) { androidx.compose.runtime.mutableStateListOf(*FakeRepository.messagesFor(sessionId).toTypedArray()) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(session?.title ?: "对话", maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${session?.repo} · ${session?.branch}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
                actions = {
                    if (session != null) StatusChip(session.status)
                    IconButton(onClick = { onOpenReview(sessionId) }) { Icon(Icons.Filled.RateReview, contentDescription = "Review") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        ModelPill(model) { menuOpen = true }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            FakeRepository.models.forEach {
                                DropdownMenuItem(text = { Text(it) }, onClick = { model = it; menuOpen = false })
                            }
                        }
                    }
                    MachinePill("Cloud machine")
                }
                ComposerBar(
                    value = input,
                    onValueChange = { input = it },
                    model = model,
                    onSend = {
                        val text = input.trim()
                        if (text.isEmpty()) return@ComposerBar
                        input = ""
                        messages.add(ChatMessage("u${messages.size}", Sender.USER, text, "now"))
                        val streamId = "s${messages.size}"
                        messages.add(ChatMessage(streamId, Sender.AGENT, "", "now", isStreaming = true))
                        scope.launch {
                            FakeRepository.fakeAgentReply(text) { token ->
                                val idx = messages.indexOfFirst { it.id == streamId }
                                if (idx >= 0) messages[idx] = messages[idx].copy(text = token)
                            }
                            val idx = messages.indexOfFirst { it.id == streamId }
                            if (idx >= 0) messages[idx] = messages[idx].copy(isStreaming = false, time = "now")
                            listState.animateScrollToItem(messages.size - 1)
                        }
                    }
                )
                Text(
                    "语音 / 截图批注 / /指令 / MCP 与 iOS 对齐，Demo 为本地模拟流。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.Top
        ) {
            item {
                Text(
                    "实时流（占位）· 接后端后换 SSE/WebSocket",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            items(messages, key = { it.id }) {
                AnimatedVisibility(visible = true, enter = fadeIn() + slideInVertically { it / 4 }) {
                    ChatBubble(it)
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FakeRepository.slashCommands.take(3).forEach { cmd ->
                        SuggestChip(cmd) { input = "$cmd " }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelPill(model: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("◆", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
        Text(model, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
        Text("▾", color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun MachinePill(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@Composable
private fun SuggestChip(cmd: String, onClick: () -> Unit) {
    Text(
        cmd,
        fontFamily = com.cursor.mobile.android.ui.theme.CodeFont,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}
