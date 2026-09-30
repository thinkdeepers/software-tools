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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cursor.mobile.android.data.AgentStore
import com.cursor.mobile.android.data.ChatMessage
import com.cursor.mobile.android.data.CursorApi
import com.cursor.mobile.android.data.ModelTier
import com.cursor.mobile.android.data.PrefsRepository
import com.cursor.mobile.android.data.Sender
import com.cursor.mobile.android.data.suggestedModelId
import com.cursor.mobile.android.ui.components.ChatBubble
import com.cursor.mobile.android.ui.components.ComposerBar
import com.cursor.mobile.android.ui.components.ModelTierPicker
import com.cursor.mobile.android.ui.components.StatusChip
import kotlinx.coroutines.launch

private val slashCommands = listOf("/remote-control", "/fix-ci", "/review", "/move-to-cloud", "/summarize")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    onBack: () -> Unit,
    onOpenReview: (String) -> Unit,
    onSessionExpired: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = AgentStore.sessions.firstOrNull { it.id == sessionId }
    var input by remember { mutableStateOf("") }
    val savedTier by PrefsRepository.tierFlow(context).collectAsState(ModelTier.BALANCED)
    val savedModel by PrefsRepository.modelFlow(context).collectAsState(ModelTier.BALANCED.model)
    var tierOpen by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    val messages = remember(sessionId) { mutableStateListOf<ChatMessage>() }
    var loading by remember(sessionId) { mutableStateOf(true) }
    var loadError by remember(sessionId) { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val catalog = AgentStore.catalog

    LaunchedEffect(sessionId) {
        loading = true
        loadError = null
        try {
            val loaded = AgentStore.loadMessages(context, sessionId)
            messages.clear()
            messages.addAll(loaded)
        } catch (e: CursorApi.Unauthorized) {
            onSessionExpired(e.message ?: "登录已失效")
        } catch (e: Exception) {
            loadError = e.message ?: "消息历史加载失败"
        } finally {
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(session?.title ?: "对话", maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOf(
                                session?.repo?.ifBlank { "未关联仓库" } ?: "未关联仓库",
                                session?.branch?.ifBlank { "分支同步中" } ?: "分支同步中"
                            ).joinToString(" · "),
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
                        ModelPill("${savedTier.label} · $savedModel") { tierOpen = true }
                        DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                            val choices = catalog.ifEmpty { null }
                            if (choices == null) {
                                ModelTier.entries.forEach { tier ->
                                    DropdownMenuItem(
                                        text = { Text(tier.model) },
                                        onClick = {
                                            modelMenu = false
                                            scope.launch { PrefsRepository.saveSelection(context, savedTier, tier.model) }
                                        }
                                    )
                                }
                            } else {
                                choices.forEach { model ->
                                    DropdownMenuItem(
                                        text = { Text(model.displayName) },
                                        onClick = {
                                            modelMenu = false
                                            scope.launch { PrefsRepository.saveSelection(context, savedTier, model.id) }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    MachinePill("Cloud")
                }
                AnimatedVisibility(visible = tierOpen) {
                    ModelTierPicker(savedTier, savedModel) { tier ->
                        scope.launch {
                            PrefsRepository.saveSelection(context, tier, suggestedModelId(tier, catalog.toList()))
                        }
                        tierOpen = false
                    }
                }
                ComposerBar(
                    value = input,
                    onValueChange = { input = it },
                    model = savedModel,
                    enabled = !sending,
                    onSend = {
                        val text = input.trim()
                        if (text.isEmpty() || sending) return@ComposerBar
                        input = ""
                        sending = true
                        messages.add(ChatMessage("u${messages.size}", Sender.USER, text, "now"))
                        val streamId = "s${messages.size}"
                        messages.add(ChatMessage(streamId, Sender.AGENT, "", "now", isStreaming = true))
                        scope.launch {
                            try {
                                val modelSent = AgentStore.followUp(context, sessionId, text, savedTier, savedModel) { token ->
                                    val idx = messages.indexOfFirst { it.id == streamId }
                                    if (idx >= 0) messages[idx] = messages[idx].copy(text = token)
                                }
                                val idx = messages.indexOfFirst { it.id == streamId }
                                if (idx >= 0) messages[idx] = messages[idx].copy(isStreaming = false, time = "now")
                                if (!modelSent) {
                                    messages.add(
                                        ChatMessage(
                                            "note$streamId",
                                            Sender.SYSTEM,
                                            "跟随消息接口未接受模型参数，本条已按会话原模型发送。",
                                            ""
                                        )
                                    )
                                }
                                if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
                            } catch (e: CursorApi.Unauthorized) {
                                onSessionExpired(e.message ?: "登录已失效")
                            } catch (e: Exception) {
                                val idx = messages.indexOfFirst { it.id == streamId }
                                if (idx >= 0) {
                                    messages[idx] = messages[idx].copy(
                                        isStreaming = false,
                                        text = e.message ?: "发送失败"
                                    )
                                }
                            } finally {
                                sending = false
                            }
                        }
                    }
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
            if (loading) {
                item {
                    Box(Modifier.padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
            }
            if (!loadError.isNullOrBlank()) {
                item {
                    Text(
                        loadError ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
            if (!loading && messages.isEmpty() && loadError.isNullOrBlank()) {
                item {
                    Text(
                        "这条会话还没有消息。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
            items(messages, key = { it.id }) { message ->
                AnimatedVisibility(visible = true, enter = fadeIn() + slideInVertically { it / 4 }) {
                    ChatBubble(message)
                }
            }
            item {
                Row(modifier = Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    slashCommands.take(3).forEach { cmd ->
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
