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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cursor.mobile.android.data.AgentRequest
import com.cursor.mobile.android.data.AgentStore
import com.cursor.mobile.android.data.CursorApi
import com.cursor.mobile.android.data.MachineKind
import com.cursor.mobile.android.data.ModelTier
import com.cursor.mobile.android.data.PrefsRepository
import com.cursor.mobile.android.data.modelChoices
import com.cursor.mobile.android.ui.components.Kicker
import com.cursor.mobile.android.ui.components.ModelPicker
import com.cursor.mobile.android.ui.components.ModelTierPicker
import com.cursor.mobile.android.ui.theme.CursorPalette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewAgentScreen(onBack: () -> Unit, onLaunched: (String) -> Unit, onSessionExpired: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val persistedTier by PrefsRepository.tierFlow(context).collectAsState(ModelTier.BALANCED)
    val persistedModel by PrefsRepository.modelFlow(context).collectAsState(ModelTier.BALANCED.model)
    var picked by remember { mutableStateOf(false) }
    var savedTier by remember { mutableStateOf(ModelTier.BALANCED) }
    var savedModel by remember { mutableStateOf(ModelTier.BALANCED.model) }
    LaunchedEffect(persistedTier, persistedModel) {
        if (!picked) {
            savedTier = persistedTier
            savedModel = persistedModel
        }
    }
    var repo by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    var repoMenu by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var launching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val repos = AgentStore.repos
    val catalog = AgentStore.catalog
    LaunchedEffect(Unit) {
        try {
            AgentStore.ensureModels(context)
            AgentStore.ensureRepos(context)
            if (repo.isBlank()) repo = AgentStore.repos.firstOrNull()?.name.orEmpty()
        } catch (e: CursorApi.Unauthorized) {
            onSessionExpired(e.message ?: "登录已失效")
        } catch (_: Exception) {
            // 仓库列表失败时仍可手填 owner/repo
        }
    }

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
            Text("会调用 POST /v1/agents，模型强度写入 model.params。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text("仓库", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Box {
                PickerRow(repo.ifBlank { "选择或填写仓库" }, branch) { if (repos.isNotEmpty()) repoMenu = true }
                DropdownMenu(expanded = repoMenu, onDismissRequest = { repoMenu = false }) {
                    repos.forEach {
                        DropdownMenuItem(
                            text = { Text("${it.name} · ${it.branch}") },
                            onClick = { repo = it.name; branch = it.branch; repoMenu = false }
                        )
                    }
                }
            }
            OutlinedTextField(
                value = repo,
                onValueChange = { repo = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                label = { Text("owner/repo 或 GitHub URL") },
                colors = fieldColors()
            )
            OutlinedTextField(
                value = branch,
                onValueChange = { branch = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                label = { Text("起始分支") },
                colors = fieldColors()
            )
            Text("模型 · 强度", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            ModelTierPicker(savedTier, savedModel) { tier ->
                picked = true
                savedTier = tier
                scope.launch { PrefsRepository.saveSelection(context, tier, savedModel) }
            }
            ModelPicker(savedModel, modelChoices(catalog.toList())) { model ->
                picked = true
                savedModel = model.id
                scope.launch { PrefsRepository.saveSelection(context, savedTier, model.id) }
            }
            PickerRow("Cloud", null) {}

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
                shape = RoundedCornerShape(18.dp),
                placeholder = { Text("例如：把登录闪退修了并补单测，跑通后贴录屏…") },
                label = { Text("任务描述") },
                colors = fieldColors()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickChip("/review") { prompt += "/review " }
                QuickChip("/fix-ci") { prompt += "/fix-ci " }
                QuickChip("截图批注") {}
                QuickChip("语音输入") {}
            }
            if (!error.isNullOrBlank()) {
                Text(error ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = {
                    if (prompt.isBlank()) {
                        error = "请填写任务描述"
                        return@Button
                    }
                    if (repo.isBlank()) {
                        error = "请填写仓库，例如 owner/repo"
                        return@Button
                    }
                    launching = true
                    error = null
                    scope.launch {
                        try {
                            val id = AgentStore.create(
                                context,
                                AgentRequest(
                                    repo = repo,
                                    branch = branch.ifBlank { "main" },
                                    prompt = prompt.trim(),
                                    model = savedModel,
                                    tier = savedTier,
                                    machine = MachineKind.CLOUD
                                )
                            )
                            onLaunched(id)
                        } catch (e: CursorApi.Unauthorized) {
                            onSessionExpired(e.message ?: "登录已失效")
                        } catch (e: Exception) {
                            error = e.message ?: "创建失败"
                        } finally {
                            launching = false
                        }
                    }
                },
                enabled = !launching,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CursorPalette.BrandBlue, contentColor = Color.White)
            ) {
                if (launching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                else Text("启动云端 Agent", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp))
            }
            Text("高速 / 均衡 / 最强会写进 model.id 和 model.params。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface
)

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
