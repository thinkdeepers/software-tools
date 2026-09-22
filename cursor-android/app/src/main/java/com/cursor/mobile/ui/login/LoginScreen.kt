package com.cursor.mobile.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cursor.mobile.data.repository.CursorRepository
import com.cursor.mobile.ui.components.ErrorBanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val apiKey: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val accountLabel: String? = null
)

class LoginViewModel(
    private val repository: CursorRepository
) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onApiKeyChange(value: String) {
        _state.update { it.copy(apiKey = value, error = null) }
    }

    fun login(onSuccess: () -> Unit) {
        val key = _state.value.apiKey.trim()
        if (key.isEmpty()) {
            _state.update { it.copy(error = "请输入 API Key") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { repository.validateAndLogin(key) }
                .onSuccess { me ->
                    val label = listOfNotNull(
                        me.userEmail,
                        me.apiKeyName,
                        me.userFirstName
                    ).firstOrNull()
                    _state.update {
                        it.copy(loading = false, accountLabel = label)
                    }
                    onSuccess()
                }
                .onFailure { t ->
                    _state.update {
                        it.copy(loading = false, error = repository.mapError(t))
                    }
                }
        }
    }

    companion object {
        fun factory(repository: CursorRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return LoginViewModel(repository) as T
            }
        }
    }
}

@Composable
fun LoginScreen(
    repository: CursorRepository,
    onLoggedIn: () -> Unit,
    viewModel: LoginViewModel = viewModel(factory = LoginViewModel.factory(repository))
) {
    val state by viewModel.state.collectAsState()
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "云程",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "使用 Cloud Agents API Key 登录，在手机上完成仓库级 AI 编程对话。",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = viewModel::onApiKeyChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API Key") },
            placeholder = { Text("key_…") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Spacer(Modifier.height(12.dp))
        state.error?.let { ErrorBanner(it) }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { viewModel.login(onLoggedIn) },
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.loading) "登录中…" else "登录")
        }
        TextButton(
            onClick = { uriHandler.openUri("https://cursor.com/dashboard/api") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("打开 Dashboard 创建 API Key")
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "说明：官方 Cloud Agents API 使用 Dashboard 中的用户 API Key 鉴权（Bearer）。登录后可选择仓库与模型，创建 Agent、流式对话、跟进指令、取消任务与归档。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
