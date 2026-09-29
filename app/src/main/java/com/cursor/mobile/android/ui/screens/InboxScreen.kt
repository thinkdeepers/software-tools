package com.cursor.mobile.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cursor.mobile.android.data.AgentSession
import com.cursor.mobile.android.ui.components.StatusChip

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
                navigationIcon = { IconButton(onClick = onOpenDrawer) { Icon(Icons.Filled.Menu, contentDescription = "侧边栏") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewAgent) { Icon(Icons.Filled.Add, contentDescription = "发起 Agent") }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Text("对标 iOS：Inbox 聚合全部云端/本地 Agent，可跟踪最多 8 个进行中任务。", style = MaterialTheme.typography.bodySmall) }
            items(sessions, key = { it.id }) { s ->
                Card(modifier = Modifier.fillMaxWidth().clickable { onOpenChat(s.id) }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            BadgedBox(badge = { if (s.unread > 0) Badge { Text("${s.unread}") } }) {
                                Text(s.title, fontWeight = FontWeight.SemiBold)
                            }
                            StatusChip(s.status)
                        }
                        Text("${s.repo} · ${s.branch}", style = MaterialTheme.typography.bodySmall)
                        Text("${s.model} · ${machineLabel(s)} · ${s.updatedAt}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private fun machineLabel(s: AgentSession) = when (s.machine) {
    com.cursor.mobile.android.data.MachineKind.CLOUD -> "Cloud machine"
    com.cursor.mobile.android.data.MachineKind.TEAM_POOL -> "Team Pool"
    com.cursor.mobile.android.data.MachineKind.MY_MACHINE -> "My Machine(Remote Control)"
}
