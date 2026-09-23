package com.cursor.mobile.ui.create

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cursor.mobile.data.local.SessionStore
import com.cursor.mobile.data.model.AgentMode
import com.cursor.mobile.data.model.EnvRef
import com.cursor.mobile.data.model.McpServerConfig
import com.cursor.mobile.data.model.ModelInfo
import com.cursor.mobile.data.model.ModelParam
import com.cursor.mobile.data.model.PromptImage
import com.cursor.mobile.data.model.ModelSelection
import com.cursor.mobile.data.model.RepositoryItem
import com.cursor.mobile.data.repository.CursorRepository
import com.cursor.mobile.ui.components.ErrorBanner
import com.cursor.mobile.ui.components.LoadingBlock
import com.cursor.mobile.ui.components.ModelPickerBar
import com.cursor.mobile.ui.components.defaultParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CreateUiState(
    val loadingMeta: Boolean = true,
    val submitting: Boolean = false,
    val models: List<ModelInfo> = emptyList(),
    val repositories: List<RepositoryItem> = emptyList(),
    val selectedModelId: String? = null,
    val selectedRepoUrl: String? = null,
    val startingRef: String = "main",
    val mode: AgentMode = AgentMode.AGENT,
    val autoCreatePR: Boolean = true,
    val name: String = "",
    val prompt: String = "",
    val manualRepoUrl: String = "",
    val worker: String = "cloud",
    val workerName: String = "",
    val modelParams: Map<String, String> = emptyMap(),
    val images: List<PromptImage> = emptyList(),
    val mcpName: String = "",
    val mcpUrl: String = "",
    val prUrl: String = "",
    val error: String? = null,
    val repoWarning: String? = null
)

class CreateViewModel(
    private val repository: CursorRepository,
    private val sessionStore: SessionStore,
    private val initialRepo: String = ""
) : ViewModel() {
    private val _state = MutableStateFlow(CreateUiState())
    val state: StateFlow<CreateUiState> = _state.asStateFlow()

    init {
        loadMeta()
    }

    private fun loadMeta() {
        viewModelScope.launch {
            _state.update { it.copy(loadingMeta = true, error = null) }
            val preferredModel = sessionStore.selectedModelId.first()
            val preferredRepo = sessionStore.selectedRepoUrl.first()
            val preferredMode = sessionStore.selectedMode.first()

            val models = runCatching { repository.listModels() }.getOrElse { emptyList() }
            val reposResult = runCatching { repository.listRepositories() }
            val repos = reposResult.getOrElse { emptyList() }
            val repoWarning = reposResult.exceptionOrNull()?.let { repository.mapError(it) }

            val chosenRepo = initialRepo.ifBlank { preferredRepo ?: repos.firstOrNull()?.url }
            val chosenModel = preferredModel ?: models.firstOrNull()?.id
            _state.update {
                it.copy(
                    loadingMeta = false,
                    models = models,
                    repositories = repos,
                    selectedModelId = chosenModel,
                    modelParams = models.find { model -> model.id == chosenModel }?.defaultParams().orEmpty(),
                    selectedRepoUrl = chosenRepo,
                    mode = AgentMode.entries.find { m -> m.apiValue == preferredMode } ?: AgentMode.AGENT,
                    repoWarning = repoWarning,
                    error = null
                )
            }
        }
    }

    fun onPromptChange(value: String) = _state.update { it.copy(prompt = value, error = null) }
    fun onNameChange(value: String) = _state.update { it.copy(name = value) }
    fun onStartingRefChange(value: String) = _state.update { it.copy(startingRef = value) }
    fun onManualRepoChange(value: String) = _state.update {
        it.copy(manualRepoUrl = value, selectedRepoUrl = null, error = null)
    }
    fun onModelSelected(id: String?) = _state.update { state ->
        val model = state.models.find { it.id == id }
        state.copy(
            selectedModelId = id?.takeIf { value -> value.isNotBlank() },
            modelParams = model?.defaultParams().orEmpty()
        )
    }
    fun onModelParams(params: Map<String, String>) = _state.update { it.copy(modelParams = params) }
    fun onRepoSelected(url: String?) = _state.update {
        it.copy(
            selectedRepoUrl = url?.takeIf { value -> value.isNotBlank() },
            manualRepoUrl = "",
            error = null
        )
    }
    fun onModeSelected(mode: AgentMode) = _state.update { it.copy(mode = mode) }
    fun onAutoCreatePRChange(value: Boolean) = _state.update { it.copy(autoCreatePR = value) }
    fun onWorker(value: String) = _state.update { it.copy(worker = value) }
    fun onWorkerName(value: String) = _state.update { it.copy(workerName = value) }
    fun onPrUrl(value: String) = _state.update { it.copy(prUrl = value) }
    fun onMcpName(value: String) = _state.update { it.copy(mcpName = value) }
    fun onMcpUrl(value: String) = _state.update { it.copy(mcpUrl = value) }
    fun onParam(id: String, value: String) = _state.update {
        it.copy(modelParams = it.modelParams + (id to value))
    }
    fun addImage(image: PromptImage) = _state.update {
        if (it.images.size >= 5) it.copy(error = "最多 5 张图片") else it.copy(images = it.images + image, error = null)
    }
    fun appendPrompt(text: String) = _state.update { it.copy(prompt = (it.prompt + "\n" + text).trim()) }

    fun submit(onCreated: (String) -> Unit) {
        val current = _state.value
        val prompt = current.prompt.trim()
        if (prompt.isEmpty()) {
            _state.update { it.copy(error = "请输入任务指令") }
            return
        }
        val repoUrl = current.manualRepoUrl.trim().ifBlank { null }
            ?: current.selectedRepoUrl
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            val model = current.selectedModelId?.let {
                ModelSelection(
                    id = it,
                    params = current.modelParams.map { (id, value) -> ModelParam(id, value) }
                )
            }
            val env = if (current.worker == "cloud") {
                null
            } else {
                EnvRef(type = current.worker, name = current.workerName.ifBlank { null })
            }
            val mcp = if (current.mcpName.isNotBlank() && current.mcpUrl.isNotBlank()) {
                listOf(McpServerConfig(name = current.mcpName.trim(), url = current.mcpUrl.trim()))
            } else {
                emptyList()
            }
            runCatching {
                sessionStore.setSelectedModelId(current.selectedModelId)
                sessionStore.setSelectedRepoUrl(repoUrl)
                sessionStore.setSelectedMode(current.mode.apiValue)
                repository.createAgent(
                    prompt = prompt,
                    repoUrl = repoUrl,
                    startingRef = current.startingRef,
                    model = model,
                    mode = current.mode.apiValue,
                    autoCreatePR = current.autoCreatePR,
                    name = current.name,
                    images = current.images,
                    env = env,
                    mcpServers = mcp,
                    prUrl = current.prUrl
                )
            }.onSuccess { (agent, _) ->
                _state.update { it.copy(submitting = false) }
                onCreated(agent.id)
            }.onFailure { t ->
                _state.update {
                    it.copy(submitting = false, error = repository.mapError(t))
                }
            }
        }
    }

    companion object {
        fun factory(repository: CursorRepository, sessionStore: SessionStore, initialRepo: String) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return CreateViewModel(repository, sessionStore, initialRepo) as T
                }
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateAgentScreen(
    repository: CursorRepository,
    sessionStore: SessionStore,
    initialRepo: String = "",
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    viewModel: CreateViewModel = viewModel(
        factory = CreateViewModel.factory(repository, sessionStore, initialRepo)
    )
) {
    val state by viewModel.state.collectAsState()
    var repoExpanded by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("新建云编程任务") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        if (state.loadingMeta) {
            BoxLoading(padding)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("模型", fontWeight = FontWeight.SemiBold)
                ModelPickerBar(
                    models = state.models,
                    selectedId = state.selectedModelId,
                    params = state.modelParams,
                    onModel = viewModel::onModelSelected,
                    onParams = viewModel::onModelParams
                )

                Text("仓库", fontWeight = FontWeight.SemiBold)
                state.repoWarning?.let {
                    Text(
                        text = "仓库列表拉取受限：$it\n可手动填写 GitHub 仓库 URL。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.repositories.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = repoExpanded,
                        onExpandedChange = { repoExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = state.selectedRepoUrl ?: "选择已连接仓库",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("已连接仓库") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(repoExpanded) },
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = repoExpanded,
                            onDismissRequest = { repoExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("不绑定仓库（纯对话）") },
                                onClick = {
                                    viewModel.onRepoSelected(null)
                                    repoExpanded = false
                                }
                            )
                            state.repositories.forEach { repo ->
                                DropdownMenuItem(
                                    text = { Text(repo.displayName) },
                                    onClick = {
                                        viewModel.onRepoSelected(repo.url)
                                        repoExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = state.manualRepoUrl,
                    onValueChange = viewModel::onManualRepoChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("或手动填写仓库 URL") },
                    placeholder = { Text("https://github.com/org/repo") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.startingRef,
                    onValueChange = viewModel::onStartingRefChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("起始分支 / commit") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.prUrl,
                    onValueChange = viewModel::onPrUrl,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("或在已有 PR 上工作") },
                    placeholder = { Text("https://github.com/org/repo/pull/1") },
                    singleLine = true
                )

                Text("运行位置", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("cloud" to "云端", "pool" to "机器池", "machine" to "我的机器").forEach { (id, label) ->
                        FilterChip(
                            selected = state.worker == id,
                            onClick = { viewModel.onWorker(id) },
                            label = { Text(label) }
                        )
                    }
                }
                if (state.worker != "cloud") {
                    OutlinedTextField(
                        value = state.workerName,
                        onValueChange = viewModel::onWorkerName,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (state.worker == "pool") "机器池名称" else "机器名称") },
                        singleLine = true
                    )
                }

                Text("快捷指令", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = false, onClick = { viewModel.onModeSelected(AgentMode.PLAN) }, label = { Text("/plan") })
                    FilterChip(selected = false, onClick = { viewModel.onModeSelected(AgentMode.AGENT) }, label = { Text("/agent") })
                    FilterChip(
                        selected = false,
                        onClick = { viewModel.appendPrompt("完成后打开 Pull Request，并总结改动、测试和风险。") },
                        label = { Text("/pr") }
                    )
                    FilterChip(
                        selected = false,
                        onClick = { viewModel.appendPrompt("查看失败的 CI，定位原因并修复。") },
                        label = { Text("/ci") }
                    )
                }

                Text("模式", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AgentMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.mode == mode,
                            onClick = { viewModel.onModeSelected(mode) },
                            label = { Text(mode.label) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("完成后自动创建 PR")
                        Text(
                            text = "对应电脑端 Cloud Agent 的 autoCreatePR",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.autoCreatePR,
                        onCheckedChange = viewModel::onAutoCreatePRChange
                    )
                }

                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("任务名称（可选）") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.prompt,
                    onValueChange = viewModel::onPromptChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    label = { Text("任务指令") },
                    placeholder = { Text("例如：给登录接口补上单元测试并修复失败用例") }
                )
                Text("已附图片 ${state.images.size}/5", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
                    ) { uri ->
                        uri ?: return@rememberLauncherForActivityResult
                        val bitmap = context.contentResolver
                            .openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                        if (bitmap != null) viewModel.addImage(com.cursor.mobile.ui.media.bitmapToPromptImage(bitmap))
                    }
                    val camera = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.TakePicturePreview()
                    ) { bitmap ->
                        if (bitmap != null) viewModel.addImage(com.cursor.mobile.ui.media.bitmapToPromptImage(bitmap))
                    }
                    val voice = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
                    ) { result ->
                        val spoken = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                            ?.firstOrNull()
                        if (!spoken.isNullOrBlank()) viewModel.appendPrompt(spoken)
                    }
                    androidx.compose.material3.OutlinedButton(onClick = {
                        picker.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    }) { Text("相册") }
                    androidx.compose.material3.OutlinedButton(onClick = { camera.launch(null) }) { Text("拍照") }
                    androidx.compose.material3.OutlinedButton(onClick = {
                        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                        voice.launch(intent)
                    }) { Text("语音") }
                }
                OutlinedTextField(
                    value = state.mcpName,
                    onValueChange = viewModel::onMcpName,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("本次 MCP 名称（可选）") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.mcpUrl,
                    onValueChange = viewModel::onMcpUrl,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("MCP 地址") },
                    placeholder = { Text("https://example.com/mcp") },
                    singleLine = true
                )

                state.error?.let { ErrorBanner(it) }

                Button(
                    onClick = { viewModel.submit(onCreated) },
                    enabled = !state.submitting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.submitting) "正在启动云代理…" else "开始云编程")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun BoxLoading(padding: androidx.compose.foundation.layout.PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
        LoadingBlock("正在加载模型与仓库…")
    }
}
