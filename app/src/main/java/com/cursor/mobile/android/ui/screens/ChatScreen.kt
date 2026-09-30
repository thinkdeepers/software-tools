package com.cursor.mobile.android.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursor.mobile.android.data.AgentStore
import com.cursor.mobile.android.data.ChatMessage
import com.cursor.mobile.android.data.CursorApi
import com.cursor.mobile.android.data.ModelTier
import com.cursor.mobile.android.data.PrefsRepository
import com.cursor.mobile.android.data.Sender
import com.cursor.mobile.android.data.modelChoices
import com.cursor.mobile.android.ui.components.ChatBubble
import com.cursor.mobile.android.ui.components.ComposerAttachment
import com.cursor.mobile.android.ui.components.ComposerBar
import com.cursor.mobile.android.ui.components.ModelPicker
import com.cursor.mobile.android.ui.components.ModelTierPicker
import com.cursor.mobile.android.ui.components.StatusChip
import kotlinx.coroutines.launch

private val slashCommands = listOf("/remote-control", "/fix-ci", "/review", "/move-to-cloud", "/summarize")

private fun attachmentName(context: android.content.Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val name = cursor.getString(0)
            if (!name.isNullOrBlank()) return name
        }
    }
    return uri.lastPathSegment ?: "附件"
}

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
    val attachments = remember { mutableStateListOf<ComposerAttachment>() }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) attachments.add(ComposerAttachment(uri.toString(), attachmentName(context, uri)))
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) attachments.add(ComposerAttachment(uri.toString(), attachmentName(context, uri)))
    }
    val persistedTier by PrefsRepository.tierFlow(context).collectAsState(ModelTier.BALANCED)
    val persistedModel by PrefsRepository.modelFlow(context).collectAsState(ModelTier.BALANCED.model)
    var picked by remember { mutableStateOf(false) }
    var savedTier by remember { mutableStateOf(ModelTier.BALANCED) }
    var savedModel by remember { mutableStateOf(ModelTier.BALANCED.model) }
    LaunchedEffect(persistedTier, persistedModel) {
        if (!picked) {
            savedTier = persistedTier
            savedModel = persistedModel
        }
    }
    val messages = remember(sessionId) { mutableStateListOf<ChatMessage>() }
    var loading by remember(sessionId) { mutableStateOf(true) }
    var loadError by remember(sessionId) { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val catalog = AgentStore.catalog

    LaunchedEffect(Unit) {
        try {
            AgentStore.ensureModels(context)
        } catch (e: CursorApi.Unauthorized) {
            onSessionExpired(e.message ?: "登录已失效")
        }
    }

    LaunchedEffect(sessionId, loading, messages.size, messages.lastOrNull()?.text) {
        if (!loading && messages.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

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
                                if (session?.scope == com.cursor.mobile.android.data.WorkScope.PROJECT) "Projects" else "Repositories",
                                session?.groupLabel?.ifBlank { null } ?: session?.repo?.ifBlank { null } ?: "未归类"
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
                    ModelPicker(savedModel, modelChoices(catalog.toList())) { model ->
                        picked = true
                        savedModel = model.id
                        scope.launch { PrefsRepository.saveSelection(context, savedTier, model.id) }
                    }
                    MachinePill("Cloud")
                }
                ModelTierPicker(savedTier, savedModel) { tier ->
                    picked = true
                    savedTier = tier
                    scope.launch { PrefsRepository.saveSelection(context, tier, savedModel) }
                }
                ComposerBar(
                    value = input,
                    onValueChange = { input = it },
                    model = savedModel,
                    enabled = !sending,
                    attachments = attachments,
                    onRemoveAttachment = { index -> if (index in attachments.indices) attachments.removeAt(index) },
                    onPickPhoto = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onPickFile = { filePicker.launch(arrayOf("*/*")) },
                    onSend = {
                        val text = input.trim()
                        val names = attachments.map { it.name }
                        if ((text.isEmpty() && names.isEmpty()) || sending) return@ComposerBar
                        val outgoing = if (names.isEmpty()) text else listOf(text, names.joinToString("\n") { "附件：$it" }).filter { it.isNotBlank() }.joinToString("\n")
                        input = ""
                        attachments.clear()
                        sending = true
                        messages.add(ChatMessage("u${messages.size}", Sender.USER, outgoing, "now"))
                        val streamId = "s${messages.size}"
                        messages.add(ChatMessage(streamId, Sender.AGENT, "", "now", isStreaming = true))
                        scope.launch {
                            try {
                                val modelSent = AgentStore.followUp(context, sessionId, outgoing, savedTier, savedModel) { token ->
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
                                if (messages.isNotEmpty()) listState.scrollToItem(0)
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
            reverseLayout = true,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .padding(vertical = 10.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    slashCommands.forEach { cmd ->
                        SuggestChip(cmd) { input = "$cmd " }
                    }
                }
            }
            items(messages.asReversed(), key = { it.id }) { message ->
                ChatBubble(message)
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
            if (loading) {
                item {
                    Box(Modifier.padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
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
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = Modifier
            .wrapContentWidth(unbounded = true)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}
