package com.cursor.mobile.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.cursor.mobile.data.local.SessionStore
import com.cursor.mobile.data.model.MeResponse
import com.cursor.mobile.data.repository.CursorRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: CursorRepository,
    sessionStore: SessionStore,
    onBack: () -> Unit,
    onLoggedOut: () -> Unit
) {
    var me by remember { mutableStateOf<MeResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var githubToken by remember { mutableStateOf(sessionStore.getGithubToken().orEmpty()) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(Unit) {
        runCatching { repository.me() }
            .onSuccess { me = it }
            .onFailure { error = repository.mapError(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text("账号", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(me?.userEmail ?: me?.apiKeyName ?: "已登录")
            me?.userFirstName?.let {
                Text(
                    text = listOfNotNull(me?.userFirstName, me?.userLastName).joinToString(" "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = { uriHandler.openUri("https://cursor.com/agents") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("打开 Cursor Web Agents")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { uriHandler.openUri("https://cursor.com/dashboard/api") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("管理 API Keys")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { uriHandler.openUri("https://cursor.com/docs/cloud-agent/api/endpoints") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cloud Agents API 文档")
            }
            Spacer(Modifier.height(24.dp))
            Text("GitHub 评审", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedTextField(
                value = githubToken,
                onValueChange = { githubToken = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("GitHub Token（可选）") },
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { sessionStore.setGithubToken(githubToken) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存 Token") }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "用于读取 diff、检查、评论和 squash 合并。电脑上的 Remote Control 需要在 Cursor 桌面端执行 /remote-control，会话会出现在收件箱。密钥、环境和 MCP 目录仍在网页配置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    repository.clearSession()
                    onLoggedOut()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("退出登录")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "本应用通过官方 Cloud Agents API 实现手机端 AI 云仓库编程对话：" +
                    "模型选择、仓库绑定、创建/跟进 Agent、SSE 流式输出、取消运行与归档。" +
                    "不包含本地编辑器、Tab 补全与代码浏览。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
