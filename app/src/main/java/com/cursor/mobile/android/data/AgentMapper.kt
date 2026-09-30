package com.cursor.mobile.android.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun mapV1Agent(obj: JSONObject, modelLabel: String = ""): AgentSession {
    val envObj = obj.optJSONObject("env")
    val envType = envObj?.optStr("type").orEmpty()
    val envName = envObj?.optStr("name").orEmpty()
    val namedProject = projectName(obj)
    val repos = obj.optJSONArray("repos")
    val firstRepo = repos?.optJSONObject(0)
    val repoUrl = firstRepo?.optStr("url").orEmpty()
    val startingRef = firstRepo?.optStr("startingRef").orEmpty()
    val iso = obj.optStr("updatedAt").ifBlank { obj.optStr("createdAt") }
    val scope = when {
        repoUrl.isNotBlank() -> WorkScope.REPOSITORY
        namedProject.isNotBlank() -> WorkScope.PROJECT
        repos != null -> WorkScope.PROJECT
        else -> null
    }
    val short = shortRepo(repoUrl).takeUnless { repoUrl.isBlank() }.orEmpty()
    val projectLabel = namedProject.ifBlank { envName }
    return AgentSession(
        id = obj.optStr("id"),
        title = obj.optStr("name").ifBlank { "未命名会话" },
        repo = if (scope == WorkScope.REPOSITORY) short else "",
        branch = if (scope == WorkScope.REPOSITORY) startingRef else "",
        status = mapAgentStatus(obj.optStr("status")),
        model = modelLabel,
        machine = mapMachine(envType),
        updatedAt = formatTime(iso),
        latestRunId = obj.optStr("latestRunId"),
        webUrl = obj.optStr("url"),
        summary = obj.optStr("summary"),
        updatedAtIso = iso,
        scope = scope,
        envName = if (scope == WorkScope.PROJECT) projectLabel else "",
        repoUrl = if (scope == WorkScope.REPOSITORY) repoUrl else "",
        groupLabel = when (scope) {
            WorkScope.REPOSITORY -> short
            WorkScope.PROJECT -> projectLabel.ifBlank { "未命名项目" }
            null -> ""
        }
    )
}

internal fun mapV0Agent(obj: JSONObject): AgentSession {
    val source = obj.optJSONObject("source")
    val target = obj.optJSONObject("target")
    val repository = source?.optStr("repository").orEmpty()
    val prUrl = source?.optStr("prUrl").orEmpty()
    val repoUrl = repository.ifBlank { prUrl.substringBefore("/pull").substringBefore("/pulls") }
    val namedProject = projectName(obj).ifBlank { source?.optStr("project").orEmpty() }
    val iso = obj.optStr("updatedAt").ifBlank { obj.optStr("createdAt") }
    val scope = when {
        repository.isNotBlank() || prUrl.isNotBlank() -> WorkScope.REPOSITORY
        namedProject.isNotBlank() -> WorkScope.PROJECT
        source != null -> WorkScope.PROJECT
        else -> null
    }
    val short = shortRepo(repoUrl).takeUnless { repoUrl.isBlank() }.orEmpty()
    return AgentSession(
        id = obj.optStr("id"),
        title = obj.optStr("name").ifBlank { obj.optStr("summary").ifBlank { "未命名会话" } },
        repo = if (scope == WorkScope.REPOSITORY) short else "",
        branch = if (scope == WorkScope.REPOSITORY) {
            target?.optStr("branchName").orEmpty().ifBlank { source?.optStr("ref").orEmpty() }
        } else {
            ""
        },
        status = mapAgentStatus(obj.optStr("status")),
        model = "",
        machine = MachineKind.CLOUD,
        updatedAt = formatTime(iso),
        webUrl = target?.optStr("url").orEmpty(),
        prUrl = target?.optStr("prUrl").orEmpty(),
        summary = obj.optStr("summary"),
        updatedAtIso = iso,
        scope = scope,
        envName = if (scope == WorkScope.PROJECT) namedProject else "",
        repoUrl = if (scope == WorkScope.REPOSITORY) repoUrl else "",
        groupLabel = when (scope) {
            WorkScope.REPOSITORY -> short.ifBlank { "未命名仓库" }
            WorkScope.PROJECT -> namedProject.ifBlank { "未命名项目" }
            null -> ""
        }
    )
}

internal fun mergeAgents(v1: List<JSONObject>, v0: List<JSONObject>): List<AgentSession> {
    val byId = LinkedHashMap<String, AgentSession>()
    v1.forEach { obj ->
        val session = mapV1Agent(obj)
        if (session.id.isNotBlank()) byId[session.id] = session
    }
    v0.forEach { obj ->
        val session = mapV0Agent(obj)
        if (session.id.isBlank()) return@forEach
        val existing = byId[session.id]
        byId[session.id] = if (existing == null) session else mergeScope(existing, session)
    }
    return byId.values.sortedByDescending { it.updatedAtIso }
}

internal fun applyDetail(session: AgentSession, detail: JSONObject): AgentSession {
    val mapped = mapV1Agent(detail, session.model)
    val scope = mapped.scope ?: session.scope
    return session.copy(
        title = mapped.title.takeUnless { it == "未命名会话" } ?: session.title,
        repo = if (scope == WorkScope.REPOSITORY) mapped.repo.ifBlank { session.repo } else "",
        branch = if (scope == WorkScope.REPOSITORY) mapped.branch.ifBlank { session.branch } else "",
        status = mapped.status,
        machine = mapped.machine,
        latestRunId = mapped.latestRunId.ifBlank { session.latestRunId },
        webUrl = mapped.webUrl.ifBlank { session.webUrl },
        summary = mapped.summary.ifBlank { session.summary },
        updatedAt = mapped.updatedAt.ifBlank { session.updatedAt },
        updatedAtIso = mapped.updatedAtIso.ifBlank { session.updatedAtIso },
        scope = scope,
        envName = if (scope == WorkScope.PROJECT) mapped.envName.ifBlank { session.envName } else "",
        repoUrl = if (scope == WorkScope.REPOSITORY) mapped.repoUrl.ifBlank { session.repoUrl } else "",
        groupLabel = mapped.groupLabel.ifBlank { session.groupLabel }
    )
}

internal fun applyRunGit(session: AgentSession, run: JSONObject): AgentSession {
    val branchObj = run.optJSONObject("git")?.optJSONArray("branches")?.optJSONObject(0)
    val branch = branchObj?.optStr("branch").orEmpty()
    val repo = branchObj?.optStr("repoUrl").orEmpty()
    val pr = branchObj?.optStr("prUrl").orEmpty()
    val learnedRepo = repo.isNotBlank() && session.scope != WorkScope.PROJECT
    val scope = if (learnedRepo) WorkScope.REPOSITORY else session.scope
    val short = session.repo.ifBlank { shortRepo(repo).takeUnless { repo.isBlank() }.orEmpty() }
    return session.copy(
        repo = if (scope == WorkScope.REPOSITORY) short else "",
        branch = if (scope == WorkScope.REPOSITORY) branch.ifBlank { session.branch } else "",
        prUrl = pr.ifBlank { session.prUrl },
        latestRunId = run.optStr("id").ifBlank { session.latestRunId },
        scope = scope,
        envName = if (scope == WorkScope.PROJECT) session.envName else "",
        repoUrl = if (scope == WorkScope.REPOSITORY) session.repoUrl.ifBlank { normalizeRepoUrl(repo) } else "",
        groupLabel = if (scope == WorkScope.REPOSITORY) short.ifBlank { session.groupLabel } else session.groupLabel
    )
}

private fun mergeScope(existing: AgentSession, incoming: AgentSession): AgentSession {
    val scope = when {
        existing.scope == WorkScope.REPOSITORY || incoming.scope == WorkScope.REPOSITORY -> WorkScope.REPOSITORY
        existing.scope == WorkScope.PROJECT || incoming.scope == WorkScope.PROJECT -> WorkScope.PROJECT
        else -> null
    }
    val repo = existing.repo.ifBlank { incoming.repo }
    val repoUrl = existing.repoUrl.ifBlank { incoming.repoUrl }
    val envName = existing.envName.ifBlank { incoming.envName }
    return existing.copy(
        title = if (existing.title == "未命名会话") incoming.title else existing.title,
        repo = if (scope == WorkScope.REPOSITORY) repo else "",
        branch = if (scope == WorkScope.REPOSITORY) existing.branch.ifBlank { incoming.branch } else "",
        prUrl = existing.prUrl.ifBlank { incoming.prUrl },
        summary = existing.summary.ifBlank { incoming.summary },
        webUrl = existing.webUrl.ifBlank { incoming.webUrl },
        scope = scope,
        envName = if (scope == WorkScope.PROJECT) envName else "",
        repoUrl = if (scope == WorkScope.REPOSITORY) repoUrl else "",
        groupLabel = when (scope) {
            WorkScope.REPOSITORY -> repo.ifBlank { existing.groupLabel.ifBlank { incoming.groupLabel } }
            WorkScope.PROJECT -> envName.ifBlank { existing.groupLabel.ifBlank { incoming.groupLabel } }.ifBlank { "未命名项目" }
            null -> ""
        }
    )
}

private fun projectName(obj: JSONObject): String {
    val project = obj.optJSONObject("project")
    val fromObject = project?.optStr("displayName").orEmpty()
        .ifBlank { project?.optStr("name").orEmpty() }
        .ifBlank { project?.optStr("id").orEmpty() }
    if (fromObject.isNotBlank()) return fromObject
    return obj.optStr("projectName").ifBlank { obj.optStr("projectId") }
}

internal fun mapConversation(body: JSONObject): List<ChatMessage> {
    val messages = body.optJSONArray("messages") ?: return emptyList()
    return messages.toMessages()
}

internal fun mapRunsToMessages(runsNewestFirst: List<JSONObject>): List<ChatMessage> {
    val chronological = runsNewestFirst.asReversed()
    val out = mutableListOf<ChatMessage>()
    chronological.forEach { run ->
        val prompt = run.optJSONObject("prompt")?.optStr("text").orEmpty()
            .ifBlank { run.optStr("promptText") }
            .ifBlank { run.optStr("userMessage") }
        val result = run.optStr("result")
        val time = formatTime(run.optStr("createdAt"))
        val id = run.optStr("id").ifBlank { "run${out.size}" }
        if (prompt.isNotBlank()) {
            out += ChatMessage("$id-user", Sender.USER, prompt, time)
        }
        if (result.isNotBlank()) {
            out += ChatMessage("$id-agent", Sender.AGENT, result, formatTime(run.optStr("updatedAt")).ifBlank { time })
        }
    }
    return out
}

private fun JSONArray.toMessages(): List<ChatMessage> = buildList {
    for (i in 0 until length()) {
        val obj = optJSONObject(i) ?: continue
        val text = obj.optStr("text").ifBlank { obj.optStr("content") }
        if (text.isBlank()) continue
        val type = obj.optStr("type").ifBlank { obj.optStr("role") }.lowercase()
        val sender = when {
            type.contains("user") -> Sender.USER
            type.contains("system") -> Sender.SYSTEM
            else -> Sender.AGENT
        }
        val id = obj.optStr("id").ifBlank { "m$i" }
        add(ChatMessage(id, sender, text, formatTime(obj.optStr("createdAt"))))
    }
}

internal fun mapAgentStatus(raw: String): AgentStatus = when (raw.uppercase()) {
    "ACTIVE", "RUNNING", "CREATING" -> AgentStatus.WORKING
    "FAILED", "ERROR" -> AgentStatus.FAILED
    "IDLE", "FINISHED", "ARCHIVED", "CANCELLED", "CANCELED", "EXPIRED", "DONE" -> AgentStatus.DONE
    else -> if (raw.contains("run", true) || raw.contains("active", true)) AgentStatus.WORKING else AgentStatus.DONE
}

internal fun mapMachine(envType: String): MachineKind = when (envType.lowercase()) {
    "pool" -> MachineKind.TEAM_POOL
    "machine" -> MachineKind.MY_MACHINE
    else -> MachineKind.CLOUD
}

internal fun formatTime(iso: String): String {
    if (iso.isBlank()) return ""
    val zone = ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    try {
        return OffsetDateTime.parse(iso).atZoneSameInstant(zone).format(formatter)
    } catch (_: Exception) {
        // Instant.parse covers values without an offset.
    }
    return try {
        Instant.parse(iso).atZone(zone).format(formatter)
    } catch (_: Exception) {
        iso.take(16).replace('T', ' ')
    }
}
