package com.cursor.mobile.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.collectAsState
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
import com.cursor.mobile.android.data.PrefsRepository
import com.cursor.mobile.android.ui.components.EmptyState
import com.cursor.mobile.android.ui.components.Kicker
import com.cursor.mobile.android.ui.theme.CursorPalette
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
        val pins by PrefsRepository.pinsFlow(context).collectAsState(initial = emptySet())
        val rows = sessions.sortedWith(
            compareByDescending<AgentSession> { pins.contains(it.id) }.thenByDescending { it.updatedAtIso }
        )
        Column(Modifier.fillMaxSize().padding(padding)) {
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
                        Text(
                            "会话 · ${sessions.size}",
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
            if (!loading && sessions.isEmpty() && error.isNullOrBlank()) {
                item { EmptyState("还没有会话", "同步完成后会列在这里。") }
            }
            items(rows, key = { it.id.ifBlank { it.title } }) { s ->
                SessionCard(
                    s,
                    repoShort(s),
                    pinned = pins.contains(s.id),
                    onClick = { onOpenChat(s.id) },
                    onLongClick = { scope.launch { PrefsRepository.togglePin(context, s.id) } }
                )
            }
            item { Box(Modifier.size(72.dp)) }
        }
        }
    }
}

private fun repoShort(s: AgentSession): String {
    ownerRepo(s.repoUrl)?.let { return it }
    ownerRepo(s.repo)?.let { return it }
    return "未归类"
}

private fun ownerRepo(raw: String): String? {
    val text = raw.trim().removeSuffix(".git")
    if (text.isBlank() || text.length > 160 || text.contains('{') || text.contains('[')) return null
    val hosted = Regex("(?:github\\.com|gitlab\\.com)[:/]([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)").find(text)
    if (hosted != null) return hosted.groupValues[1]
    if (text.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) return text
    return null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    s: AgentSession,
    repo: String,
    pinned: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val press = remember { MutableInteractionSource() }
    val isPressed by press.collectIsPressedAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = if (isPressed) 0.985f else 1f; scaleY = if (isPressed) 0.985f else 1f }
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (pinned) 0.9f else 1f), RoundedCornerShape(20.dp))
            .combinedClickable(interactionSource = press, indication = null, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                s.title.ifBlank { "未命名会话" },
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge
            )
            if (pinned) {
                Text(
                    "置顶",
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        Text(
            repo,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
