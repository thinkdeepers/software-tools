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
    val hit = readProject(obj)
    val repos = obj.optJSONArray("repos")
    val firstRepo = repos?.optJSONObject(0)
    val repoUrl = firstRepo?.optStr("url").orEmpty()
    val startingRef = firstRepo?.optStr("startingRef").orEmpty()
    val iso = obj.optStr("updatedAt").ifBlank { obj.optStr("createdAt") }
    val scope = when {
        hit != null -> WorkScope.PROJECT
        repoUrl.isNotBlank() -> WorkScope.REPOSITORY
        else -> null
    }
    val short = shortRepo(repoUrl).takeUnless { repoUrl.isBlank() }.orEmpty()
    val projectLabel = hit?.name.orEmpty()
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
        envName = projectLabel,
        projectId = hit?.id.orEmpty(),
        repoUrl = repoUrl,
        groupLabel = when (scope) {
            WorkScope.REPOSITORY -> short
            WorkScope.PROJECT -> projectLabel
            null -> ""
        },
        classified = hit != null || obj.has("repos")
    )
}

// 桌面左侧 Projects 大约几个名字。ListBackgroundComposers 的条目是会话。
// 只有 projectMetadata 里真有 id 或名字，才把会话归进项目。
// 空对象不分组。不同名字超过 15 个时，也不把会话标题或整批会话当成项目。
// catalog 只用来把已经对上的 id 换成项目名，不能单独把会话塞进 Projects。
internal fun mapDesktopComposers(items: List<JSONObject>, catalog: List<ProjectRef> = emptyList()): List<AgentSession> {
    val byId = catalog.filter { it.id.isNotBlank() }.associate { it.id to it.name }
    val byName = catalog.filter { it.name.isNotBlank() }.associate { it.name.lowercase() to it.name }
    val hits = items.map { projectMetadata(it) }
    val rawLabels = hits.map { hit ->
        if (hit == null) {
            ""
        } else {
            val fromId = byId[hit.id].orEmpty()
            val fromName = byName[hit.name.lowercase()].orEmpty()
            fromId.ifBlank { fromName }.ifBlank { hit.name }.ifBlank {
                if (catalog.isEmpty()) hit.id else ""
            }
        }
    }
    val distinct = rawLabels.filter { it.isNotBlank() }.distinct()
    val union = (catalog.map { it.name } + distinct).filter { it.isNotBlank() }.distinct()
    val metadataTrusted = distinct.size in 1..15 && union.size <= 15
    val labels = rawLabels.map { label ->
        val inCatalog = label.isNotBlank() && catalog.any { it.name.equals(label, true) }
        if (inCatalog || (metadataTrusted && label.isNotBlank())) label else ""
    }
    return items.mapIndexed { index, obj -> composerSession(obj, labels[index], hits[index]) }
}

internal fun rawProjectMetadata(obj: JSONObject): String {
    val raw = obj.opt("projectMetadata") ?: obj.opt("project_metadata")
    return when (raw) {
        null -> "缺失"
        is JSONObject -> if (raw.length() == 0) "空对象 {}" else raw.toString()
        else -> raw.toString()
    }
}

internal fun projectMetadataReport(items: List<JSONObject>): String {
    val named = items.mapNotNull { projectMetadata(it)?.name }.distinct()
    val filled = items.count { projectMetadata(it) != null }
    return buildString {
        appendLine("composer ${items.size} 条")
        appendLine("projectMetadata 有 id 或名字 $filled 条")
        appendLine("projectMetadata 为空或没有 id/名字 ${items.size - filled} 条")
        if (named.size in 1..15) appendLine("projectMetadata 分组 ${named.size} 个：${named.joinToString("、")}")
        else appendLine("projectMetadata 不同名称 ${named.size} 个，未当作项目列表")
        val envIds = items.map { environmentId(it) }.filter { it.isNotBlank() }.distinct()
        val workflows = items.map { it.optStr("workflowId").ifBlank { it.optStr("workflow_id") } }.filter { it.isNotBlank() }.distinct()
        appendLine("environmentPublicId ${envIds.size} 个不同值，未当作项目")
        appendLine("workflowId ${workflows.size} 个不同值，未当作项目")
    }.trim()
}

internal fun desktopFieldReport(items: List<JSONObject>): String {
    if (items.isEmpty()) return "composer 0 条，无法对照桌面 Projects。"
    val keyCounts = linkedMapOf<String, Int>()
    val typeCounts = linkedMapOf<String, Int>()
    items.forEach { obj ->
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!obj.isNull(key)) keyCounts[key] = (keyCounts[key] ?: 0) + 1
        }
        listOf("composerType", "composer_type", "type", "kind", "role", "source").forEach { key ->
            val value = when (val raw = obj.opt(key)) {
                is String -> raw
                is JSONObject -> raw.optStr("type").ifBlank { "object" }
                else -> ""
            }
            if (value.isNotBlank() && value != "null") {
                val label = "$key=$value"
                typeCounts[label] = (typeCounts[label] ?: 0) + 1
            }
        }
    }
    val repo = items.count { readRepoUrl(it).isNotBlank() }
    val flags = items.count { explicitProject(it) }
    val keys = keyCounts.entries.sortedByDescending { it.value }.take(18)
        .joinToString(" ") { "${it.key}:${it.value}" }
    val types = if (typeCounts.isEmpty()) "无" else typeCounts.entries.joinToString(" ") { "${it.key}:${it.value}" }
    return "判别：共 ${items.size} 条，有仓库地址 $repo，无仓库 ${items.size - repo}，显式项目标记 $flags。Projects 只用无仓库的少数协调项或显式标记，其余进 Repositories。字段：$keys。类型：$types。"
}

internal fun mapComposer(obj: JSONObject): AgentSession = mapComposer(obj, readRepoUrl(obj), explicitProject(obj), false)

private fun mapComposer(obj: JSONObject, repoUrl: String, marked: Boolean, noRepoAreProjects: Boolean): AgentSession {
    val id = obj.optStr("bcId").ifBlank { obj.optStr("bc_id") }.ifBlank { obj.optStr("composerId") }.ifBlank { obj.optStr("id") }
    val hit = readProject(obj)
    val branchName = obj.optStr("branchName").ifBlank { obj.optStr("branch_name") }
        .ifBlank { obj.optStr("branch") }.ifBlank { obj.optStr("startingRef") }.ifBlank { obj.optStr("ref") }
        .ifBlank { obj.optJSONObject("source")?.optStr("ref").orEmpty() }
    val iso = readInstant(obj)
    val title = obj.optStr("name").ifBlank { obj.optStr("title") }.ifBlank { obj.optStr("summary") }.ifBlank { "未命名会话" }
    val archived = obj.optBoolean("isArchived", false) || obj.optBoolean("is_archived", false)
    val statusRaw = obj.optStr("status").ifBlank { obj.optStr("composerStatus") }.ifBlank { if (archived) "ARCHIVED" else "" }
    val project = repoUrl.isBlank() && (marked || noRepoAreProjects)
    val short = shortRepo(repoUrl).takeUnless { repoUrl.isBlank() }.orEmpty()
    val label = if (project) {
        hit?.name?.takeIf { it.isNotBlank() } ?: title
    } else {
        short.ifBlank { "未命名仓库" }
    }
    return AgentSession(
        id = id,
        title = title,
        repo = if (project) "" else short,
        branch = if (project) "" else branchName,
        status = mapAgentStatus(statusRaw),
        model = obj.optStr("model"),
        machine = MachineKind.CLOUD,
        updatedAt = formatTime(iso),
        source = "desktop-session",
        webUrl = obj.optStr("url"),
        summary = obj.optStr("summary"),
        updatedAtIso = iso,
        scope = if (project) WorkScope.PROJECT else if (repoUrl.isNotBlank()) WorkScope.REPOSITORY else null,
        envName = if (project) label else "",
        projectId = if (project) hit?.id?.takeIf { it.isNotBlank() } ?: id else "",
        repoUrl = repoUrl,
        groupLabel = if (project || repoUrl.isNotBlank()) label else "",
        classified = project || repoUrl.isNotBlank()
    )
}

private fun explicitProject(obj: JSONObject): Boolean {
    val kind = obj.optStr("kind").ifBlank { obj.optStr("agentType") }.ifBlank { obj.optStr("role") }
        .ifBlank { obj.optStr("composerType") }.ifBlank { obj.optStr("composer_type") }
    val typeOnly = obj.optStr("type")
    val sourceText = obj.opt("source")?.takeIf { it is String }?.toString().orEmpty()
    if (kind.equals("project", true) || kind.equals("coordinator", true)) return true
    if (typeOnly.equals("project", true) || typeOnly.equals("coordinator", true)) return true
    if (sourceText.equals("project", true) || sourceText.equals("coordinator", true)) return true
    if (obj.optBoolean("isProject", false) || obj.optBoolean("is_project", false)) return true
    if (obj.optBoolean("isCoordinator", false) || obj.optBoolean("is_coordinator", false)) return true
    val project = obj.optJSONObject("project")
    if (project != null && (project.optStr("name").isNotBlank() || project.optStr("displayName").isNotBlank() || project.optStr("id").isNotBlank())) {
        return true
    }
    if (obj.optStr("projectName").isNotBlank() || obj.optStr("projectDisplayName").isNotBlank()) return true
    return false
}

internal fun mapV0Agent(obj: JSONObject): AgentSession {
    val source = obj.optJSONObject("source")
    val target = obj.optJSONObject("target")
    val repository = source?.optStr("repository").orEmpty()
    val prUrl = source?.optStr("prUrl").orEmpty()
    val repoUrl = repository.ifBlank { prUrl.substringBefore("/pull").substringBefore("/pulls") }
    val hit = readProject(obj)
    val iso = obj.optStr("updatedAt").ifBlank { obj.optStr("createdAt") }
    val scope = when {
        hit != null -> WorkScope.PROJECT
        repository.isNotBlank() || prUrl.isNotBlank() -> WorkScope.REPOSITORY
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
        envName = hit?.name.orEmpty(),
        projectId = hit?.id.orEmpty(),
        repoUrl = repoUrl,
        groupLabel = when (scope) {
            WorkScope.REPOSITORY -> short.ifBlank { "未命名仓库" }
            WorkScope.PROJECT -> hit?.name.orEmpty()
            null -> ""
        },
        classified = true
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
    val scope = if (session.source == "desktop-session") {
        session.scope
    } else if (mapped.classified) {
        mapped.scope
    } else {
        session.scope
    }
    val projectId = mapped.projectId.ifBlank { session.projectId }
    return session.copy(
        title = mapped.title.takeUnless { it == "未命名会话" } ?: session.title,
        repo = mapped.repo.ifBlank { session.repo },
        branch = mapped.branch.ifBlank { session.branch },
        status = mapped.status,
        machine = mapped.machine,
        latestRunId = mapped.latestRunId.ifBlank { session.latestRunId },
        webUrl = mapped.webUrl.ifBlank { session.webUrl },
        summary = mapped.summary.ifBlank { session.summary },
        updatedAt = mapped.updatedAt.ifBlank { session.updatedAt },
        updatedAtIso = mapped.updatedAtIso.ifBlank { session.updatedAtIso },
        scope = scope,
        envName = if (scope == WorkScope.PROJECT) mapped.envName.ifBlank { session.envName } else "",
        projectId = projectId,
        repoUrl = mapped.repoUrl.ifBlank { session.repoUrl },
        groupLabel = if (scope == WorkScope.PROJECT) mapped.envName.ifBlank { session.groupLabel } else mapped.groupLabel.ifBlank { session.groupLabel },
        classified = true
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
        projectId = session.projectId,
        repoUrl = if (scope == WorkScope.PROJECT) session.repoUrl else session.repoUrl.ifBlank { normalizeRepoUrl(repo) },
        groupLabel = if (scope == WorkScope.REPOSITORY) short.ifBlank { session.groupLabel } else session.groupLabel,
        classified = session.classified || learnedRepo
    )
}

internal fun mergeDesktop(desktop: List<AgentSession>, api: List<AgentSession>): List<AgentSession> {
    val byId = LinkedHashMap<String, AgentSession>()
    desktop.forEach { session ->
        if (session.id.isNotBlank()) byId[session.id] = session
    }
    api.forEach { incoming ->
        if (incoming.id.isBlank() || incoming.scope == null) return@forEach
        val existing = byId[incoming.id]
        if (existing == null) {
            byId[incoming.id] = incoming
            return@forEach
        }
        val merged = mergeScope(existing, incoming)
        byId[incoming.id] = if (existing.scope == WorkScope.PROJECT) {
            merged.copy(
                scope = WorkScope.PROJECT,
                projectId = existing.projectId.ifBlank { merged.projectId },
                envName = existing.envName.ifBlank { merged.envName },
                groupLabel = existing.groupLabel.ifBlank { merged.groupLabel },
                repo = "",
                branch = ""
            )
        } else if (merged.scope == WorkScope.PROJECT) {
            merged.copy(scope = existing.scope, envName = existing.envName, projectId = existing.projectId, groupLabel = existing.groupLabel)
        } else {
            merged
        }
    }
    return byId.values.sortedByDescending { it.updatedAtIso }
}

private fun mergeScope(existing: AgentSession, incoming: AgentSession): AgentSession {
    val projectId = existing.projectId.ifBlank { incoming.projectId }
    val envName = existing.envName.ifBlank { incoming.envName }
    val scope = when {
        existing.scope == WorkScope.PROJECT || incoming.scope == WorkScope.PROJECT || projectId.isNotBlank() -> WorkScope.PROJECT
        existing.scope == WorkScope.REPOSITORY || incoming.scope == WorkScope.REPOSITORY -> WorkScope.REPOSITORY
        else -> null
    }
    val repo = existing.repo.ifBlank { incoming.repo }
    val repoUrl = existing.repoUrl.ifBlank { incoming.repoUrl }
    return existing.copy(
        title = if (existing.title == "未命名会话") incoming.title else existing.title,
        repo = repo,
        branch = existing.branch.ifBlank { incoming.branch },
        prUrl = existing.prUrl.ifBlank { incoming.prUrl },
        summary = existing.summary.ifBlank { incoming.summary },
        webUrl = existing.webUrl.ifBlank { incoming.webUrl },
        scope = scope,
        envName = if (scope == WorkScope.PROJECT) envName else "",
        projectId = projectId,
        repoUrl = repoUrl,
        groupLabel = when (scope) {
            WorkScope.REPOSITORY -> repo.ifBlank { existing.groupLabel.ifBlank { incoming.groupLabel } }
            WorkScope.PROJECT -> envName.ifBlank { existing.groupLabel.ifBlank { incoming.groupLabel } }
            null -> ""
        },
        classified = existing.classified || incoming.classified
    )
}

private data class ProjectHit(val id: String, val name: String)

private fun readProject(obj: JSONObject): ProjectHit? {
    val project = obj.optJSONObject("project")
    val source = obj.optJSONObject("source")
    val sourceProject = source?.optJSONObject("project")
    val meta = obj.optJSONObject("metadata")
    val linkedId = listOf(
        "parentAgentId",
        "parent_agent_id",
        "parentBcId",
        "parent_bc_id",
        "coordinatorAgentId",
        "coordinator_agent_id",
        "coordinatorBcId",
        "coordinator_bc_id",
        "projectBcId",
        "project_bc_id",
        "owningComposerBcId",
        "owning_composer_bc_id",
        "owningProjectId",
        "owning_project_id",
        "rootAgentId",
        "root_agent_id"
    ).firstNotNullOfOrNull { key -> obj.optStr(key).takeIf { it.isNotBlank() } }.orEmpty()
    val projectText = obj.opt("project")?.takeIf { it is String }?.toString()?.takeIf { it.isNotBlank() && it != "null" }.orEmpty()
    val id = project?.optStr("id").orEmpty()
        .ifBlank { obj.optStr("projectId") }
        .ifBlank { obj.optStr("project_id") }
        .ifBlank { source?.optStr("projectId").orEmpty() }
        .ifBlank { sourceProject?.optStr("id").orEmpty() }
        .ifBlank { meta?.optStr("projectId").orEmpty() }
        .ifBlank { linkedId }
        .ifBlank { projectText }
        .ifBlank { scanProjectFields(obj)?.first.orEmpty() }
    val name = project?.optStr("displayName").orEmpty()
        .ifBlank { project?.optStr("name").orEmpty() }
        .ifBlank { obj.optStr("projectName") }
        .ifBlank { obj.optStr("project_name") }
        .ifBlank { source?.opt("project")?.takeIf { it is String }?.toString().orEmpty() }
        .ifBlank { sourceProject?.optStr("name").orEmpty() }
        .ifBlank { sourceProject?.optStr("displayName").orEmpty() }
        .ifBlank { meta?.optStr("projectName").orEmpty() }
        .ifBlank { obj.optStr("projectDisplayName") }
        .ifBlank { projectText }
        .ifBlank { scanProjectFields(obj)?.second.orEmpty() }
    val kind = obj.optStr("kind").ifBlank { obj.optStr("agentType") }.ifBlank { obj.optStr("role") }
        .ifBlank { obj.optStr("composerType") }.ifBlank { obj.optStr("composer_type") }
    val typeOnly = obj.optStr("type")
    val sourceText = obj.opt("source")?.takeIf { it is String }?.toString().orEmpty()
    val marked = kind.equals("project", true) || kind.equals("coordinator", true) ||
        typeOnly.equals("project", true) || typeOnly.equals("coordinator", true) ||
        sourceText.equals("project", true) || sourceText.equals("coordinator", true) ||
        obj.optBoolean("isCoordinator", false) || obj.optBoolean("is_coordinator", false) ||
        obj.optBoolean("isProject", false) || obj.optBoolean("is_project", false)
    val resolvedId = id.ifBlank { if (marked) obj.optStr("id") else "" }
    if (resolvedId.isBlank() && name.isBlank() && !marked) return null
    val label = name.ifBlank { resolvedId }.ifBlank { if (marked) obj.optStr("name") else "" }
    if (label.isBlank()) return null
    return ProjectHit(resolvedId.ifBlank { label }, label)
}

internal fun jsonHasProject(obj: JSONObject): Boolean = projectMetadata(obj) != null

private fun environmentId(obj: JSONObject): String =
    obj.optStr("environmentPublicId").ifBlank { obj.optStr("environment_public_id") }
        .ifBlank { obj.optStr("environmentId") }.ifBlank { obj.optStr("environment_id") }

private fun composerSession(obj: JSONObject, label: String, hit: ProjectHit?): AgentSession {
    val id = obj.optStr("bcId").ifBlank { obj.optStr("bc_id") }.ifBlank { obj.optStr("composerId") }.ifBlank { obj.optStr("id") }
    val repoUrl = readRepoUrl(obj)
    val branchName = obj.optStr("branchName").ifBlank { obj.optStr("branch_name") }
        .ifBlank { obj.optStr("branch") }.ifBlank { obj.optStr("baseBranch") }.ifBlank { obj.optStr("startingCommit") }
    val iso = readInstant(obj)
    val title = obj.optStr("name").ifBlank { obj.optStr("title") }.ifBlank { obj.optStr("summary") }.ifBlank { "未命名会话" }
    val archived = obj.optBoolean("isArchived", false) || obj.optBoolean("is_archived", false)
    val statusRaw = obj.optStr("status").ifBlank { obj.optStr("composerStatus") }.ifBlank { if (archived) "ARCHIVED" else "" }
    val inProject = hit != null && label.isNotBlank()
    return AgentSession(
        id = id,
        title = title,
        repo = "",
        branch = branchName,
        status = mapAgentStatus(statusRaw),
        model = obj.optStr("model").ifBlank { obj.optJSONObject("modelDetails")?.optStr("modelName").orEmpty() },
        machine = MachineKind.CLOUD,
        updatedAt = formatTime(iso),
        source = "desktop-session",
        webUrl = obj.optStr("url"),
        summary = obj.optStr("summary").ifBlank { obj.optStr("prompt") }.take(180),
        updatedAtIso = iso,
        scope = if (inProject) WorkScope.PROJECT else null,
        envName = if (inProject) label else "",
        projectId = if (inProject) hit.id else "",
        repoUrl = repoUrl,
        groupLabel = if (inProject) label else "",
        classified = true
    )
}

private fun projectMetadata(obj: JSONObject): ProjectHit? {
    val raw = obj.opt("projectMetadata") ?: obj.opt("project_metadata") ?: return null
    val meta = when (raw) {
        is JSONObject -> raw
        is JSONArray -> raw.optJSONObject(0)
        is String -> if (raw.trim().startsWith("{")) try { JSONObject(raw) } catch (_: Exception) { null } else null
        else -> null
    } ?: return null
    if (meta.length() == 0) return null
    var id = ""
    var name = ""
    fun take(key: String, text: String, depth: Int) {
        val value = text.trim()
        if (value.isBlank() || value == "null" || value.equals("true", true) || value.equals("false", true)) return
        if (value.length > 80 || value.startsWith("http") || value.startsWith("{") || value.startsWith("[")) return
        if (id.isBlank() && isProjectIdKey(key, depth)) id = value
        if (name.isBlank() && isProjectNameKey(key)) name = value
    }
    fun walk(value: Any?, depth: Int) {
        if (depth > 4) return
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    when (val child = value.opt(key)) {
                        is String -> take(key, child, depth)
                        is JSONObject, is JSONArray -> walk(child, depth + 1)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) walk(value.opt(i), depth + 1)
            }
        }
    }
    walk(meta, 0)
    if (id.isBlank() && name.isBlank()) return null
    return ProjectHit(id.ifBlank { name }, name.ifBlank { id })
}

private fun isProjectIdKey(key: String, depth: Int): Boolean {
    val k = key.lowercase()
    if (k.contains("bc") || k.contains("workflow") || k.contains("environment") || k.contains("user") || k.contains("session") || k.contains("agent")) return false
    if (k == "id" || k == "projectid" || k == "project_id") return depth <= 1
    return k.contains("project") && k.contains("id")
}

private fun isProjectNameKey(key: String): Boolean {
    val k = key.lowercase()
    if (k.contains("model") || k.contains("file") || k.contains("path") || k.contains("url") || k.contains("email") || k.contains("repo")) return false
    return k == "displayname" || k == "display_name" || k == "name" || k == "title" || k == "projectname" || k == "project_name" || k == "label"
}

private fun scanProjectFields(obj: JSONObject): Pair<String, String>? {
    val keys = obj.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        if (!key.contains("project", true)) continue
        if (key.equals("isProject", true) || key.equals("is_project", true)) continue
        when (val value = obj.opt(key)) {
            is String -> {
                if (value.isBlank() || value == "null" || value.equals("true", true) || value.equals("false", true)) continue
                return value to value
            }
            is JSONObject -> {
                val id = value.optStr("id").ifBlank { value.optStr("projectId") }.ifBlank { value.optStr("bcId") }
                val name = value.optStr("displayName").ifBlank { value.optStr("name") }.ifBlank { value.optStr("title") }.ifBlank { id }
                if (name.isNotBlank()) return id.ifBlank { name } to name
            }
            is JSONArray -> {
                val first = value.optJSONObject(0) ?: value.optString(0).takeIf { it.isNotBlank() && it != "null" }
                when (first) {
                    is JSONObject -> {
                        val id = first.optStr("id").ifBlank { first.optStr("projectId") }
                        val name = first.optStr("displayName").ifBlank { first.optStr("name") }.ifBlank { id }
                        if (name.isNotBlank()) return id.ifBlank { name } to name
                    }
                    is String -> return first to first
                }
            }
        }
    }
    return null
}

private fun readRepoUrl(obj: JSONObject): String {
    listOf("repoUrl", "repo_url", "repositoryUrl", "repository_url").forEach { key ->
        asRepo(obj.optStr(key))?.let { return it }
    }
    when (val repoVal = obj.opt("repository")) {
        is String -> asRepo(repoVal)?.let { return it }
        is JSONObject -> asRepo(repoVal.optStr("url").ifBlank { repoVal.optStr("remoteUrl") })?.let { return it }
    }
    val repoObj = obj.optJSONObject("repo")
    asRepo(repoObj?.optStr("url").orEmpty().ifBlank { repoObj?.optStr("remoteUrl").orEmpty() })?.let { return it }
    val source = obj.optJSONObject("source")
    asRepo(source?.optStr("repository").orEmpty())?.let { return it }
    asRepo(source?.optStr("repoUrl").orEmpty())?.let { return it }
    val workspace = obj.optJSONObject("workspace") ?: obj.optJSONObject("git")
    asRepo(workspace?.optStr("repoUrl").orEmpty())?.let { return it }
    asRepo(workspace?.optStr("repository").orEmpty())?.let { return it }
    return ""
}

private fun asRepo(raw: String): String? {
    val value = raw.trim()
    if (value.isBlank() || value == "null" || value.contains("cursor.com")) return null
    val looksLikeRepo = value.contains("github.com") || value.contains("gitlab") || value.endsWith(".git") ||
        value.matches(Regex("[\\w.-]+/[\\w.-]+"))
    if (!looksLikeRepo) return null
    return normalizeRepoUrl(value)
}

private fun readInstant(obj: JSONObject): String {
    listOf("updatedAt", "updated_at", "lastUpdatedAt", "createdAt", "created_at").forEach { key ->
        if (!obj.has(key) || obj.isNull(key)) return@forEach
        when (val raw = obj.opt(key)) {
            is Number -> return epochIso(raw.toLong())
            is String -> {
                if (raw.isBlank() || raw == "null") return@forEach
                raw.toLongOrNull()?.let { return epochIso(it) }
                return raw
            }
        }
    }
    return ""
}

private fun epochIso(n: Long): String {
    val ms = if (n < 10_000_000_000L) n * 1000 else n
    return Instant.ofEpochMilli(ms).toString()
}

internal fun linkProjectNames(sessions: List<AgentSession>): List<AgentSession> {
    return sessions.map { session ->
        if (session.scope != WorkScope.PROJECT || session.groupLabel.isBlank()) return@map session
        session.copy(repo = "", branch = "")
    }
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
