package com.cursor.mobile.android.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

object CursorSession {
    private const val API2 = "https://api2.cursor.sh"
    private const val WEB = "https://cursor.com"

    data class Handshake(val uuid: String, val verifier: String, val url: String)
    data class Tokens(val accessToken: String, val refreshToken: String)
    data class ComposerSync(
        val composers: List<JSONObject>,
        val projects: List<ProjectRef>,
        val report: String,
        val httpCode: Int,
        val adoptedApi: String = ""
    )

    data class MintResult(val apiKey: String?, val email: String, val detail: String)

    private class RateLimited : Exception()

    fun handshake(): Handshake {
        val random = ByteArray(32)
        SecureRandom().nextBytes(random)
        val verifier = b64url(random)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8)))
        val uuid = UUID.randomUUID().toString()
        val url = buildString {
            append("https://cursor.com/loginDeepControl")
            append("?challenge=").append(enc(challenge))
            append("&uuid=").append(enc(uuid))
            append("&mode=login")
            append("&redirectTarget=cli")
            append("&mobile=1")
        }
        return Handshake(uuid, verifier, url)
    }

    suspend fun poll(uuid: String, verifier: String): Tokens {
        var waitMs = 1_000L
        var useGet = false
        repeat(150) {
            coroutineContext.ensureActive()
            val res = try {
                if (useGet) pollGet(uuid, verifier) else pollPost(uuid, verifier)
            } catch (e: IOException) {
                throw CursorApi.ApiException("无法连接 Cursor 登录服务，请检查网络（${e.message ?: "网络错误"}）")
            }
            tokensOf(res.body)?.let { return it }
            if (isPending(res.code, res.body)) {
                delay(waitMs)
                waitMs = (waitMs * 1.2).toLong().coerceAtMost(10_000L)
                return@repeat
            }
            if (!useGet) {
                useGet = true
                return@repeat
            }
            throw CursorApi.ApiException(pollFailure(res.code, res.body))
        }
        throw CursorApi.ApiException("登录超时。请在浏览器里用和电脑端相同的 Cursor 账号完成确认，然后回到应用重试。")
    }

    fun mintCloudCredential(accessToken: String): MintResult {
        val res = try {
            post(
                "$API2/aiserver.v1.DashboardService/CreateUserApiKey",
                accessToken,
                JSONObject().put("name", "Cursor Mobile"),
                25_000
            )
        } catch (e: IOException) {
            return MintResult(null, "", "换发失败：无法连接 api2.cursor.sh（${e.message ?: "网络错误"}）")
        }
        val head = "POST aiserver.v1.DashboardService/CreateUserApiKey\n${summarize(res)}"
        if (res.code !in 200..299) {
            return MintResult(null, "", "换发 Cloud Agents 凭证失败，登录未完成。\n$head")
        }
        val key = readApiKey(res.body)
        if (key.isNullOrBlank()) {
            return MintResult(null, "", "换发接口返回了 HTTP ${res.code}，但没有 apiKey。登录未完成。\n$head")
        }
        val email = accountEmail(accessToken)
        val verify = CursorApi.verifyKey(key)
        if (verify != null) {
            return MintResult(null, email, "凭证已签发，但 Cloud Agents API 拒绝了它，登录未完成。\n$head\n$verify")
        }
        return MintResult(key, email, head)
    }

    fun sessionFromApiKey(apiKey: String): Pair<String?, String> {
        val res = try {
            post("$API2/auth/exchange_user_api_key", apiKey, JSONObject(), 20_000)
        } catch (e: IOException) {
            return null to "POST /auth/exchange_user_api_key 网络失败：${e.message ?: "网络错误"}"
        }
        val token = tokensOf(res.body)?.accessToken
        val note = "POST /auth/exchange_user_api_key\n${summarize(res)}"
        return if (token.isNullOrBlank()) null to note else token to note
    }

    fun accountEmail(accessToken: String): String {
        val res = try {
            post("$API2/aiserver.v1.DashboardService/GetMe", accessToken, JSONObject(), 20_000)
        } catch (_: IOException) {
            return ""
        }
        if (res.code !in 200..299) return emailFromToken(accessToken)
        val obj = try {
            JSONObject(res.body)
        } catch (_: Exception) {
            return emailFromToken(accessToken)
        }
        val email = obj.optStr("email").ifBlank { obj.optStr("userEmail") }.ifBlank { obj.optStr("user_email") }
        val name = listOf(obj.optStr("firstName").ifBlank { obj.optStr("first_name") }, obj.optStr("lastName").ifBlank { obj.optStr("last_name") })
            .filter { it.isNotBlank() }
            .joinToString(" ")
        return when {
            name.isNotBlank() && email.isNotBlank() -> "$name · $email"
            email.isNotBlank() -> email
            else -> emailFromToken(accessToken)
        }
    }

    fun emailFromToken(accessToken: String): String {
        val payload = jwtPayload(accessToken) ?: return ""
        return payload.optStr("email").ifBlank { payload.optStr("userEmail") }.ifBlank { payload.optStr("user_email") }
    }

    suspend fun sync(accessToken: String, apiKey: String?): ComposerSync = withContext(Dispatchers.IO) {
        val notes = mutableListOf<String>()
        var token = accessToken
        var listed = fetchListed(token)
        notes += listed.note
        if (listed.code == 401 || listed.code == 403) {
            if (!apiKey.isNullOrBlank()) {
                val (fresh, note) = sessionFromApiKey(apiKey)
                notes += "用已保存的 Cloud Agents 凭证重新换会话：\n$note"
                if (!fresh.isNullOrBlank()) {
                    token = fresh
                    listed = fetchListed(token)
                    notes += listed.note
                }
            }
        }
        val enriched: List<JSONObject>
        val discovered: Discovery
        coroutineScope {
            val enrichJob = async {
                if (listed.code in 200..299 && listed.composers.isNotEmpty()) enrich(token, listed.composers) else listed.composers
            }
            val discoverJob = async { discoverProjects(token) }
            enriched = enrichJob.await()
            discovered = discoverJob.await()
        }
        val patched = enriched.toMutableList()
        val sampleNote = metadataSamples(token, patched)
        val listProjects = listed.projects.filter { it.name.isNotBlank() }.distinctBy { it.name.lowercase() }
        val listProjectOk = discovered.projects.isEmpty() && listProjects.size in 1..15 && !repoShaped(listProjects)
        val projects = if (listProjectOk) listProjects else discovered.projects
        val adoptedApi = if (listProjectOk) "ListBackgroundComposers.projects" else discovered.adoptedApi
        val report = buildString {
            notes.forEachIndexed { index, note ->
                if (index > 0) append("\n")
                append(note)
            }
            append("\n")
            append(projectMetadataReport(patched))
            append("\n")
            append("ListBackgroundComposers.projects HTTP ${listed.code}，条目 ${listed.projects.size}")
            if (listed.projects.size in 1..15 && listed.projects.isNotEmpty()) {
                append("，名称：")
                append(listed.projects.map { it.name }.distinct().joinToString("、"))
            }
            append(if (listProjectOk) "，采用" else "，未采用")
            append("\n")
            append(discovered.note)
            if (sampleNote.isNotBlank()) {
                append("\n")
                append(sampleNote)
            }
        }
        ComposerSync(patched, projects, report.trim(), listed.code, adoptedApi)
    }

    private data class ProbeSpec(val name: String, val url: String, val web: Boolean, val adopt: Boolean)

    private data class Discovery(val projects: List<ProjectRef>, val adoptedApi: String, val note: String)

    private suspend fun discoverProjects(accessToken: String): Discovery = coroutineScope {
        val specs = projectProbes()
        val gate = Semaphore(6)
        val hits = specs.map { spec ->
            async(Dispatchers.IO) {
                gate.withPermit { probeOne(accessToken, spec) }
            }
        }.awaitAll()
        val chosen = hits
            .filter { it.adopt && it.code in 200..299 && it.projects.size in 1..15 && !repoShaped(it.projects) }
            .minWithOrNull(compareBy({ kotlin.math.abs(it.projects.size - 5) }, { if (it.name.endsWith("ListProjects")) 0 else 1 }))
        val note = hits.joinToString("\n") { it.line(chosen?.name == it.name) }
        Discovery(chosen?.projects.orEmpty(), chosen?.name.orEmpty(), note)
    }

    private fun projectProbes(): List<ProbeSpec> {
        fun api(service: String, method: String, adopt: Boolean) = ProbeSpec(
            "$service/$method",
            "$API2/aiserver.v1.$service/$method",
            false,
            adopt
        )
        fun web(path: String, adopt: Boolean) = ProbeSpec(path, "$WEB$path", true, adopt)
        return listOf(
            api("BackgroundComposerService", "ListProjects", true),
            api("BackgroundComposerService", "ListAgentProjects", true),
            api("BackgroundComposerService", "ListGlassProjects", true),
            api("BackgroundComposerService", "GetProjects", true),
            api("BackgroundComposerService", "ListBackgroundComposerProjects", true),
            api("DashboardService", "ListProjects", true),
            api("DashboardService", "ListTeamProjects", true),
            api("DashboardService", "GetUserProjects", true),
            api("AiService", "ListProjects", true),
            api("ProjectService", "ListProjects", true),
            api("GlassService", "ListProjects", true),
            web("/api/background-composer/list-projects", true),
            web("/api/dashboard/list-projects", true),
            api("BackgroundComposerService", "ListEnvironments", false),
            web("/api/background-composer/list-environments", false),
            api("DashboardService", "GetTeamProjectsSettings", false),
            api("DashboardService", "GetTeams", false),
            api("DashboardService", "ListBackgroundComposerSecrets", false),
            api("AutomationsService", "ListAutomations", false)
        )
    }

    private data class ProbeHit(
        val name: String,
        val code: Int,
        val rawCount: Int,
        val projects: List<ProjectRef>,
        val adopt: Boolean,
        val html: Boolean,
        val failure: String
    ) {
        fun line(adopted: Boolean): String {
            if (failure.isNotBlank()) return "$name $failure"
            if (html) return "$name HTTP $code，HTML，不是接口"
            val names = projects.map { it.name }.distinct()
            val detail = when {
                rawCount in 1..15 && names.isNotEmpty() -> "，名称：${names.joinToString("、")}"
                else -> ""
            }
            val used = when {
                adopted -> "，采用"
                code in 200..299 -> "，未采用"
                else -> ""
            }
            return "$name HTTP $code，条目 $rawCount$detail$used"
        }
    }

    private fun probeOne(accessToken: String, spec: ProbeSpec): ProbeHit {
        val body = JSONObject().put("n", 100).put("includeArchived", true).put("includeStatus", true)
        val res = try {
            post(spec.url, accessToken, body, 12_000, if (spec.web) sessionCookie(accessToken) else emptyMap())
        } catch (e: IOException) {
            return ProbeHit(spec.name, 0, 0, emptyList(), spec.adopt, false, "网络失败：${e.message ?: "网络错误"}")
        }
        val html = res.body.contains("<html", true) || res.body.contains("[text/html]", true) || res.body.contains("<!DOCTYPE", true)
        val parsed = if (res.code in 200..299 && !html) parseCatalog(res.body) else CatalogParse(0, emptyList())
        return ProbeHit(spec.name, res.code, parsed.rawCount, parsed.projects, spec.adopt, html, "")
    }

    private data class CatalogParse(val rawCount: Int, val projects: List<ProjectRef>)

    private fun parseCatalog(body: String): CatalogParse {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return CatalogParse(0, emptyList())
        val found = mutableListOf<Pair<Int, List<ProjectRef>>>()
        fun consider(arr: JSONArray) {
            val objects = jsonArray(arr)
            if (objects.isEmpty()) return
            val projects = objects.mapNotNull { toProject(it) }.distinctBy { it.name.lowercase() }
            found += objects.size to projects
        }
        fun walk(value: Any?, depth: Int) {
            if (depth > 4) return
            when (value) {
                is JSONArray -> {
                    if (value.length() > 0 && value.optJSONObject(0) != null) consider(value)
                    if (value.length() <= 20) {
                        for (i in 0 until value.length()) walk(value.opt(i), depth + 1)
                    }
                }
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) walk(value.opt(keys.next()), depth + 1)
                }
            }
        }
        try {
            if (trimmed.startsWith("[")) walk(JSONArray(trimmed), 0) else walk(JSONObject(trimmed), 0)
        } catch (_: Exception) {
            return CatalogParse(0, emptyList())
        }
        val chosen = found
            .filter { it.second.size in 1..15 && it.first <= 15 }
            .minByOrNull { kotlin.math.abs(it.second.size - 5) }
            ?: found.maxByOrNull { it.first }
        return CatalogParse(chosen?.first ?: 0, chosen?.second.orEmpty())
    }

    private fun toProject(obj: JSONObject): ProjectRef? {
        if (looksLikeSession(obj)) return null
        val name = obj.optStr("displayName").ifBlank { obj.optStr("name") }
            .ifBlank { obj.optStr("title") }.ifBlank { obj.optStr("projectName") }.trim()
        if (name.isBlank() || name.length > 80 || name.startsWith("http") || name.startsWith("{")) return null
        val id = obj.optStr("id").ifBlank { obj.optStr("projectId") }.ifBlank { obj.optStr("project_id") }
            .ifBlank { obj.optStr("publicId") }.ifBlank { name }
        val repo = obj.optStr("repoUrl").ifBlank { obj.optStr("repository") }.ifBlank { obj.optStr("repo_url") }
        return ProjectRef(name, id, repo)
    }

    private fun looksLikeSession(obj: JSONObject): Boolean {
        val bc = obj.optStr("bcId").ifBlank { obj.optStr("bc_id") }.ifBlank { obj.optStr("composerId") }
        if (bc.isNotBlank()) return true
        if (obj.has("projectMetadata") || obj.has("project_metadata")) return true
        if (obj.has("environmentPublicId") && obj.has("repoUrl")) return true
        return false
    }

    private fun repoShaped(items: List<ProjectRef>): Boolean {
        if (items.isEmpty()) return false
        return items.all { ref ->
            val repo = ref.repoUrl
            repo.isNotBlank() && (shortRepo(repo).equals(ref.name, true) || ref.name.contains("/"))
        }
    }

    private fun metadataSamples(accessToken: String, items: MutableList<JSONObject>): String {
        if (items.isEmpty()) return "没有会话，无法写出 projectMetadata"
        return items.take(2).mapIndexed { index, obj ->
            buildString {
                append("会话${index + 1} projectMetadata ")
                append(rawProjectMetadata(obj))
                if (!jsonHasProject(obj)) {
                    val id = composerId(obj)
                    val detail = detailMetadata(accessToken, id)
                    append("\n会话${index + 1} 详情 GetBackgroundComposerInfo ")
                    append(detail.note)
                    val extra = detail.obj
                    if (extra != null) items[index] = overlay(obj, extra)
                }
            }
        }.joinToString("\n")
    }

    private data class DetailMeta(val note: String, val obj: JSONObject?)

    private fun detailMetadata(accessToken: String, bcId: String): DetailMeta {
        if (bcId.isBlank()) return DetailMeta("没有 bcId", null)
        val body = JSONObject().put("bcId", bcId).put("bc_id", bcId)
        val res = try {
            post("$API2/aiserver.v1.BackgroundComposerService/GetBackgroundComposerInfo", accessToken, body, 12_000)
        } catch (e: IOException) {
            return DetailMeta("HTTP 0，网络失败：${e.message ?: "网络错误"}", null)
        }
        if (res.code !in 200..299) return DetailMeta("HTTP ${res.code}", null)
        val obj = try {
            flatten(JSONObject(res.body))
        } catch (_: Exception) {
            return DetailMeta("HTTP ${res.code}，不是 JSON", null)
        }
        return DetailMeta("HTTP ${res.code} projectMetadata ${rawProjectMetadata(obj)}", obj)
    }

    private fun sessionCookie(accessToken: String): Map<String, String> {
        val userId = jwtPayload(accessToken)?.optStr("sub").orEmpty().ifBlank {
            jwtPayload(accessToken)?.optStr("userId").orEmpty()
        }
        val cookieValue = if (userId.isNotBlank()) "$userId::$accessToken" else accessToken
        return mapOf(
            "Origin" to WEB,
            "Referer" to "$WEB/",
            "Cookie" to "WorkosCursorSessionToken=${enc(cookieValue)}"
        )
    }

    private suspend fun enrich(accessToken: String, items: List<JSONObject>): List<JSONObject> = coroutineScope {
        val stop = AtomicBoolean(false)
        val gate = Semaphore(4)
        val pendingIds = items.filter { !jsonHasProject(it) }.map { composerId(it) }.filter { it.isNotBlank() }.take(80).toSet()
        items.map { obj ->
            async(Dispatchers.IO) {
                val id = composerId(obj)
                if (id.isBlank() || id !in pendingIds || stop.get()) return@async obj
                gate.withPermit {
                    if (stop.get()) return@withPermit obj
                    try {
                        val extra = fetchDetail(accessToken, id) ?: return@withPermit obj
                        overlay(obj, extra)
                    } catch (_: RateLimited) {
                        stop.set(true)
                        obj
                    }
                }
            }
        }.awaitAll()
    }

    private data class Listed(
        val composers: List<JSONObject>,
        val projects: List<ProjectRef>,
        val code: Int,
        val note: String
    )

    private fun fetchListed(accessToken: String): Listed {
        val merged = LinkedHashMap<String, JSONObject>()
        val projects = mutableListOf<ProjectRef>()
        var lastCode = 0
        var lastBody = ""
        var cursor: String? = null
        val seenCursors = mutableSetOf<String>()
        var page = 0
        while (page < 20) {
            page += 1
            val body = JSONObject().put("n", 100).put("includeStatus", true).put("includeArchived", true)
            if (!cursor.isNullOrBlank()) body.put("cursor", cursor)
            val res = try {
                post(
                    "$API2/aiserver.v1.BackgroundComposerService/ListBackgroundComposers",
                    accessToken,
                    body,
                    45_000
                )
            } catch (e: IOException) {
                return Listed(emptyList(), emptyList(), 0, "ListBackgroundComposers 网络失败：${e.message ?: "网络错误"}")
            }
            lastCode = res.code
            lastBody = res.body
            if (res.code !in 200..299) break
            val parsed = parseList(res.body)
            parsed.first.forEach { obj ->
                val id = composerId(obj).ifBlank { "row-${merged.size}" }
                val existing = merged[id]
                merged[id] = if (existing == null) obj else overlay(existing, obj)
            }
            parsed.second.forEach { item ->
                if (projects.none { (item.id.isNotBlank() && it.id == item.id) || it.name.equals(item.name, true) }) {
                    projects += item
                }
            }
            val next = parsed.third
            if (next.isNullOrBlank() || next == cursor || !seenCursors.add(next)) break
            if (parsed.first.isEmpty()) break
            cursor = next
        }
        val web = fetchWebList(accessToken, 100)
        if (web != null && web.code in 200..299) {
            web.composers.forEach { obj ->
                val id = composerId(obj).ifBlank { "web-${merged.size}" }
                val existing = merged[id]
                merged[id] = if (existing == null) obj else overlay(existing, obj)
            }
            web.projects.forEach { item ->
                if (projects.none { (item.id.isNotBlank() && it.id == item.id) || it.name.equals(item.name, true) }) {
                    projects += item
                }
            }
        }
        val note = buildString {
            appendLine("ListBackgroundComposers")
            appendLine("HTTP $lastCode")
            appendLine("composer ${merged.size} 条")
            append(rootShape(lastBody))
        }
        return Listed(merged.values.toList(), projects, lastCode, note)
    }

    private data class WebList(val composers: List<JSONObject>, val projects: List<ProjectRef>, val code: Int, val raw: String)

    private fun fetchWebList(accessToken: String, n: Int): WebList? {
        val userId = jwtPayload(accessToken)?.optStr("sub").orEmpty().ifBlank {
            jwtPayload(accessToken)?.optStr("userId").orEmpty()
        }
        val cookieValue = if (userId.isNotBlank()) "$userId::$accessToken" else accessToken
        val body = JSONObject()
            .put("n", n)
            .put("include_status", true)
            .put("includeStatus", true)
            .put("include_archived", true)
            .put("includeArchived", true)
        val res = try {
            post(
                "$WEB/api/background-composer/list",
                accessToken,
                body,
                30_000,
                extraHeaders = mapOf(
                    "Origin" to WEB,
                    "Referer" to "$WEB/",
                    "Cookie" to "WorkosCursorSessionToken=${enc(cookieValue)}"
                )
            )
        } catch (_: IOException) {
            return null
        }
        val parsed = parseList(res.body)
        return WebList(parsed.first, parsed.second, res.code, res.body)
    }

    private fun fetchDetail(accessToken: String, bcId: String): JSONObject? {
        val body = JSONObject().put("bcId", bcId).put("bc_id", bcId)
        val primary = try {
            post(
                "$API2/aiserver.v1.BackgroundComposerService/GetBackgroundComposerInfo",
                accessToken,
                body,
                25_000
            )
        } catch (_: IOException) {
            null
        }
        if (primary != null && primary.code == 429) throw RateLimited()
        if (primary != null && primary.code in 200..299) {
            return try {
                flatten(JSONObject(primary.body))
            } catch (_: Exception) {
                null
            }
        }
        val webBody = JSONObject().put("bcId", bcId).put("n", 1).put("includeDiff", false).put("includeTeamWide", false)
        val web = try {
            post("$WEB/api/background-composer/get-detailed-composer", accessToken, webBody, 25_000, mapOf("Origin" to WEB, "Referer" to "$WEB/"))
        } catch (_: IOException) {
            null
        }
        if (web != null && web.code == 429) throw RateLimited()
        if (web == null || web.code !in 200..299) return null
        return try {
            flatten(JSONObject(web.body))
        } catch (_: Exception) {
            null
        }
    }

    private fun parseList(body: String): Triple<List<JSONObject>, List<ProjectRef>, String?> {
        val trimmed = body.trim()
        if (trimmed.startsWith("[")) return Triple(jsonArray(JSONArray(trimmed)), emptyList(), null)
        if (!trimmed.startsWith("{")) return Triple(emptyList(), emptyList(), null)
        val root = try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            return Triple(emptyList(), emptyList(), null)
        }
        val composers = findComposers(root)
        val projects = mutableListOf<ProjectRef>()
        val rootKeys = root.keys()
        while (rootKeys.hasNext()) {
            val key = rootKeys.next()
            if (!key.contains("project", true) || key.contains("environment", true)) continue
            val arr = root.optJSONArray(key) ?: continue
            if (arr.length() !in 1..12) continue
            jsonArray(arr).forEach { obj ->
                if (looksLikeSession(obj)) return@forEach
                val name = obj.optStr("displayName").ifBlank { obj.optStr("name") }.ifBlank { obj.optStr("projectName") }.trim()
                if (name.isBlank() || name.length > 80) return@forEach
                val id = obj.optStr("id").ifBlank { obj.optStr("projectId") }.ifBlank { obj.optStr("project_id") }.ifBlank { name }
                if (projects.none { it.name.equals(name, true) }) {
                    projects += ProjectRef(name, id, obj.optStr("repoUrl").ifBlank { obj.optStr("repository") })
                }
            }
        }
        val next = root.optStr("nextCursor").ifBlank { root.optStr("nextPageToken") }.ifBlank { root.optStr("cursor") }
            .takeIf { it.isNotBlank() && !it.equals("null", true) }
        return Triple(composers, projects, next)
    }

    private fun rootShape(body: String): String {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return "顶层不是对象"
        val root = try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            return "顶层不是 JSON"
        }
        val parts = mutableListOf<String>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = root.opt(key)
            val count = when (value) {
                is JSONArray -> value.length()
                is JSONObject -> value.length()
                else -> -1
            }
            parts += if (count >= 0) "$key:$count" else key
        }
        return "顶层 " + parts.joinToString(" ")
    }

    private fun findComposers(root: JSONObject): List<JSONObject> {
        val direct = firstArray(root, listOf("composers", "backgroundComposers", "background_composers", "items", "results"))
        if (direct.isNotEmpty()) return direct
        val found = mutableListOf<JSONObject>()
        walkForComposers(root, 0, found)
        return found
    }

    private fun walkForComposers(obj: JSONObject, depth: Int, out: MutableList<JSONObject>) {
        if (depth > 3 || out.isNotEmpty()) return
        val keys = obj.keys()
        while (keys.hasNext()) {
            if (out.isNotEmpty()) return
            when (val value = obj.opt(keys.next())) {
                is JSONArray -> {
                    val items = jsonArray(value)
                    if (items.any { composerId(it).isNotBlank() || it.has("repoUrl") || it.has("repo_url") || it.has("repository") }) {
                        out += items
                    }
                }
                is JSONObject -> walkForComposers(value, depth + 1, out)
            }
        }
    }

    private fun mergeObjects(primary: List<JSONObject>, extra: List<JSONObject>): List<JSONObject> {
        val byId = LinkedHashMap<String, JSONObject>()
        primary.forEach { obj ->
            val id = composerId(obj)
            if (id.isNotBlank()) byId[id] = obj
        }
        extra.forEach { obj ->
            val id = composerId(obj)
            if (id.isBlank()) return@forEach
            val existing = byId[id]
            byId[id] = if (existing == null) obj else overlay(existing, obj)
        }
        if (byId.isEmpty()) return primary.ifEmpty { extra }
        return byId.values.toList()
    }

    private fun pollPost(uuid: String, verifier: String): CursorApi.HttpResult {
        val body = JSONObject().put("uuid", uuid).put("verifier", verifier)
        return post("$API2/auth/poll", null, body, 20_000)
    }

    private fun pollGet(uuid: String, verifier: String): CursorApi.HttpResult {
        val url = "$API2/auth/poll?uuid=${enc(uuid)}&verifier=${enc(verifier)}"
        return open("GET", url, null, 20_000, emptyMap())
    }

    private fun tokensOf(body: String): Tokens? {
        val obj = try {
            JSONObject(body)
        } catch (_: Exception) {
            return null
        }
        val access = obj.optStr("accessToken").ifBlank { obj.optStr("access_token") }
        if (access.isBlank()) return null
        val refresh = obj.optStr("refreshToken").ifBlank { obj.optStr("refresh_token") }
        return Tokens(access, refresh)
    }

    private fun isPending(code: Int, body: String): Boolean {
        val trimmed = body.trim().trim('"')
        if (trimmed == "Not found") return true
        if (code == 404 && trimmed.isEmpty()) return true
        val obj = try {
            JSONObject(body)
        } catch (_: Exception) {
            null
        } ?: return false
        val msg = obj.optStr("message").ifBlank { obj.optStr("error") }
        return code == 404 && msg.equals("Not found", true)
    }

    private fun pollFailure(code: Int, body: String): String {
        val detail = snippet(body)
        return if (detail.isBlank()) "登录失败（HTTP $code）。请在浏览器完成 Cursor 账号确认后重试。"
        else "登录失败（HTTP $code）：$detail"
    }

    private fun post(
        url: String,
        bearer: String?,
        body: JSONObject,
        readMs: Int,
        extraHeaders: Map<String, String> = emptyMap()
    ): CursorApi.HttpResult {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = readMs
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("User-Agent", "CursorMobile-Android/9")
        conn.instanceFollowRedirects = false
        if (url.contains("/aiserver.v1.")) {
            conn.setRequestProperty("Connect-Protocol-Version", "1")
            conn.setRequestProperty("x-cursor-client-type", "sdk")
            conn.setRequestProperty("x-cursor-client-version", "1.0.34")
        }
        if (!bearer.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $bearer")
        extraHeaders.forEach { (key, value) -> conn.setRequestProperty(key, value) }
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            finish(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(
        method: String,
        url: String,
        bearer: String?,
        readMs: Int,
        extraHeaders: Map<String, String>
    ): CursorApi.HttpResult {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = readMs
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("User-Agent", "CursorMobile-Android/9")
        conn.instanceFollowRedirects = false
        if (!bearer.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $bearer")
        extraHeaders.forEach { (key, value) -> conn.setRequestProperty(key, value) }
        return try {
            finish(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun finish(conn: HttpURLConnection): CursorApi.HttpResult {
        val code = try {
            conn.responseCode
        } catch (e: IOException) {
            throw IOException("无法连接 Cursor，请检查网络（${e.message ?: "网络错误"}）", e)
        }
        val ok = code in 200..299
        val stream = if (ok) conn.inputStream else conn.errorStream
        val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        val type = conn.contentType.orEmpty()
        val location = if (code in 300..399) conn.getHeaderField("Location").orEmpty() else ""
        val body = buildString {
            if (location.isNotBlank()) append("redirect ").append(location).append(' ')
            if (type.isNotBlank() && !raw.trim().startsWith("{") && !raw.trim().startsWith("[")) {
                append('[').append(type).append("] ")
            }
            append(raw)
        }
        return CursorApi.HttpResult(code, body)
    }

    private fun overlay(base: JSONObject, extra: JSONObject): JSONObject {
        val out = JSONObject(base.toString())
        val flat = flatten(extra)
        val keys = flat.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (flat.isNull(key)) continue
            val value = flat.get(key)
            if (value is String && value.isBlank()) continue
            out.put(key, value)
        }
        return out
    }

    private fun flatten(obj: JSONObject): JSONObject {
        val out = JSONObject(obj.toString())
        listOf("composer", "backgroundComposer", "detailedComposer", "info", "result", "data").forEach { key ->
            val nested = obj.optJSONObject(key) ?: return@forEach
            val nestedKeys = nested.keys()
            while (nestedKeys.hasNext()) {
                val child = nestedKeys.next()
                if (!nested.isNull(child)) out.put(child, nested.get(child))
            }
        }
        return out
    }

    private fun firstArray(root: JSONObject, keys: List<String>): List<JSONObject> {
        val arr = keys.firstNotNullOfOrNull { root.optJSONArray(it) } ?: return emptyList()
        return jsonArray(arr)
    }

    private fun jsonArray(arr: JSONArray?): List<JSONObject> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(it) }
        }
    }

    private fun composerId(obj: JSONObject): String =
        obj.optStr("bcId").ifBlank { obj.optStr("bc_id") }.ifBlank { obj.optStr("composerId") }.ifBlank { obj.optStr("id") }

    private fun jwtPayload(token: String): JSONObject? {
        val parts = token.split('.')
        if (parts.size < 2) return null
        return try {
            val bytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun b64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun snippet(body: String): String =
        body.trim().replace("\\s+".toRegex(), " ").let { if (it.isBlank()) "空响应" else it.take(420) }

    private fun summarize(res: CursorApi.HttpResult): String = "HTTP ${res.code} ${snippet(res.body)}"

    private fun readApiKey(body: String): String? {
        val obj = try {
            JSONObject(body)
        } catch (_: Exception) {
            return null
        }
        val key = obj.optStr("apiKey").ifBlank { obj.optStr("api_key") }
        return key.takeIf { it.isNotBlank() }
    }

    private fun jsonKeys(obj: JSONObject): String {
        val names = mutableListOf<String>()
        val keys = obj.keys()
        while (keys.hasNext() && names.size < 40) names += keys.next()
        return names.joinToString(", ")
    }
}
