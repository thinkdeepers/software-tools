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
    val catalog = mutableStateListOf<RemoteModel>()
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var accountLabel by mutableStateOf("")

    private val gate = Mutex()
    private var reposLoaded = false

    fun clear() {
        sessions.clear()
        repos.clear()
        catalog.clear()
        loading = false
        error = null
        accountLabel = ""
        reposLoaded = false
    }

    suspend fun refresh(context: Context) {
        val key = AuthRepository.apiKey(context) ?: throw CursorApi.Unauthorized("未登录")
        gate.withLock {
            loading = true
            error = null
            try {
                val bundle = withContext(Dispatchers.IO) { fetchBundle(key) }
                sessions.clear()
                sessions.addAll(bundle.sessions)
                if (bundle.models.isNotEmpty()) {
                    catalog.clear()
                    catalog.addAll(bundle.models)
                }
                if (bundle.account.isNotBlank()) accountLabel = bundle.account
                loading = false
                val enriched = enrichRepos(key, sessions.toList())
                enriched.forEach { updated ->
                    val index = sessions.indexOfFirst { it.id == updated.id }
                    if (index >= 0) sessions[index] = updated
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
        repos.clear()
        repos.addAll(loaded)
        reposLoaded = true
    }

    suspend fun loadMessages(context: Context, agentId: String): List<ChatMessage> {
        val key = AuthRepository.apiKey(context) ?: throw CursorApi.Unauthorized("未登录")
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
    }

    suspend fun create(context: Context, request: AgentRequest): String {
        val key = AuthRepository.apiKey(context) ?: throw CursorApi.Unauthorized("未登录")
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
        val session = mapV1Agent(agent, selection.id).copy(
            latestRunId = runId.ifBlank { agent.optStr("latestRunId") },
            branch = request.branch.ifBlank { mapV1Agent(agent).branch },
            repo = shortRepo(normalizeRepoUrl(request.repo)).ifBlank { mapV1Agent(agent).repo }
        )
        val index = sessions.indexOfFirst { it.id == session.id }
        if (index >= 0) sessions[index] = session else sessions.add(0, session)
        return session.id
    }

    suspend fun followUp(
        context: Context,
        agentId: String,
        text: String,
        tier: ModelTier,
        modelName: String,
        onDelta: (String) -> Unit
    ): Boolean {
        val key = AuthRepository.apiKey(context) ?: throw CursorApi.Unauthorized("未登录")
        val selection = selectModel(tier, modelName, catalog.toList())
        val started = withContext(Dispatchers.IO) { CursorApi.startRun(key, agentId, text, selection) }
        withContext(Dispatchers.IO) {
            CursorApi.collectRunText(key, agentId, started.runId) { delta ->
                withContext(Dispatchers.Main) { onDelta(delta) }
            }
        }
        return started.modelSent
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
                if (session.repo.isNotBlank() || limited.get()) return@async session
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
