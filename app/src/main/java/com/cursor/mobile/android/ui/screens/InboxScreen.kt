package com.cursor.mobile.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cursor.mobile.android.data.AgentSession
import com.cursor.mobile.android.data.AgentStore
import com.cursor.mobile.android.data.CursorApi
import com.cursor.mobile.android.data.MachineKind
import com.cursor.mobile.android.data.WorkScope
import com.cursor.mobile.android.ui.components.EmptyState
import com.cursor.mobile.android.ui.components.Kicker
import com.cursor.mobile.android.ui.components.MessageBody
import com.cursor.mobile.android.ui.components.StatusChip
import com.cursor.mobile.android.ui.theme.CursorPalette
import com.cursor.mobile.android.ui.theme.accentGradient
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onOpenDrawer: () -> Unit,
    onOpenChat: (String) -> Unit,
    onNewAgent: () -> Unit,
    onSessionExpired: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessions = AgentStore.sessions
    val loading = AgentStore.loading
    val error = AgentStore.error
    fun reload() {
        scope.launch {
            try {
                AgentStore.refresh(context)
            } catch (e: CursorApi.Unauthorized) {
                onSessionExpired(e.message ?: "登录已失效")
            }
        }
    }
    LaunchedEffect(Unit) { reload() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("收件箱", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onOpenDrawer) { Icon(Icons.Filled.Menu, contentDescription = "侧边栏") } },
                actions = {
                    IconButton(onClick = { reload() }, enabled = !loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "同步")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewAgent,
                containerColor = CursorPalette.BrandBlue,
                contentColor = Color.White
            ) { Icon(Icons.Filled.Add, contentDescription = "发起 Agent") }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        val scope = AgentStore.scope
        val visible = sessions.filter { it.scope == scope }
        val pending = sessions.count { !it.classified }
        val groups = visible.groupBy { it.groupLabel.ifBlank { if (scope == WorkScope.PROJECT) "未命名项目" else "未命名仓库" } }
        Column(Modifier.fillMaxSize().padding(padding)) {
        ScopeSwitch(scope, sessions.count { it.scope == WorkScope.PROJECT }, sessions.count { it.scope == WorkScope.REPOSITORY }) {
            AgentStore.choose(it)
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFFE3EBFF), Color(0xFFF1E8FF), Color(0xFFE0F7FD))
                            )
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Kicker("Cloud Agents")
                        Text("从口袋里指挥全部 Agent", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (scope == WorkScope.PROJECT) "Projects · ${visible.size} 个项目会话"
                            else "Repositories · ${visible.size} 个仓库会话",
                            style = MaterialTheme.typography.bodySmall,
                            color = CursorPalette.LightMuted
                        )
                    }
                }
            }
            if (loading && sessions.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            if (!error.isNullOrBlank()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { reload() }) { Text("重试同步") }
                    }
                }
            }
            if (!loading && visible.isEmpty() && pending > 0) {
                item { EmptyState("正在区分项目和仓库", "正在拉会话详情里的 project 字段和 repos.url。") }
            }
            if (!loading && visible.isEmpty() && pending == 0 && error.isNullOrBlank()) {
                item {
                    if (scope == WorkScope.PROJECT) {
                        EmptyState("还没有项目会话", "只收录带 project.id、projectId 或 coordinator 关联的会话。没有仓库不会被当成项目。")
                    } else {
                        EmptyState("还没有仓库会话", "带 repos.url 或 source.repository 的会话会归在这里。")
                    }
                }
            }
            for ((label, grouped) in groups) {
                item(key = "group-${scope.name}-$label") {
                    Text(label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                }
                items(grouped, key = { it.id }) { s ->
                    SessionCard(s) { onOpenChat(s.id) }
                }
            }
            item { Box(Modifier.size(72.dp)) }
        }
        }
    }
}

@Composable
private fun ScopeSwitch(selected: WorkScope, projects: Int, repositories: Int, onSelect: (WorkScope) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ScopeChip("Projects", projects, selected == WorkScope.PROJECT, Modifier.weight(1f)) { onSelect(WorkScope.PROJECT) }
        ScopeChip("Repositories", repositories, selected == WorkScope.REPOSITORY, Modifier.weight(1f)) { onSelect(WorkScope.REPOSITORY) }
    }
}

@Composable
private fun ScopeChip(label: String, count: Int, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
            .border(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            color = if (active) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SessionCard(s: AgentSession, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val isPressed by press.collectIsPressedAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = if (isPressed) 0.985f else 1f; scaleY = if (isPressed) 0.985f else 1f }
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            .clickable(interactionSource = press, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                MessageBody(
                    s.title,
                    MaterialTheme.colorScheme.onSurface,
                    MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                )
            }
            StatusChip(s.status)
        }
        if (s.summary.isNotBlank() && s.summary != s.title) {
            MessageBody(s.summary, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.typography.bodySmall)
        }
        Text(
            when (s.scope) {
                WorkScope.PROJECT -> s.groupLabel.ifBlank { "Project" }
                WorkScope.REPOSITORY -> listOf(s.repo.ifBlank { s.groupLabel }, s.branch.ifBlank { "分支同步中" }).joinToString(" · ")
                null -> "正在识别分类"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                listOf(s.model, machineLabel(s), s.updatedAt).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (s.unread > 0) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(accentGradient())
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                ) {
                    Text("${s.unread} 条新消息", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun machineLabel(s: AgentSession) = when (s.machine) {
    MachineKind.CLOUD -> "Cloud"
    MachineKind.TEAM_POOL -> "Team Pool"
    MachineKind.MY_MACHINE -> "My Machine"
}
