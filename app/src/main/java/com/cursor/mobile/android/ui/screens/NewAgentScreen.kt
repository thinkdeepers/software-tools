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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.window.Dialog
import com.cursor.mobile.android.data.AgentRequest
import com.cursor.mobile.android.data.AgentStore
import com.cursor.mobile.android.data.CursorApi
import com.cursor.mobile.android.data.MachineKind
import com.cursor.mobile.android.data.ModelTier
import com.cursor.mobile.android.data.PrefsRepository
import com.cursor.mobile.android.data.ProjectRef
import com.cursor.mobile.android.data.RepoRef
import com.cursor.mobile.android.data.WorkScope
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
    var taskScope by remember { mutableStateOf(AgentStore.scope) }
    var selectedProject by remember { mutableStateOf<ProjectRef?>(null) }
    var selectedRepo by remember { mutableStateOf<RepoRef?>(null) }
    var branch by remember { mutableStateOf("main") }
    var targetOpen by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var launching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val repos = AgentStore.repos
    val projects = AgentStore.projects
    val catalog = AgentStore.catalog
    LaunchedEffect(Unit) {
        try {
            AgentStore.ensureModels(context)
            AgentStore.ensureRepos(context)
            AgentStore.ensureProjects(context)
        } catch (e: CursorApi.Unauthorized) {
            onSessionExpired(e.message ?: "登录已失效")
        } catch (_: Exception) {
            // 目录失败时仍显示会话里已经同步到的项目或仓库
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
            Kicker("New agent · 先选分类，再选目标")
            Text("描述任务，Agent 在云端开工", style = MaterialTheme.typography.titleLarge)
            Text("Projects 写入 env.name，Repositories 写入 repos.url。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text("分类", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                CategoryChip("Projects", taskScope == WorkScope.PROJECT, Modifier.weight(1f)) { taskScope = WorkScope.PROJECT }
                CategoryChip("Repositories", taskScope == WorkScope.REPOSITORY, Modifier.weight(1f)) { taskScope = WorkScope.REPOSITORY }
            }
            Text(
                if (taskScope == WorkScope.PROJECT) "Project" else "Repository",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium
            )
            val targetLabel = if (taskScope == WorkScope.PROJECT) {
                selectedProject?.name ?: "选择 Project"
            } else {
                selectedRepo?.name ?: "选择 Repository"
            }
            val choices = if (taskScope == WorkScope.PROJECT) projects.map { it.name } else repos.map { it.name }
            PickerRow(targetLabel, if (taskScope == WorkScope.REPOSITORY) selectedRepo?.branch else null) { targetOpen = true }
            if (choices.isEmpty()) {
                Text(
                    if (taskScope == WorkScope.PROJECT) "账号里还没有项目。项目来自 GET /v1/projects、/v1/environments，或会话上的 env.name。"
                    else "账号里还没有仓库。仓库来自 GET /v1/repositories（失败时用 /v0/repositories）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (targetOpen) {
                TargetDialog(
                    title = if (taskScope == WorkScope.PROJECT) "选择 Project" else "选择 Repository",
                    options = choices,
                    selected = targetLabel,
                    onDismiss = { targetOpen = false },
                    onSelect = { name ->
                        if (taskScope == WorkScope.PROJECT) {
                            selectedProject = projects.firstOrNull { it.name == name } ?: ProjectRef(name)
                        } else {
                            val repo = repos.firstOrNull { it.name == name }
                            selectedRepo = repo
                            branch = repo?.branch?.ifBlank { "main" } ?: "main"
                        }
                        targetOpen = false
                    }
                )
            }
            if (taskScope == WorkScope.REPOSITORY) {
                OutlinedTextField(
                    value = branch,
                    onValueChange = { branch = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    label = { Text("起始分支 startingRef") },
                    colors = fieldColors()
                )
            }
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
                    if (taskScope == WorkScope.PROJECT && selectedProject == null) {
                        error = "请先选择一个 Project"
                        return@Button
                    }
                    if (taskScope == WorkScope.REPOSITORY && selectedRepo == null) {
                        error = "请先选择一个 Repository"
                        return@Button
                    }
                    launching = true
                    error = null
                    val request = if (taskScope == WorkScope.PROJECT) {
                        AgentRequest(
                            repo = "",
                            branch = "",
                            prompt = prompt.trim(),
                            model = savedModel,
                            tier = savedTier,
                            machine = MachineKind.CLOUD,
                            scope = WorkScope.PROJECT,
                            projectName = selectedProject?.name.orEmpty()
                        )
                    } else {
                        val repo = selectedRepo
                        AgentRequest(
                            repo = repo?.url?.ifBlank { repo.name }.orEmpty(),
                            branch = branch.ifBlank { repo?.branch?.ifBlank { "main" } ?: "main" },
                            prompt = prompt.trim(),
                            model = savedModel,
                            tier = savedTier,
                            machine = MachineKind.CLOUD,
                            scope = WorkScope.REPOSITORY
                        )
                    }
                    scope.launch {
                        try {
                            val id = AgentStore.create(context, request)
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
            Text("高速 / 均衡 / 最强会写进 model.id 和 model.params。分类只带当前选中的项目或仓库。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun CategoryChip(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Text(
        label,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
            .border(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    )
}

@Composable
private fun TargetDialog(
    title: String,
    options: List<String>,
    selected: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            if (options.isEmpty()) {
                Text(
                    "这个分类还是空的。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            options.forEach { name ->
                val active = name == selected
                Text(
                    if (active) "✓  $name" else name,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(name) }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                )
            }
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
