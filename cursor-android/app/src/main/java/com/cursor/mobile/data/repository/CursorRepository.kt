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
import com.cursor.mobile.data.model.ArtifactItem
import com.cursor.mobile.data.model.EnvRef
import com.cursor.mobile.data.model.McpServerConfig
import com.cursor.mobile.data.model.ModelSelection
import com.cursor.mobile.data.model.PromptImage
import com.cursor.mobile.data.model.PromptPayload
import com.cursor.mobile.data.model.RepoConfig
import com.cursor.mobile.data.model.TokenUsage
import com.cursor.mobile.data.model.RepositoryItem
import com.cursor.mobile.data.model.RunStatus
import com.cursor.mobile.data.model.RunSummary
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    suspend fun listAgentDetails(includeArchived: Boolean = false): List<AgentDetail> = coroutineScope {
        api.listAgents(limit = 50, includeArchived = includeArchived).items.map { summary ->
            async {
                runCatching { api.getAgent(summary.id) }.getOrElse {
                    AgentDetail(
                        id = summary.id,
                        name = summary.name,
                        status = summary.status,
                        env = summary.env,
                        url = summary.url,
                        createdAt = summary.createdAt,
                        updatedAt = summary.updatedAt,
                        latestRunId = summary.latestRunId
                    )
                }
            }
        }.awaitAll()
    }

    suspend fun getAgent(id: String): AgentDetail = api.getAgent(id)

    suspend fun createAgent(
        prompt: String,
        repoUrl: String?,
        startingRef: String?,
        model: ModelSelection?,
        mode: String?,
        autoCreatePR: Boolean,
        name: String?,
        images: List<PromptImage> = emptyList(),
        env: EnvRef? = null,
        mcpServers: List<McpServerConfig> = emptyList(),
        prUrl: String? = null
    ): Pair<AgentDetail, RunSummary> {
        val repos = repoUrl?.takeIf { it.isNotBlank() }?.let {
            listOf(
                RepoConfig(
                    url = it.trim(),
                    startingRef = startingRef?.takeIf { ref -> ref.isNotBlank() },
                    prUrl = prUrl?.takeIf { value -> value.isNotBlank() }
                )
            )
        }
        val response = api.createAgent(
            CreateAgentRequest(
                prompt = PromptPayload(
                    text = prompt.trim(),
                    images = images.takeIf { it.isNotEmpty() }
                ),
                model = model,
                name = name?.takeIf { it.isNotBlank() },
                env = env,
                repos = repos,
                autoCreatePR = autoCreatePR,
                mode = mode,
                mcpServers = mcpServers.takeIf { it.isNotEmpty() }
            )
        )
        return response.agent to response.run
    }

    suspend fun createFollowUp(
        agentId: String,
        prompt: String,
        mode: String? = null,
        images: List<PromptImage> = emptyList(),
        model: ModelSelection? = null
    ): RunSummary = api.createRun(
        agentId,
        CreateRunRequest(
            prompt = PromptPayload(
                text = prompt.trim().ifBlank { "请根据附图继续。" },
                images = images.takeIf { it.isNotEmpty() }
            ),
            mode = mode,
            model = model
        )
    ).run

    suspend fun getRun(agentId: String, runId: String): RunSummary = api.getRun(agentId, runId)

    suspend fun listRuns(agentId: String): List<RunSummary> = api.listRuns(agentId).items

    suspend fun cancelRun(agentId: String, runId: String) = api.cancelRun(agentId, runId)

    suspend fun archiveAgent(agentId: String) = api.archiveAgent(agentId)

    suspend fun unarchiveAgent(agentId: String) = api.unarchiveAgent(agentId)

    suspend fun deleteAgent(agentId: String) = api.deleteAgent(agentId)

    suspend fun listArtifacts(agentId: String): List<ArtifactItem> = api.listArtifacts(agentId).items

    suspend fun artifactUrl(agentId: String, path: String): String =
        api.downloadArtifact(agentId, path).url

    suspend fun usage(agentId: String): TokenUsage? = api.getUsage(agentId).totalUsage

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
