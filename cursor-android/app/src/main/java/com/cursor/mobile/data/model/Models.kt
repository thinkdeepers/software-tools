package com.cursor.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MeResponse(
    val apiKeyName: String? = null,
    val createdAt: String? = null,
    val userId: Int? = null,
    val userEmail: String? = null,
    val userFirstName: String? = null,
    val userLastName: String? = null
)

@Serializable
data class ModelsResponse(
    val items: List<ModelInfo> = emptyList()
)

@Serializable
data class ModelInfo(
    val id: String,
    val displayName: String? = null,
    val description: String? = null,
    val aliases: List<String> = emptyList(),
    val parameters: List<ModelParameter> = emptyList(),
    val variants: List<ModelVariant> = emptyList()
) {
    val label: String get() = displayName?.takeIf { it.isNotBlank() } ?: id
}

@Serializable
data class ModelParameter(
    val id: String,
    val displayName: String? = null,
    val values: List<ModelParameterValue> = emptyList()
)

@Serializable
data class ModelParameterValue(
    val value: String,
    val displayName: String? = null
)

@Serializable
data class ModelVariant(
    val params: List<ModelParam> = emptyList(),
    val displayName: String? = null,
    val description: String? = null,
    val isDefault: Boolean = false
)

@Serializable
data class ModelParam(
    val id: String,
    val value: String
)

@Serializable
data class ModelSelection(
    val id: String,
    val params: List<ModelParam> = emptyList()
)

@Serializable
data class RepositoriesResponse(
    val items: List<RepositoryItem> = emptyList()
)

@Serializable
data class RepositoryItem(
    val url: String
) {
    val displayName: String
        get() = url.removePrefix("https://").removePrefix("http://")
            .removePrefix("github.com/")
}

@Serializable
data class AgentsListResponse(
    val items: List<AgentSummary> = emptyList(),
    val nextCursor: String? = null
)

@Serializable
data class AgentSummary(
    val id: String,
    val name: String? = null,
    val status: String? = null,
    val env: EnvRef? = null,
    val url: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val latestRunId: String? = null
)

@Serializable
data class AgentDetail(
    val id: String,
    val name: String? = null,
    val status: String? = null,
    val env: EnvRef? = null,
    val repos: List<RepoConfig> = emptyList(),
    val workOnCurrentBranch: Boolean = false,
    val autoCreatePR: Boolean? = null,
    val url: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val latestRunId: String? = null
)

@Serializable
data class EnvRef(
    val type: String? = null,
    val name: String? = null
)

@Serializable
data class RepoConfig(
    val url: String,
    val startingRef: String? = null,
    val prUrl: String? = null
)

@Serializable
data class PromptPayload(
    val text: String,
    val images: List<PromptImage>? = null
)

@Serializable
data class PromptImage(
    val data: String? = null,
    val mimeType: String? = null,
    val url: String? = null
)

@Serializable
data class McpServerConfig(
    val name: String,
    val type: String = "http",
    val url: String
)

@Serializable
data class CreateAgentRequest(
    val prompt: PromptPayload,
    val model: ModelSelection? = null,
    val name: String? = null,
    val env: EnvRef? = null,
    val repos: List<RepoConfig>? = null,
    val workOnCurrentBranch: Boolean = false,
    val autoCreatePR: Boolean? = null,
    val mode: String? = null,
    val mcpServers: List<McpServerConfig>? = null
)

@Serializable
data class CreateAgentResponse(
    val agent: AgentDetail,
    val run: RunSummary
)

@Serializable
data class CreateRunRequest(
    val prompt: PromptPayload,
    val mode: String? = null,
    val model: ModelSelection? = null
)

@Serializable
data class CreateRunResponse(
    val run: RunSummary
)

@Serializable
data class RunsListResponse(
    val items: List<RunSummary> = emptyList(),
    val nextCursor: String? = null
)

@Serializable
data class RunSummary(
    val id: String,
    val agentId: String? = null,
    val status: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val durationMs: Long? = null,
    val result: String? = null,
    val git: GitInfo? = null
)

@Serializable
data class GitInfo(
    val branches: List<GitBranch> = emptyList()
)

@Serializable
data class GitBranch(
    val repoUrl: String? = null,
    val branch: String? = null,
    val prUrl: String? = null
)

@Serializable
data class IdResponse(
    val id: String
)

@Serializable
data class UsageResponse(
    val totalUsage: TokenUsage? = null,
    val runs: List<RunUsage> = emptyList()
)

@Serializable
data class TokenUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val totalTokens: Long = 0
)

@Serializable
data class RunUsage(
    val id: String,
    val usageUuid: String? = null,
    val usage: TokenUsage? = null
)

@Serializable
data class ArtifactsResponse(
    val items: List<ArtifactItem> = emptyList()
)

@Serializable
data class ArtifactItem(
    val path: String,
    val sizeBytes: Long? = null,
    val updatedAt: String? = null
)

@Serializable
data class ArtifactDownloadResponse(
    val url: String,
    val expiresAt: String? = null
)

/** Legacy v0 conversation history for rendering prior turns. */
@Serializable
data class ConversationResponse(
    val id: String? = null,
    val messages: List<ConversationMessage> = emptyList()
)

@Serializable
data class ConversationMessage(
    val id: String? = null,
    val type: String? = null,
    val text: String? = null,
    @SerialName("role") val role: String? = null
)

@Serializable
data class ApiErrorBody(
    val error: ApiErrorDetail? = null,
    val code: String? = null,
    val message: String? = null
)

@Serializable
data class ApiErrorDetail(
    val code: String? = null,
    val message: String? = null
)

enum class AgentMode(val apiValue: String, val label: String) {
    AGENT("agent", "Agent"),
    PLAN("plan", "Plan")
}

enum class RunStatus {
    CREATING, RUNNING, FINISHED, ERROR, CANCELLED, EXPIRED, UNKNOWN;

    companion object {
        fun from(raw: String?): RunStatus = when (raw?.uppercase()) {
            "CREATING" -> CREATING
            "RUNNING" -> RUNNING
            "FINISHED" -> FINISHED
            "ERROR" -> ERROR
            "CANCELLED" -> CANCELLED
            "EXPIRED" -> EXPIRED
            else -> UNKNOWN
        }
    }

    val isActive: Boolean get() = this == CREATING || this == RUNNING
    val isTerminal: Boolean get() = this == FINISHED || this == ERROR || this == CANCELLED || this == EXPIRED
}

sealed class ChatItem {
    abstract val id: String

    data class UserMessage(
        override val id: String,
        val text: String
    ) : ChatItem()

    data class AssistantMessage(
        override val id: String,
        val text: String,
        val isStreaming: Boolean = false
    ) : ChatItem()

    data class ThinkingMessage(
        override val id: String,
        val text: String,
        val isStreaming: Boolean = false
    ) : ChatItem()

    data class ToolCallMessage(
        override val id: String,
        val name: String,
        val status: String,
        val detail: String? = null
    ) : ChatItem()

    data class SystemMessage(
        override val id: String,
        val text: String
    ) : ChatItem()

    data class GitSummary(
        override val id: String,
        val branches: List<GitBranch>
    ) : ChatItem()
}
