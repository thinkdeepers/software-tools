package com.cursor.mobile.data.repository

import com.cursor.mobile.data.api.CursorApi
import com.cursor.mobile.data.api.RunStreamClient
import com.cursor.mobile.data.api.StreamEvent
import com.cursor.mobile.data.api.toUserMessage
import com.cursor.mobile.data.local.SessionStore
import com.cursor.mobile.data.model.AgentDetail
import com.cursor.mobile.data.model.AgentSummary
import com.cursor.mobile.data.model.ChatItem
import com.cursor.mobile.data.model.ConversationMessage
import com.cursor.mobile.data.model.CreateAgentRequest
import com.cursor.mobile.data.model.CreateRunRequest
import com.cursor.mobile.data.model.MeResponse
import com.cursor.mobile.data.model.ModelInfo
import com.cursor.mobile.data.model.ModelSelection
import com.cursor.mobile.data.model.PromptPayload
import com.cursor.mobile.data.model.RepoConfig
import com.cursor.mobile.data.model.RepositoryItem
import com.cursor.mobile.data.model.RunStatus
import com.cursor.mobile.data.model.RunSummary
import kotlinx.coroutines.flow.Flow

class CursorRepository(
    private val api: CursorApi,
    private val streamClient: RunStreamClient,
    private val sessionStore: SessionStore
) {
    fun hasSession(): Boolean = !sessionStore.getApiKey().isNullOrBlank()

    fun saveApiKey(apiKey: String) = sessionStore.setApiKey(apiKey)

    fun clearSession() = sessionStore.clearApiKey()

    suspend fun validateAndLogin(apiKey: String): MeResponse {
        sessionStore.setApiKey(apiKey)
        return try {
            api.me()
        } catch (t: Throwable) {
            sessionStore.clearApiKey()
            throw t
        }
    }

    suspend fun me(): MeResponse = api.me()

    suspend fun listModels(): List<ModelInfo> = api.listModels().items

    suspend fun listRepositories(): List<RepositoryItem> = api.listRepositories().items

    suspend fun listAgents(includeArchived: Boolean = false): List<AgentSummary> =
        api.listAgents(includeArchived = includeArchived).items

    suspend fun getAgent(id: String): AgentDetail = api.getAgent(id)

    suspend fun createAgent(
        prompt: String,
        repoUrl: String?,
        startingRef: String?,
        model: ModelSelection?,
        mode: String?,
        autoCreatePR: Boolean,
        name: String?
    ): Pair<AgentDetail, RunSummary> {
        val repos = repoUrl?.takeIf { it.isNotBlank() }?.let {
            listOf(
                RepoConfig(
                    url = it.trim(),
                    startingRef = startingRef?.takeIf { ref -> ref.isNotBlank() }
                )
            )
        }
        val response = api.createAgent(
            CreateAgentRequest(
                prompt = PromptPayload(text = prompt.trim()),
                model = model,
                name = name?.takeIf { it.isNotBlank() },
                repos = repos,
                autoCreatePR = autoCreatePR,
                mode = mode
            )
        )
        return response.agent to response.run
    }

    suspend fun createFollowUp(
        agentId: String,
        prompt: String,
        mode: String? = null
    ): RunSummary = api.createRun(
        agentId,
        CreateRunRequest(
            prompt = PromptPayload(text = prompt.trim()),
            mode = mode
        )
    ).run

    suspend fun getRun(agentId: String, runId: String): RunSummary = api.getRun(agentId, runId)

    suspend fun listRuns(agentId: String): List<RunSummary> = api.listRuns(agentId).items

    suspend fun cancelRun(agentId: String, runId: String) = api.cancelRun(agentId, runId)

    suspend fun archiveAgent(agentId: String) = api.archiveAgent(agentId)

    suspend fun deleteAgent(agentId: String) = api.deleteAgent(agentId)

    fun streamRun(agentId: String, runId: String): Flow<StreamEvent> =
        streamClient.stream(agentId, runId)

    suspend fun loadConversation(agentId: String): List<ChatItem> {
        val fromV0 = runCatching { api.getConversation(agentId).messages }
            .getOrElse { emptyList() }
        if (fromV0.isNotEmpty()) {
            return fromV0.mapIndexed { index, message -> message.toChatItem(index) }
        }

        // Fallback: reconstruct from completed runs (assistant results only).
        val runs = runCatching { listRuns(agentId) }.getOrElse { emptyList() }
        return runs.asReversed().flatMap { run ->
            val items = mutableListOf<ChatItem>()
            if (!run.result.isNullOrBlank()) {
                items += ChatItem.AssistantMessage(
                    id = "run-result-${run.id}",
                    text = run.result
                )
            }
            if (!run.git?.branches.isNullOrEmpty()) {
                items += ChatItem.GitSummary(
                    id = "run-git-${run.id}",
                    branches = run.git!!.branches
                )
            }
            items
        }
    }

    fun mapError(t: Throwable): String = t.toUserMessage()
}

private fun ConversationMessage.toChatItem(index: Int): ChatItem {
    val text = this.text.orEmpty()
    val role = (role ?: type)?.lowercase().orEmpty()
    val id = this.id ?: "msg-$index"
    return when {
        role.contains("user") || role.contains("human") -> ChatItem.UserMessage(id, text)
        role.contains("tool") -> ChatItem.ToolCallMessage(id, name = type ?: "tool", status = "completed", detail = text)
        role.contains("system") -> ChatItem.SystemMessage(id, text)
        else -> ChatItem.AssistantMessage(id, text)
    }
}
