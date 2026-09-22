package com.cursor.mobile.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cursor.mobile.data.github.GithubClient
import com.cursor.mobile.data.github.PullReview
import com.cursor.mobile.ui.components.ErrorBanner
import com.cursor.mobile.ui.components.LoadingBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReviewUiState(
    val loading: Boolean = true,
    val review: PullReview? = null,
    val error: String? = null,
    val info: String? = null,
    val comment: String = "",
    val busy: Boolean = false
)

class ReviewViewModel(
    private val prUrl: String,
    private val github: GithubClient
) : ViewModel() {
    private val _state = MutableStateFlow(ReviewUiState())
    val state = _state.asStateFlow()

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { withContext(Dispatchers.IO) { github.load(prUrl) } }
                .onSuccess { review -> _state.update { it.copy(loading = false, review = review) } }
                .onFailure { t -> _state.update { it.copy(loading = false, error = t.message ?: "读取 PR 失败") } }
        }
    }

    fun onComment(value: String) = _state.update { it.copy(comment = value) }

    fun merge() = act("已 squash 合并") { github.mergeSquash(prUrl) }
    fun close() = act("已关闭") { github.close(prUrl) }
    fun sendComment() {
        val body = _state.value.comment.trim()
        if (body.isEmpty()) return
        act("评论已发送") { github.comment(prUrl, body) }
    }

    private fun act(success: String, block: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess {
                    _state.update { it.copy(busy = false, info = success, comment = "") }
                    reload()
                }
                .onFailure { t -> _state.update { it.copy(busy = false, error = t.message) } }
        }
    }

    companion object {
        fun factory(prUrl: String, github: GithubClient) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ReviewViewModel(prUrl, github) as T
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    prUrl: String,
    github: GithubClient,
    onBack: () -> Unit,
    onAskAgent: (String) -> Unit,
    viewModel: ReviewViewModel = viewModel(factory = ReviewViewModel.factory(prUrl, github))
) {
    val state by viewModel.state.collectAsState()
    var confirmMerge by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("评审") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.loading && state.review == null -> LoadingBlock("读取 Pull Request…")
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                state.error?.let { ErrorBanner(it) }
                state.info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                val pull = state.review?.pull
                if (pull != null) {
                    Text(pull.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "${pull.state}${if (pull.draft) " · draft" else ""} · ${pull.head?.ref ?: ""} → ${pull.base?.ref ?: ""}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("+${pull.additions}  -${pull.deletions}  · ${pull.changedFiles} 个文件 · ${pull.commits} 个提交")
                    pull.body?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { confirmMerge = true }, enabled = !state.busy) { Text("Squash 合并") }
                        OutlinedButton(onClick = viewModel::close, enabled = !state.busy) { Text("关闭") }
                        OutlinedButton(
                            onClick = { onAskAgent("请阅读这个 PR 的审查意见和失败检查，逐条修复后说明改动。\n$prUrl") },
                            enabled = !state.busy
                        ) { Text("让云程改") }
                    }
                }
                state.review?.status?.let { status ->
                    Text("检查：${status.state ?: "未知"}", fontWeight = FontWeight.SemiBold)
                    status.statuses.forEach { item ->
                        Text("${item.state}  ${item.context}\n${item.description.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("提交", fontWeight = FontWeight.SemiBold)
                state.review?.commits?.forEach { commit ->
                    Text(commit.commit.message.lineSequence().first(), fontWeight = FontWeight.Medium)
                    Text(commit.sha.take(8), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("文件", fontWeight = FontWeight.SemiBold)
                state.review?.files?.forEach { file ->
                    Text("${file.status ?: "modified"}  ${file.filename}  +${file.additions} -${file.deletions}")
                    file.patch?.take(1800)?.let {
                        Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("评论", fontWeight = FontWeight.SemiBold)
                state.review?.comments?.forEach { comment ->
                    Text("${comment.user?.login ?: "用户"}：${comment.body}")
                }
                OutlinedTextField(
                    value = state.comment,
                    onValueChange = viewModel::onComment,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("写评论") },
                    minLines = 2
                )
                Button(onClick = viewModel::sendComment, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("发送评论")
                }
                Text(
                    "私有仓库需要在设置里保存 GitHub Token。合并、关闭和评论使用这个 Token，不会上传到云程以外的服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (confirmMerge) {
        AlertDialog(
            onDismissRequest = { confirmMerge = false },
            title = { Text("Squash 合并这个 PR？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmMerge = false
                    viewModel.merge()
                }) { Text("合并") }
            },
            dismissButton = { TextButton(onClick = { confirmMerge = false }) { Text("取消") } }
        )
    }
}
