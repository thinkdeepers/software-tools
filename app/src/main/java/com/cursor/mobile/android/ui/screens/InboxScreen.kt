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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cursor.mobile.android.data.AgentSession
import com.cursor.mobile.android.data.MachineKind
import com.cursor.mobile.android.ui.components.Kicker
import com.cursor.mobile.android.ui.components.StatusChip
import com.cursor.mobile.android.ui.theme.CursorPalette
import com.cursor.mobile.android.ui.theme.accentGradient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    sessions: List<AgentSession>,
    onOpenDrawer: () -> Unit,
    onOpenChat: (String) -> Unit,
    onNewAgent: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("收件箱", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onOpenDrawer) { Icon(Icons.Filled.Menu, contentDescription = "侧边栏") } },
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
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
                        Kicker("Cloud agents · 与 iOS 同后端")
                        Text("从口袋里指挥全部 Agent", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${sessions.count { it.status.name == "WORKING" }} 个运行中 · 锁屏最多跟 8 个 · 完成即推送",
                            style = MaterialTheme.typography.bodySmall,
                            color = CursorPalette.LightMuted
                        )
                    }
                }
            }
            items(sessions, key = { it.id }) { s ->
                SessionCard(s) { onOpenChat(s.id) }
            }
            item { Box(Modifier.size(72.dp)) }
        }
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
            Text(s.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(end = 8.dp))
            StatusChip(s.status)
        }
        Text("${s.repo} · ${s.branch}", fontFamily = null, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${s.model} · ${machineLabel(s)} · ${s.updatedAt}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
