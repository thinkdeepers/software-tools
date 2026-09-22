package com.cursor.mobile.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cursor.mobile.data.api.StreamEvent
import com.cursor.mobile.data.model.AgentDetail
import com.cursor.mobile.data.model.AgentMode
import com.cursor.mobile.data.model.ChatItem
import com.cursor.mobile.data.model.PromptImage
import com.cursor.mobile.data.model.RunStatus
import com.cursor.mobile.data.repository.CursorRepository
import com.cursor.mobile.ui.components.ChatBubble
import com.cursor.mobile.ui.components.ErrorBanner
import com.cursor.mobile.ui.components.LoadingBlock
import com.cursor.mobile.ui.components.StatusPill
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatUiState(
    val loading: Boolean = true,
    val agent: AgentDetail? = null,
    val messages: List<ChatItem> = emptyList(),
    val draft: String = "",
    val activeRunId: String? = null,
    val runStatus: RunStatus? = null,
    val sending: Boolean = false,
    val streaming: Boolean = false,
    val mode: AgentMode = AgentMode.AGENT,
    val error: String? = null,
    val info: String? = null,
    val pendingCount: Int = 0,
    val attachmentCount: Int = 0,
    val artifacts: List<String> = emptyList()
)

class ChatViewModel(
    private val agentId: String,
    private val repository: CursorRepository
) : ViewModel() {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var streamJob: Job? = null
    private val queue = ArrayDeque<Pair<String, List<PromptImage>>>()
    private val attachments = mutableListOf<PromptImage>()

    init {
        bootstrap()
    }

    private fun bootstrap() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                val agent = repository.getAgent(agentId)
                val history = repository.loadConversation(agentId)
                agent to history
            }.onSuccess { (agent, history) ->
                _state.update {
                    it.copy(
                        loading = false,
                        agent = agent,
                        messages = history,
                        activeRunId = agent.latestRunId
                    )
                }
                agent.latestRunId?.let { runId ->
                    val run = runCatching { repository.getRun(agentId, runId) }.getOrNull()
                    val status = RunStatus.from(run?.status)
                    _state.update { it.copy(runStatus = status) }
                    if (status.isActive) {
                        startStreaming(runId, seedAssistant = false)
                    }
                }
            }.onFailure { t ->
                _state.update {
                    it.copy(loading = false, error = repository.mapError(t))
                }
            }
        }
    }

    fun onDraftChange(value: String) = _state.update { it.copy(draft = value) }

    fun onModeChange(mode: AgentMode) = _state.update { it.copy(mode = mode) }

    fun addImage(image: PromptImage) {
        if (attachments.size >= 5) {
            _state.update { it.copy(error = "最多 5 张图片") }
            return
        }
        attachments += image
        _state.update { it.copy(attachmentCount = attachments.size, error = null) }
    }

    fun sendFollowUp() {
        val text = _state.value.draft.trim()
        val images = attachments.toList()
        if (text.isEmpty() && images.isEmpty()) return
        if (_state.value.sending || _state.value.streaming) {
            queue.addLast(text to images)
            attachments.clear()
            _state.update {
                it.copy(
                    draft = "",
                    attachmentCount = 0,
                    pendingCount = queue.size,
                    info = "已排队 ${queue.size} 条，当前步骤结束后发送"
                )
            }
            return
        }
        dispatch(text, images)
    }

    private fun dispatch(text: String, images: List<PromptImage>) {
        attachments.clear()
        viewModelScope.launch {
            _state.update {
                it.copy(
                    sending = true,
                    error = null,
                    attachmentCount = 0,
                    pendingCount = queue.size,
                    messages = it.messages + ChatItem.UserMessage(
                        id = UUID.randomUUID().toString(),
                        text = text.ifBlank { "（附图）" }
                    ),
                    draft = ""
                )
            }
            runCatching {
                repository.createFollowUp(
                    agentId = agentId,
                    prompt = text,
                    mode = _state.value.mode.apiValue,
                    images = images
                )
            }.onSuccess { run ->
                _state.update {
                    it.copy(
                        sending = false,
                        activeRunId = run.id,
                        runStatus = RunStatus.from(run.status)
                    )
                }
                startStreaming(run.id, seedAssistant = true)
            }.onFailure { t ->
                _state.update {
                    it.copy(sending = false, error = repository.mapError(t))
                }
            }
        }
    }

    private fun flushQueue() {
        if (queue.isEmpty() || _state.value.sending || _state.value.streaming) return
        val (text, images) = queue.removeFirst()
        _state.update { it.copy(pendingCount = queue.size) }
        dispatch(text, images)
    }

    fun cancelActiveRun() {
        val runId = _state.value.activeRunId ?: return
        viewModelScope.launch {
            runCatching { repository.cancelRun(agentId, runId) }
                .onSuccess {
                    streamJob?.cancel()
                    _state.update {
                        it.copy(
                            streaming = false,
                            runStatus = RunStatus.CANCELLED,
                            info = "已取消当前运行"
                        )
                    }
                }
                .onFailure { t ->
                    _state.update { it.copy(error = repository.mapError(t)) }
                }
        }
    }

    fun showArtifacts() {
        viewModelScope.launch {
            runCatching { repository.listArtifacts(agentId) }
                .onSuccess { items ->
                    _state.update {
                        it.copy(
                            artifacts = items.map { item -> item.path },
                            info = if (items.isEmpty()) "这个任务还没有截图或产物" else null
                        )
                    }
                }
                .onFailure { t -> _state.update { it.copy(error = repository.mapError(t)) } }
        }
    }

    suspend fun artifactUrl(path: String): String = repository.artifactUrl(agentId, path)

    fun archiveAgent(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { repository.archiveAgent(agentId) }
                .onSuccess { onDone() }
                .onFailure { t ->
                    _state.update { it.copy(error = repository.mapError(t)) }
                }
        }
    }

    fun deleteAgent(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { repository.deleteAgent(agentId) }
                .onSuccess { onDone() }
                .onFailure { t ->
                    _state.update { it.copy(error = repository.mapError(t)) }
                }
        }
    }

    fun refresh() = bootstrap()

    private fun startStreaming(runId: String, seedAssistant: Boolean) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            var assistantId = "assistant-$runId"
            var thinkingId = "thinking-$runId"
            if (seedAssistant) {
                _state.update {
                    it.copy(
                        streaming = true,
                        messages = it.messages + ChatItem.AssistantMessage(
                            id = assistantId,
                            text = "",
                            isStreaming = true
                        )
                    )
                }
            } else {
                _state.update { it.copy(streaming = true) }
            }

            repository.streamRun(agentId, runId).collect { event ->
                when (event) {
                    is StreamEvent.Status -> {
                        _state.update { it.copy(runStatus = event.status) }
                    }
                    is StreamEvent.AssistantDelta -> {
                        _state.update { state ->
                            val messages = state.messages.toMutableList()
                            val index = messages.indexOfLast {
                                it is ChatItem.AssistantMessage && it.id == assistantId
                            }
                            if (index >= 0) {
                                val current = messages[index] as ChatItem.AssistantMessage
                                messages[index] = current.copy(
                                    text = current.text + event.text,
                                    isStreaming = true
                                )
                            } else {
                                messages += ChatItem.AssistantMessage(
                                    id = assistantId,
                                    text = event.text,
                                    isStreaming = true
                                )
                            }
                            state.copy(messages = messages)
                        }
                    }
                    is StreamEvent.ThinkingDelta -> {
                        _state.update { state ->
                            val messages = state.messages.toMutableList()
                            val index = messages.indexOfLast {
                                it is ChatItem.ThinkingMessage && it.id == thinkingId
                            }
                            if (index >= 0) {
                                val current = messages[index] as ChatItem.ThinkingMessage
                                messages[index] = current.copy(
                                    text = current.text + event.text,
                                    isStreaming = true
                                )
                            } else {
                                messages += ChatItem.ThinkingMessage(
                                    id = thinkingId,
                                    text = event.text,
                                    isStreaming = true
                                )
                            }
                            state.copy(messages = messages)
                        }
                    }
                    is StreamEvent.ToolCall -> {
                        _state.update { state ->
                            val messages = state.messages.toMutableList()
                            val index = messages.indexOfLast {
                                it is ChatItem.ToolCallMessage && it.id == event.callId
                            }
                            val item = ChatItem.ToolCallMessage(
                                id = event.callId,
                                name = event.name,
                                status = event.status,
                                detail = event.result ?: event.args
                            )
                            if (index >= 0) messages[index] = item else messages += item
                            state.copy(messages = messages)
                        }
                    }
                    is StreamEvent.Result -> {
                        _state.update { state ->
                            val messages = state.messages.toMutableList()
                            val index = messages.indexOfLast {
                                it is ChatItem.AssistantMessage && it.id == assistantId
                            }
                            val finalText = event.text
                            if (!finalText.isNullOrBlank()) {
                                if (index >= 0) {
                                    messages[index] = ChatItem.AssistantMessage(
                                        id = assistantId,
                                        text = finalText,
                                        isStreaming = false
                                    )
                                } else {
                                    messages += ChatItem.AssistantMessage(
                                        id = assistantId,
                                        text = finalText
                                    )
                                }
                            } else if (index >= 0) {
                                val current = messages[index] as ChatItem.AssistantMessage
                                messages[index] = current.copy(isStreaming = false)
                            }
                            if (event.branches.isNotEmpty()) {
                                messages += ChatItem.GitSummary(
                                    id = "git-$runId",
                                    branches = event.branches
                                )
                            }
                            state.copy(
                                messages = messages,
                                runStatus = event.status,
                                streaming = false,
                                agent = state.agent?.copy(latestRunId = runId)
                            )
                        }
                    }
                    is StreamEvent.Error -> {
                        _state.update {
                            it.copy(
                                streaming = false,
                                error = listOfNotNull(event.code, event.message)
                                    .joinToString(": ")
                                    .ifBlank { "流式输出失败" }
                            )
                        }
                    }
                    StreamEvent.Done -> {
                        _state.update { state ->
                            val messages = state.messages.map { item ->
                                when (item) {
                                    is ChatItem.AssistantMessage -> item.copy(isStreaming = false)
                                    is ChatItem.ThinkingMessage -> item.copy(isStreaming = false)
                                    else -> item
                                }
                            }
                            state.copy(messages = messages, streaming = false)
                        }
                    }
                    StreamEvent.Heartbeat -> Unit
                }
            }
            _state.update { it.copy(streaming = false) }
            flushQueue()
        }
    }

    companion object {
        fun factory(agentId: String, repository: CursorRepository) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ChatViewModel(agentId, repository) as T
                }
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    agentId: String,
    repository: CursorRepository,
    onBack: () -> Unit,
    onOpenReview: (String) -> Unit = {},
    incomingPrompt: String = "",
    onIncomingConsumed: () -> Unit = {},
    viewModel: ChatViewModel = viewModel(
        key = agentId,
        factory = ChatViewModel.factory(agentId, repository)
    )
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(incomingPrompt) {
        if (incomingPrompt.isNotBlank()) {
            viewModel.onDraftChange(incomingPrompt)
            onIncomingConsumed()
        }
    }

    LaunchedEffect(state.messages.size, state.streaming) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.agent?.name ?: "云代理对话",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusPill(state.agent?.status)
                            state.runStatus?.let {
                                Text(
                                    text = " · Run ${it.name}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.streaming) {
                        IconButton(onClick = viewModel::cancelActiveRun) {
                            Icon(Icons.Default.Stop, contentDescription = "停止")
                        }
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("产物") },
                            onClick = {
                                menuOpen = false
                                viewModel.showArtifacts()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("刷新") },
                            onClick = {
                                menuOpen = false
                                viewModel.refresh()
                            }
                        )
                        state.agent?.url?.let { url ->
                            DropdownMenuItem(
                                text = { Text("在网页打开") },
                                onClick = {
                                    menuOpen = false
                                    uriHandler.openUri(url)
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("归档") },
                            onClick = {
                                menuOpen = false
                                viewModel.archiveAgent(onBack)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("永久删除") },
                            onClick = {
                                menuOpen = false
                                confirmDelete = true
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            when {
                state.loading -> LoadingBlock("加载对话…")
                else -> {
                    state.error?.let {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            ErrorBanner(it)
                        }
                    }
                    state.info?.let {
                        Text(
                            text = it,
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    state.artifacts.forEach { path ->
                        androidx.compose.material3.TextButton(onClick = {
                            scope.launch {
                                runCatching { viewModel.artifactUrl(path) }
                                    .onSuccess { uriHandler.openUri(it) }
                            }
                        }) { Text(path) }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(state.messages, key = { index, item -> "$index-${item.id}" }) { _, item ->
                            ChatBubble(item, onOpenReview)
                        }
                    }

                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(selected = false, onClick = {
                            viewModel.onDraftChange("请根据当前 PR 的审查意见逐条修复。")
                        }, label = { Text("/review") })
                        FilterChip(selected = false, onClick = {
                            viewModel.onDraftChange("查看失败的检查并修复。")
                        }, label = { Text("/ci") })
                        AgentMode.entries.forEach { mode ->
                            FilterChip(
                                selected = state.mode == mode,
                                onClick = { viewModel.onModeChange(mode) },
                                label = { Text(mode.label) },
                                enabled = !state.streaming
                            )
                        }
                    }
                    if (state.pendingCount > 0 || state.attachmentCount > 0) {
                        Text(
                            text = buildString {
                                if (state.pendingCount > 0) append("排队 ${state.pendingCount} 条  ")
                                if (state.attachmentCount > 0) append("附图 ${state.attachmentCount}")
                            },
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
                    ) { uri ->
                        val bitmap = uri?.let {
                            context.contentResolver.openInputStream(it)?.use { stream ->
                                android.graphics.BitmapFactory.decodeStream(stream)
                            }
                        }
                        if (bitmap != null) viewModel.addImage(com.cursor.mobile.ui.media.bitmapToPromptImage(bitmap))
                    }
                    val voice = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
                    ) { result ->
                        result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                            ?.firstOrNull()?.let(viewModel::onDraftChange)
                    }
                    Row(modifier = Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.TextButton(onClick = {
                            picker.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }) { Text("图片") }
                        androidx.compose.material3.TextButton(onClick = {
                            voice.launch(
                                android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                            )
                        }) { Text("语音") }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = state.draft,
                            onValueChange = viewModel::onDraftChange,
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("继续给云代理下指令…") },
                            minLines = 1,
                            maxLines = 5
                        )
                        FilledIconButton(
                            onClick = viewModel::sendFollowUp,
                            enabled = !state.sending && (state.draft.isNotBlank() || state.attachmentCount > 0)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("永久删除此代理？") },
            text = { Text("删除后无法恢复。若只需停用，请使用归档。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.deleteAgent(onBack)
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}
