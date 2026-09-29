package com.cursor.mobile.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cursor.mobile.android.data.FakeRepository

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
                title = { Text("发起 Agent") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("选仓库 → 选分支/机器/模型 → 描述任务（对标 iOS 发起页）", style = MaterialTheme.typography.bodySmall)
            Text("仓库", fontWeight = FontWeight.SemiBold)
            AssistChip(onClick = { repoMenu = true }, label = { Text(repo) })
            DropdownMenu(expanded = repoMenu, onDismissRequest = { repoMenu = false }) {
                FakeRepository.repos.forEach { DropdownMenuItem(text = { Text("${it.name} · ${it.branch}") }, onClick = { repo = it.name; repoMenu = false }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { modelMenu = true }, label = { Text(model) })
                AssistChip(onClick = {}, label = { Text(machine) })
            }
            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                FakeRepository.models.forEach { DropdownMenuItem(text = { Text(it) }, onClick = { model = it; modelMenu = false }) }
            }
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                minLines = 5,
                placeholder = { Text("例如：把登录闪退修了并补单测，跑通后贴录屏…") },
                label = { Text("任务描述（支持语音/图片附件占位）") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { prompt += "/review " }, label = { Text("/review") })
                AssistChip(onClick = { prompt += "/fix-ci " }, label = { Text("/fix-ci") })
                AssistChip(onClick = {}, label = { Text("📷 截图批注") })
                AssistChip(onClick = {}, label = { Text("🎙 语音") })
            }
            Button(
                onClick = {
                    val id = "a${FakeRepository.sessions.size + 1}"
                    FakeRepository.sessions.add(
                        com.cursor.mobile.android.data.AgentSession(
                            id, prompt.ifBlank { "新任务 ${id}" }.take(24), repo, "agent/$id",
                            com.cursor.mobile.android.data.AgentStatus.WORKING, model,
                            com.cursor.mobile.android.data.MachineKind.CLOUD, "刚刚"
                        )
                    )
                    onLaunched(id)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("启动云端 Agent（Demo 本地建单）") }
        }
    }
}
