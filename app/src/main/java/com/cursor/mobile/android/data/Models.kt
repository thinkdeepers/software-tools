package com.cursor.mobile.android.data

enum class AgentStatus { WORKING, NEEDS_INPUT, READY_FOR_REVIEW, DONE, FAILED }
enum class Sender { USER, AGENT, SYSTEM }
enum class MachineKind { CLOUD, TEAM_POOL, MY_MACHINE }

data class AgentSession(
    val id: String,
    val title: String,
    val repo: String,
    val branch: String,
    val status: AgentStatus,
    val model: String,
    val machine: MachineKind,
    val updatedAt: String,
    val unread: Int = 0,
    val source: String = "androidApp"
)

data class ChatMessage(
    val id: String,
    val sender: Sender,
    val text: String,
    val time: String,
    val isStreaming: Boolean = false,
    val attachment: String? = null
)

data class DiffFile(
    val path: String,
    val additions: Int,
    val deletions: Int,
    val status: String,
    val preview: String
)

data class RepoRef(val name: String, val branch: String, val isPrivate: Boolean = true)
