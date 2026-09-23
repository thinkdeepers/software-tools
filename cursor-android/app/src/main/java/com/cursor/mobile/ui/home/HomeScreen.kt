package com.cursor.mobile.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cursor.mobile.data.model.AgentDetail
import com.cursor.mobile.data.model.AgentSummary
import com.cursor.mobile.data.model.RepositoryItem
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
    val details: List<AgentDetail> = emptyList(),
    val repositories: List<RepositoryItem> = emptyList(),
    val collapsedRepos: Set<String> = emptySet(),
    val accountLabel: String? = null,
    val filter: String = "all",
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
                val includeArchived = _state.value.filter == "archived"
                val details = repository.listAgentDetails(includeArchived = includeArchived)
                val repositories = runCatching { repository.listRepositories() }.getOrDefault(emptyList())
                Triple(me, details, repositories)
            }.onSuccess { (me, details, repositories) ->
                val summaries = details.map {
                    AgentSummary(it.id, it.name, it.status, it.env, it.url, it.createdAt, it.updatedAt, it.latestRunId)
                }
                watch.publish(summaries)
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        agents = summaries,
                        details = details,
                        repositories = repositories,
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

    fun toggleRepo(key: String) {
        _state.update { state ->
            val next = state.collapsedRepos.toMutableSet()
            if (!next.add(key)) next.remove(key)
            state.copy(collapsedRepos = next)
        }
    }

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
    onCreateAgent: (String?) -> Unit,
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
                        Text("仓库", fontWeight = FontWeight.Bold)
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
            FloatingActionButton(onClick = { onCreateAgent(null) }) {
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
                    val sections = buildRepoSections(
                        repositories = state.repositories,
                        details = state.details,
                        filter = state.filter,
                        query = state.query
                    )
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("active" to "进行中", "all" to "全部", "archived" to "归档").forEach { (id, label) ->
                                FilterChip(
                                    selected = state.filter == id,
                                    onClick = { viewModel.setFilter(id) },
                                    label = { Text(label) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::setQuery,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            placeholder = { Text("搜索任务或仓库") },
                            singleLine = true
                        )
                        state.error?.let {
                            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                                ErrorBanner(it)
                            }
                        }
                        if (sections.isEmpty() && state.error == null) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("还没有仓库")
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "连接 GitHub 后下拉刷新，或点右下角开始一个对话",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                sections.forEach { section ->
                                    val expanded = section.key !in state.collapsedRepos
                                    val stableKey = section.key.ifBlank { "none" }
                                    item(key = "repo-$stableKey") {
                                        RepoHeader(
                                            title = section.title,
                                            subtitle = section.subtitle(),
                                            expanded = expanded,
                                            onToggle = { viewModel.toggleRepo(section.key) }
                                        )
                                    }
                                    if (expanded) {
                                        if (section.chats.isEmpty()) {
                                            item(key = "empty-$stableKey") {
                                                Text(
                                                    text = "还没有对话",
                                                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        } else {
                                            items(section.chats, key = { it.id }) { agent ->
                                                AgentCard(
                                                    agent = agent.toSummary(),
                                                    onClick = { onOpenAgent(agent.id) },
                                                    modifier = Modifier.padding(start = 12.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                                item(key = "bottom-space") { Spacer(Modifier.height(72.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoHeader(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onToggle) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起对话" else "展开对话"
            )
        }
    }
}

private data class RepoSection(
    val key: String,
    val title: String,
    val chats: List<AgentDetail>,
    val totalCount: Int,
    val activeCount: Int
) {
    fun subtitle(): String {
        val running = if (activeCount > 0) " · $activeCount 进行中" else ""
        return "$totalCount 个对话$running"
    }
}

private fun AgentDetail.repoKey(): String = repos.firstOrNull()?.url.orEmpty()

private fun AgentDetail.toSummary() = AgentSummary(
    id = id,
    name = name,
    status = status,
    url = url,
    createdAt = createdAt,
    updatedAt = updatedAt,
    latestRunId = latestRunId
)

private fun AgentDetail.matches(filter: String, query: String): Boolean {
    val status = status?.uppercase().orEmpty()
    val matchesFilter = when (filter) {
        "active" -> status in setOf("ACTIVE", "RUNNING", "CREATING")
        "archived" -> status == "ARCHIVED"
        else -> status != "ARCHIVED"
    }
    val q = query.trim()
    return matchesFilter && (
        q.isBlank() ||
            name.orEmpty().contains(q, true) ||
            id.contains(q, true) ||
            repoTitle(repoKey()).contains(q, true)
        )
}

private fun repoTitle(url: String): String {
    if (url.isBlank()) return "不绑定仓库"
    return url.removePrefix("https://").removePrefix("http://").removePrefix("github.com/")
}

private fun buildRepoSections(
    repositories: List<RepositoryItem>,
    details: List<AgentDetail>,
    filter: String,
    query: String
): List<RepoSection> {
    val q = query.trim()
    val keys = (repositories.map { it.url } + details.map { it.repoKey() }).distinct()
    return keys.mapNotNull { key ->
        val shown = details.filter { it.repoKey() == key && it.matches(filter, query) }
        val title = repoTitle(key)
        val titleMatches = q.isBlank() || title.contains(q, true)
        if (shown.isEmpty() && !(filter == "all" && titleMatches)) return@mapNotNull null
        val active = shown.count { it.status?.uppercase() in setOf("ACTIVE", "RUNNING", "CREATING") }
        RepoSection(key, title, shown, shown.size, active)
    }.sortedWith(compareByDescending<RepoSection> { it.activeCount }.thenBy { it.title })
}
