package com.cursor.mobile.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cursor.mobile.data.model.AgentSummary
import com.cursor.mobile.data.repository.CursorRepository
import com.cursor.mobile.notify.AgentWatch
import com.cursor.mobile.ui.components.AgentCard
import com.cursor.mobile.ui.components.ErrorBanner
import com.cursor.mobile.ui.components.LoadingBlock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val agents: List<AgentSummary> = emptyList(),
    val accountLabel: String? = null,
    val filter: String = "active",
    val query: String = "",
    val error: String? = null
)

class HomeViewModel(
    private val repository: CursorRepository,
    private val watch: AgentWatch
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    fun refresh(initial: Boolean = false) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = initial,
                    refreshing = !initial,
                    error = null
                )
            }
            runCatching {
                val me = runCatching { repository.me() }.getOrNull()
                val agents = repository.listAgents(includeArchived = _state.value.filter == "archived")
                me to agents
            }.onSuccess { (me, agents) ->
                watch.publish(agents)
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        agents = agents,
                        accountLabel = me?.userEmail ?: me?.apiKeyName,
                        error = null
                    )
                }
            }.onFailure { t ->
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = repository.mapError(t)
                    )
                }
            }
        }
    }

    fun setFilter(value: String) {
        _state.update { it.copy(filter = value) }
        refresh()
    }

    fun setQuery(value: String) = _state.update { it.copy(query = value) }

    companion object {
        fun factory(repository: CursorRepository, watch: AgentWatch) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HomeViewModel(repository, watch) as T
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: CursorRepository,
    watch: AgentWatch,
    onOpenAgent: (String) -> Unit,
    onCreateAgent: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(repository, watch))
) {
    val state by viewModel.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh(initial = viewModel.state.value.agents.isEmpty())
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("收件箱", fontWeight = FontWeight.Bold)
                        state.accountLabel?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateAgent) {
                Icon(Icons.Default.Add, contentDescription = "新建云任务")
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.loading -> LoadingBlock("正在同步云代理…")
                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("active" to "进行中", "all" to "全部", "archived" to "归档").forEach { (id, label) ->
                                androidx.compose.material3.FilterChip(
                                    selected = state.filter == id,
                                    onClick = { viewModel.setFilter(id) },
                                    label = { Text(label) }
                                )
                            }
                        }
                        androidx.compose.material3.OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::setQuery,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            placeholder = { Text("搜索任务") },
                            singleLine = true
                        )
                        state.error?.let {
                            Box(modifier = Modifier.padding(16.dp)) {
                                ErrorBanner(it)
                            }
                        }
                        val shown = state.agents.filter { agent ->
                            val status = agent.status?.uppercase().orEmpty()
                            val matchesFilter = when (state.filter) {
                                "active" -> status in setOf("ACTIVE", "RUNNING", "CREATING")
                                "archived" -> status == "ARCHIVED"
                                else -> status != "ARCHIVED"
                            }
                            val q = state.query.trim()
                            matchesFilter && (q.isBlank() || agent.name.orEmpty().contains(q, true) || agent.id.contains(q, true))
                        }
                        if (shown.isEmpty() && state.error == null) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("还没有云代理任务")
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "点击右下角 + 选择仓库与模型，开始 AI 云编程",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(shown, key = { it.id }) { agent ->
                                    AgentCard(agent = agent, onClick = { onOpenAgent(agent.id) })
                                }
                                item {
                                    Spacer(Modifier.height(72.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
