package com.cursor.mobile.android.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

object AgentStore {
    val sessions = mutableStateListOf<AgentSession>()
    val repos = mutableStateListOf<RepoRef>()
    val projects = mutableStateListOf<ProjectRef>()
    val catalog = mutableStateListOf<RemoteModel>()
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var accountLabel by mutableStateOf("")
    var syncReport by mutableStateOf("")
    var syncSummary by mutableStateOf("")
    var scope by mutableStateOf(WorkScope.PROJECT)
        private set

    private val gate = Mutex()
    private var reposLoaded = false
    private var projectsLoaded = false
    private var scopeTouched = false

    fun choose(next: WorkScope) {
        scope = next
        scopeTouched = true
    }

    fun clear() {
        sessions.clear()
        repos.clear()
        projects.clear()
        catalog.clear()
        loading = false
        error = null
        accountLabel = ""
        syncReport = ""
        syncSummary = ""
        reposLoaded = false
        projectsLoaded = false
        scopeTouched = false
        scope = WorkScope.PROJECT
    }

    suspend fun refresh(context: Context) {
        val token = AuthRepository.accessToken(context) ?: throw CursorApi.Unauthorized("未登录")
        gate.withLock {
            loading = true
            error = null
            try {
                val sync = CursorSession.sync(token, AuthRepository.apiKey(context))
                syncReport = sync.report
                syncSummary = summarizeSync(sync.httpCode, sync.composers.size, 0, 0)
                if (sync.httpCode == 401 || sync.httpCode == 403) {
                    throw CursorApi.Unauthorized(syncSummary.ifBlank { "登录已失效（HTTP ${sync.httpCode}）" })
                }
                if (sync.httpCode !in 200..299) {
                    error = syncSummary
                    return@withLock
                }
                var merged = linkProjectNames(mapDesktopComposers(sync.composers))
                val key = AuthRepository.apiKey(context)
                if (key != null) {
                    try {
                        val bundle = withContext(Dispatchers.IO) { fetchBundle(key) }
                        merged = linkProjectNames(mergeDesktop(merged, bundle.sessions))
                        merged = linkProjectNames(enrichRepos(key, merged))
                        if (bundle.models.isNotEmpty()) {
                            catalog.clear()
                            catalog.addAll(bundle.models)
                        }
                        if (bundle.account.isNotBlank()) accountLabel = bundle.account
                    } catch (_: Exception) {
                        // 会话已经能列项目；Cloud Agents 凭证失败时不把列表清空
                    }
                }
                val email = AuthRepository.accountLabel(context)
                if (email.isNotBlank()) accountLabel = email
                sessions.clear()
                sessions.addAll(merged)
                projects.clear()
                sync.projects.forEach { item ->
                    if (projects.none { (item.id.isNotBlank() && it.id == item.id) || it.name.equals(item.name, true) }) {
                        projects.add(item)
                    }
                }
                projectsLoaded = true
                absorbTargets()
                reposLoaded = false
                try {
                    ensureRepos(context)
                } catch (_: Exception) {
                    // 仓库目录失败时仍用会话上的仓库地址
                }
                absorbTargets()
                val projectCount = sessions.count { it.scope == WorkScope.PROJECT }
                val repoCount = sessions.count { it.scope == WorkScope.REPOSITORY }
                syncSummary = summarizeSync(sync.httpCode, sessions.size, projectCount, repoCount)
                if (!scopeTouched) {
                    scope = if (projectCount == 0 && repoCount > 0) WorkScope.REPOSITORY else WorkScope.PROJECT
                }
            } catch (e: CursorApi.Unauthorized) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "同步失败"
            } finally {
                loading = false
            }
        }
    }

    suspend fun ensureModels(context: Context) {
        if (catalog.isNotEmpty()) return
        val key = AuthRepository.apiKey(context) ?: return
        try {
            val models = withContext(Dispatchers.IO) { CursorApi.listModels(key) }
            if (models.isNotEmpty()) {
                catalog.clear()
                catalog.addAll(models)
            }
        } catch (e: CursorApi.Unauthorized) {
            throw e
        } catch (_: Exception) {
            // 列表失败时界面用本地模型，选择仍然可点
        }
    }

    suspend fun ensureRepos(context: Context) {
        if (reposLoaded) return
        val key = AuthRepository.apiKey(context) ?: return
        val loaded = withContext(Dispatchers.IO) { CursorApi.listRepos(key) }
        loaded.forEach { item ->
            if (repos.none { (item.url.isNotBlank() && it.url == item.url) || it.name.equals(item.name, true) }) {
                repos.add(item)
            }
        }
        reposLoaded = true
    }

    suspend fun ensureProjects(context: Context) {
        if (projectsLoaded) return
        if (AuthRepository.accessToken(context) == null) return
        absorbTargets()
        projectsLoaded = true
    }

    suspend fun loadMessages(context: Context, agentId: String): List<ChatMessage> {
        val key = cloudKey(context)
        hydrate(context, agentId)
        return withContext(Dispatchers.IO) { fetchMessages(key, agentId) }
    }

    suspend fun hydrate(context: Context, agentId: String) {
        val key = AuthRepository.apiKey(context) ?: return
        val current = sessions.firstOrNull { it.id == agentId }
        val updated = withContext(Dispatchers.IO) {
            var session = current ?: AgentSession(
                id = agentId,
                title = "对话",
                repo = "",
                branch = "",
                status = AgentStatus.WORKING,
                model = "",
                machine = MachineKind.CLOUD,
                updatedAt = ""
            )
            val detail = CursorApi.getOptional(key, "/v1/agents/$agentId")
            if (detail != null) session = applyDetail(session, detail)
            val runId = session.latestRunId
            if (runId.isNotBlank()) {
                val run = CursorApi.getOptional(key, "/v1/agents/$agentId/runs/$runId")
                if (run != null) session = applyRunGit(session, run)
            }
            session
        }
        val index = sessions.indexOfFirst { it.id == agentId }
        if (index >= 0) sessions[index] = updated else sessions.add(0, updated)
        absorbTargets()
    }

    suspend fun create(context: Context, request: AgentRequest): String {
        val key = cloudKey(context)
        if (catalog.isEmpty()) {
            val models = withContext(Dispatchers.IO) { CursorApi.listModels(key) }
            if (models.isNotEmpty()) {
                catalog.clear()
                catalog.addAll(models)
            }
        }
        val selection = request.selection(catalog.toList())
        val body = withContext(Dispatchers.IO) { CursorApi.createAgent(key, request, selection) }
        val agent = body.optJSONObject("agent") ?: body
        val runId = body.optJSONObject("run")?.optStr("id").orEmpty()
        val mapped = mapV1Agent(agent, selection.id)
        val session = if (request.scope == WorkScope.PROJECT) {
            mapped.copy(
                latestRunId = runId.ifBlank { agent.optStr("latestRunId") },
                scope = WorkScope.PROJECT,
                envName = request.projectName.ifBlank { mapped.envName },
                projectId = request.projectId.ifBlank { mapped.projectId },
                repoUrl = normalizeRepoUrl(request.repo).ifBlank { mapped.repoUrl },
                groupLabel = request.projectName.ifBlank { mapped.groupLabel },
                classified = true
            )
        } else {
            val url = normalizeRepoUrl(request.repo)
            val short = shortRepo(url).ifBlank { mapped.repo }
            mapped.copy(
                latestRunId = runId.ifBlank { agent.optStr("latestRunId") },
                scope = WorkScope.REPOSITORY,
                branch = request.branch.ifBlank { mapped.branch },
                repo = short,
                repoUrl = url.ifBlank { mapped.repoUrl },
                envName = "",
                projectId = "",
                groupLabel = short.ifBlank { mapped.groupLabel },
                classified = true
            )
        }
        val index = sessions.indexOfFirst { it.id == session.id }
        if (index >= 0) sessions[index] = session else sessions.add(0, session)
        absorbTargets()
        return session.id
    }

    private fun absorbTargets() {
        sessions.forEach { session ->
            if (session.scope == WorkScope.PROJECT && session.envName.isNotBlank() &&
                projects.none { it.name.equals(session.envName, true) || (session.projectId.isNotBlank() && it.id == session.projectId) }
            ) {
                projects.add(ProjectRef(session.envName, session.projectId, session.repoUrl))
            }
            if (session.repoUrl.isNotBlank() && repos.none { it.url == session.repoUrl || it.name.equals(shortRepo(session.repoUrl), true) }) {
                repos.add(RepoRef(shortRepo(session.repoUrl), session.branch.ifBlank { "main" }, session.repoUrl))
            }
        }
    }

    suspend fun followUp(
        context: Context,
        agentId: String,
        text: String,
        tier: ModelTier,
        modelName: String,
        images: List<PromptImage> = emptyList(),
        onDelta: (String) -> Unit
    ): Boolean {
        val key = cloudKey(context)
        val selection = selectModel(tier, modelName, catalog.toList())
        val started = withContext(Dispatchers.IO) { CursorApi.startRun(key, agentId, text, selection, images) }
        withContext(Dispatchers.IO) {
            CursorApi.collectRunText(key, agentId, started.runId) { delta ->
                withContext(Dispatchers.Main) { onDelta(delta) }
            }
        }
        return started.modelSent
    }

    private fun summarizeSync(code: Int, total: Int, projects: Int, repos: Int): String = when {
        code == 0 -> "没有连上 Cursor，列表没更新。"
        code !in 200..299 -> "列表请求失败（HTTP $code）。"
        total == 0 -> "服务器返回 0 条会话。"
        projects > 0 -> ""
        projects == 0 && total > 0 -> "还没有从 projectMetadata 分出项目。"
        else -> "同步到 $total 条会话，但没有可归类的项目或仓库。"
    }

    private fun cloudKey(context: Context): String {
        if (AuthRepository.accessToken(context) == null) throw CursorApi.Unauthorized("未登录")
        return AuthRepository.apiKey(context)
            ?: throw CursorApi.ApiException("已登录，但没有换发 Cloud Agents 凭证。请退出后重新登录。")
    }

    private fun fetchBundle(apiKey: String): Bundle {
        val v1 = paginate(apiKey, "v1", "items", "includeArchived=true")
        val v0 = try {
            paginate(apiKey, "v0", "agents")
        } catch (_: Exception) {
            emptyList()
        }
        val models = try {
            CursorApi.listModels(apiKey)
        } catch (_: Exception) {
            emptyList()
        }
        val account = try {
            CursorApi.accountLabel(apiKey)
        } catch (_: Exception) {
            ""
        }
        return Bundle(mergeAgents(v1, v0), models, account)
    }

    private fun paginate(apiKey: String, version: String, arrayKey: String, extra: String = ""): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        repeat(200) {
            val (items, next) = CursorApi.listAgentPage(apiKey, version, cursor, arrayKey, extra)
            out += items
            if (next.isNullOrBlank() || next == cursor || !seen.add(next)) return out
            cursor = next
        }
        return out
    }

    private suspend fun enrichRepos(apiKey: String, items: List<AgentSession>): List<AgentSession> = coroutineScope {
        val gate = Semaphore(4)
        val limited = AtomicBoolean(false)
        items.map { session ->
            async(Dispatchers.IO) {
                if (limited.get()) return@async session
                gate.withPermit {
                    if (limited.get()) return@withPermit session
                    try {
                        val detail = CursorApi.getOptional(apiKey, "/v1/agents/${session.id}") ?: return@withPermit session
                        applyDetail(session, detail)
                    } catch (e: CursorApi.Unauthorized) {
                        throw e
                    } catch (e: CursorApi.ApiException) {
                        if (e.message?.contains("429") == true) limited.set(true)
                        session
                    } catch (_: Exception) {
                        session
                    }
                }
            }
        }.awaitAll()
    }

    private fun fetchMessages(apiKey: String, agentId: String): List<ChatMessage> {
        val encoded = android.net.Uri.encode(agentId)
        try {
            val conversation = CursorApi.getJson(apiKey, "/v0/agents/$encoded/conversation")
            val messages = mapConversation(conversation)
            if (messages.isNotEmpty()) return messages
        } catch (e: CursorApi.Unauthorized) {
            throw e
        } catch (e: CursorApi.ApiException) {
            val fallback = runHistory(apiKey, agentId)
            if (fallback.isNotEmpty()) return historyNote() + fallback
            if (e.message?.contains("HTTP 404") == true) return emptyList()
            throw e
        }
        return runHistory(apiKey, agentId)
    }

    private fun historyNote() = listOf(
        ChatMessage(
            "history-note",
            Sender.SYSTEM,
            "完整对话接口无记录，以下是各次 run 的最终回复。",
            ""
        )
    )

    private fun runHistory(apiKey: String, agentId: String): List<ChatMessage> {
        val encoded = android.net.Uri.encode(agentId)
        val runs = paginateRuns(apiKey, agentId)
        val detailed = runs.map { run ->
            if (run.optStr("result").isNotBlank() || run.optJSONObject("prompt") != null) run
            else {
                val runId = run.optStr("id")
                if (runId.isBlank()) run
                else CursorApi.getOptional(apiKey, "/v1/agents/$encoded/runs/${android.net.Uri.encode(runId)}") ?: run
            }
        }
        return mapRunsToMessages(detailed)
    }

    private fun paginateRuns(apiKey: String, agentId: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        val encoded = android.net.Uri.encode(agentId)
        repeat(50) {
            val path = buildString {
                append("/v1/agents/").append(encoded).append("/runs?limit=100")
                if (!cursor.isNullOrBlank()) append("&cursor=").append(java.net.URLEncoder.encode(cursor, Charsets.UTF_8.name()))
            }
            val json = try {
                CursorApi.getJson(apiKey, path)
            } catch (e: CursorApi.Unauthorized) {
                throw e
            } catch (_: Exception) {
                return out
            }
            val items = json.optJSONArray("items")
            if (items != null) {
                for (i in 0 until items.length()) items.optJSONObject(i)?.let { out += it }
            }
            val next = json.optStr("nextCursor")
            if (next.isBlank() || next == cursor || !seen.add(next)) return out
            cursor = next
        }
        return out
    }

    private data class Bundle(
        val sessions: List<AgentSession>,
        val models: List<RemoteModel>,
        val account: String
    )
}
