package com.cursor.mobile.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cursor.mobile.android.data.ChatMessage
import com.cursor.mobile.android.data.FakeRepository
import com.cursor.mobile.android.data.Sender
import com.cursor.mobile.android.ui.components.ChatBubble
import com.cursor.mobile.android.ui.components.ComposerBar
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
                title = { Text(session?.title ?: "对话", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") } },
                actions = { IconButton(onClick = { onOpenReview(sessionId) }) { Icon(Icons.Filled.RateReview, contentDescription = "Review") } }
            )
        },
        bottomBar = {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { menuOpen = true }, label = { Text(model) })
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        FakeRepository.models.forEach {
                            DropdownMenuItem(text = { Text(it) }, onClick = { model = it; menuOpen = false })
                        }
                    }
                    AssistChip(onClick = {}, label = { Text("Cloud machine") })
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
                Text("语音 / 截图批注 / /指令 / MCP 与 iOS 对齐，Demo 为本地模拟流。", style = MaterialTheme.typography.labelSmall)
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.Top
        ) {
            item { Text("${session?.repo} · ${session?.branch} · 实时流（占位）", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 8.dp)) }
            items(messages, key = { it.id }) { ChatBubble(it) }
            item {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FakeRepository.slashCommands.take(3).forEach { cmd -> AssistChip(onClick = { input = "$cmd " }, label = { Text(cmd) }) }
                }
            }
        }
    }
}
