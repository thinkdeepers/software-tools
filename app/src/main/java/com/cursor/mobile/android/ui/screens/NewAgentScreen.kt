package com.cursor.mobile.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cursor.mobile.android.data.AgentSession
import com.cursor.mobile.android.data.AgentStatus
import com.cursor.mobile.android.data.FakeRepository
import com.cursor.mobile.android.data.MachineKind
import com.cursor.mobile.android.ui.components.Kicker
import com.cursor.mobile.android.ui.theme.CursorPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewAgentScreen(onBack: () -> Unit, onLaunched: (String) -> Unit) {
    var repo by remember { mutableStateOf(FakeRepository.repos.first().name) }
    var repoMenu by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(FakeRepository.models.first()) }
    var modelMenu by remember { mutableStateOf(false) }
    var machine by remember { mutableStateOf("Cloud machine") }
    var prompt by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("发起 Agent", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Kicker("New agent · 选仓库 / 模型 / 机器")
            Text("描述任务，Agent 在云端开工", style = MaterialTheme.typography.titleLarge)
            Text("对标 iOS 发起页：机器、模型、指令、附件、语音全对齐（占位）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text("仓库", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Box {
                PickerRow(repo, "main") { repoMenu = true }
                DropdownMenu(expanded = repoMenu, onDismissRequest = { repoMenu = false }) {
                    FakeRepository.repos.forEach { DropdownMenuItem(text = { Text("${it.name} · ${it.branch}") }, onClick = { repo = it.name; repoMenu = false }) }
                }
            }
            Text("模型 · 机器", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    PickerRow(model, null) { modelMenu = true }
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        FakeRepository.models.forEach { DropdownMenuItem(text = { Text(it) }, onClick = { model = it; modelMenu = false }) }
                    }
                }
                PickerRow(machine, null) {}
            }

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
                shape = RoundedCornerShape(18.dp),
                placeholder = { Text("例如：把登录闪退修了并补单测，跑通后贴录屏…") },
                label = { Text("任务描述") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickChip("/review") { prompt += "/review " }
                QuickChip("/fix-ci") { prompt += "/fix-ci " }
                QuickChip("截图批注") {}
                QuickChip("语音输入") {}
            }
            Button(
                onClick = {
                    val id = "a${FakeRepository.sessions.size + 1}"
                    FakeRepository.sessions.add(
                        AgentSession(
                            id, prompt.ifBlank { "新任务 $id" }.take(24), repo, "agent/$id",
                            AgentStatus.WORKING, model, MachineKind.CLOUD, "刚刚"
                        )
                    )
                    onLaunched(id)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CursorPalette.BrandBlue, contentColor = Color.White)
            ) { Text("启动云端 Agent", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp)) }
            Text("Demo 为本地建单，接后端后走真实建单 + 推送。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PickerRow(main: String, sub: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(main, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("▾", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QuickChip(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}
