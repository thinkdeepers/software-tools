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
import com.cursor.mobile.data.model.ModelInfo
import com.cursor.mobile.data.model.ModelSelection
import com.cursor.mobile.data.model.RepositoryItem
import com.cursor.mobile.data.repository.CursorRepository
import com.cursor.mobile.ui.components.ErrorBanner
import com.cursor.mobile.ui.components.LoadingBlock
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
    val error: String? = null,
    val repoWarning: String? = null
)

class CreateViewModel(
    private val repository: CursorRepository,
    private val sessionStore: SessionStore
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

            _state.update {
                it.copy(
                    loadingMeta = false,
                    models = models,
                    repositories = repos,
                    selectedModelId = preferredModel
                        ?: models.firstOrNull()?.id,
                    selectedRepoUrl = preferredRepo ?: repos.firstOrNull()?.url,
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
    fun onModelSelected(id: String?) = _state.update {
        it.copy(selectedModelId = id?.takeIf { value -> value.isNotBlank() })
    }
    fun onRepoSelected(url: String?) = _state.update {
        it.copy(
            selectedRepoUrl = url?.takeIf { value -> value.isNotBlank() },
            manualRepoUrl = "",
            error = null
        )
    }
    fun onModeSelected(mode: AgentMode) = _state.update { it.copy(mode = mode) }
    fun onAutoCreatePRChange(value: Boolean) = _state.update { it.copy(autoCreatePR = value) }

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
            val model = current.selectedModelId?.let { ModelSelection(id = it) }
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
                    name = current.name
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
        fun factory(repository: CursorRepository, sessionStore: SessionStore) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return CreateViewModel(repository, sessionStore) as T
                }
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateAgentScreen(
    repository: CursorRepository,
    sessionStore: SessionStore,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    viewModel: CreateViewModel = viewModel(
        factory = CreateViewModel.factory(repository, sessionStore)
    )
) {
    val state by viewModel.state.collectAsState()
    var modelExpanded by remember { mutableStateOf(false) }
    var repoExpanded by remember { mutableStateOf(false) }

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
                ExposedDropdownMenuBox(
                    expanded = modelExpanded,
                    onExpandedChange = { modelExpanded = it }
                ) {
                    val selectedLabel = state.models
                        .find { it.id == state.selectedModelId }
                        ?.label
                        ?: state.selectedModelId
                        ?: "使用账号默认模型"
                    OutlinedTextField(
                        value = selectedLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("选择模型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("账号默认模型") },
                            onClick = {
                                viewModel.onModelSelected(null)
                                modelExpanded = false
                            }
                        )
                        state.models.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(model.label)
                                        Text(
                                            text = model.id,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.onModelSelected(model.id)
                                    modelExpanded = false
                                }
                            )
                        }
                    }
                }

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
