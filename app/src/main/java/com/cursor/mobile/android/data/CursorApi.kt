package com.cursor.mobile.android.data

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object CursorApi {
    private const val BASE = "https://api.cursor.com"

    class Unauthorized(message: String) : Exception(message)
    class ApiException(message: String) : Exception(message)

    data class HttpResult(val code: Int, val body: String)
    data class RunStart(val runId: String, val modelSent: Boolean)

    fun verifyKey(apiKey: String): String? {
        val res = try {
            get("/v1/agents?limit=1", apiKey)
        } catch (e: IOException) {
            return "无法连接 api.cursor.com，请检查网络（${e.message ?: "网络错误"}）"
        }
        if (res.code in 200..299) {
            return try {
                JSONObject(res.body)
                null
            } catch (_: Exception) {
                "校验失败：Cursor 没有返回有效 JSON"
            }
        }
        return explain(res.code, res.body)
    }

    fun listAgentPage(apiKey: String, version: String, cursor: String?, arrayKey: String, extra: String = ""): Pair<List<JSONObject>, String?> {
        val path = buildString {
            append("/").append(version).append("/agents?limit=100")
            if (extra.isNotBlank()) append('&').append(extra)
            if (!cursor.isNullOrBlank()) append("&cursor=").append(URLEncoder.encode(cursor, Charsets.UTF_8.name()))
        }
        val res = get(path, apiKey)
        if (res.code == 401 || res.code == 403) throw Unauthorized(explain(res.code, res.body))
        if (res.code !in 200..299) throw ApiException(explain(res.code, res.body))
        val json = JSONObject(res.body)
        val arr = json.optJSONArray(arrayKey) ?: JSONArray()
        val items = buildList {
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(it) }
        }
        val next = json.optStr("nextCursor").takeIf { it.isNotBlank() }
        return items to next
    }

    fun getJson(apiKey: String, path: String, readMs: Int = 30_000): JSONObject {
        val res = get(path, apiKey, readMs = readMs)
        if (res.code == 401 || res.code == 403) throw Unauthorized(explain(res.code, res.body))
        if (res.code !in 200..299) throw ApiException(explain(res.code, res.body))
        return JSONObject(res.body)
    }

    fun getOptional(apiKey: String, path: String, readMs: Int = 30_000): JSONObject? {
        val res = try {
            get(path, apiKey, readMs = readMs)
        } catch (_: IOException) {
            return null
        }
        if (res.code == 401 || res.code == 403) throw Unauthorized(explain(res.code, res.body))
        if (res.code !in 200..299) return null
        return try {
            JSONObject(res.body)
        } catch (_: Exception) {
            null
        }
    }

    fun listModels(apiKey: String): List<RemoteModel> {
        val res = get("/v1/models", apiKey)
        if (res.code == 401 || res.code == 403) throw Unauthorized(explain(res.code, res.body))
        if (res.code !in 200..299) return emptyList()
        val items = try {
            JSONObject(res.body).optJSONArray("items")
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return buildList {
            for (i in 0 until items.length()) {
                val obj = items.optJSONObject(i) ?: continue
                val id = obj.optStr("id")
                if (id.isBlank()) continue
                add(
                    RemoteModel(
                        id = id,
                        displayName = obj.optStr("displayName").ifBlank { id },
                        aliases = obj.optJSONArray("aliases").toStringList(),
                        parameters = obj.optJSONArray("parameters").toParams(),
                        variants = obj.optJSONArray("variants").toVariants()
                    )
                )
            }
        }
    }

    fun accountLabel(apiKey: String): String {
        val res = get("/v1/me", apiKey)
        if (res.code == 401 || res.code == 403) throw Unauthorized(explain(res.code, res.body))
        if (res.code !in 200..299) return ""
        val obj = try {
            JSONObject(res.body)
        } catch (_: Exception) {
            return ""
        }
        val email = obj.optStr("userEmail")
        val name = listOf(obj.optStr("userFirstName"), obj.optStr("userLastName"))
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val keyName = obj.optStr("apiKeyName")
        return when {
            email.isNotBlank() && name.isNotBlank() -> "$name · $email"
            email.isNotBlank() -> email
            else -> keyName
        }
    }

    fun listRepos(apiKey: String): List<RepoRef> {
        val v1 = collectPages(apiKey, "/v1/repositories", arrayKeys = listOf("items"), readMs = 60_000)
        if (v1.isNotEmpty()) {
            return v1.mapNotNull { obj ->
                val url = obj.optStr("url").ifBlank { obj.optStr("repository") }
                if (url.isBlank()) null else RepoRef(shortRepo(url), obj.optStr("defaultBranch").ifBlank { "main" }, url)
            }
        }
        val v0 = collectPages(apiKey, "/v0/repositories", arrayKeys = listOf("repositories", "items"), readMs = 60_000)
        return v0.mapNotNull { obj ->
            val url = obj.optStr("repository").ifBlank { obj.optStr("url") }.ifBlank {
                val owner = obj.optStr("owner")
                val name = obj.optStr("name")
                if (owner.isNotBlank() && name.isNotBlank()) "https://github.com/$owner/$name" else ""
            }
            if (url.isBlank()) null else RepoRef(shortRepo(url), obj.optStr("defaultBranch").ifBlank { "main" }, url)
        }
    }

    fun listProjects(apiKey: String): List<ProjectRef> {
        val me = try {
            get("/v1/me", apiKey)
        } catch (_: IOException) {
            return emptyList()
        }
        if (me.code == 401 || me.code == 403) throw Unauthorized(explain(me.code, me.body))
        if (me.code !in 200..299) return emptyList()
        return parseNamedItems(me.body)
    }

    fun createAgent(apiKey: String, request: AgentRequest, selection: ModelSelection): JSONObject {
        val body = JSONObject()
        body.put("prompt", JSONObject().put("text", request.prompt))
        body.put("model", selection.toJson())
        if (request.scope == WorkScope.PROJECT) {
            if (request.projectId.isNotBlank()) body.put("projectId", request.projectId)
            val project = JSONObject()
            if (request.projectId.isNotBlank()) project.put("id", request.projectId)
            if (request.projectName.isNotBlank()) project.put("name", request.projectName)
            if (project.length() > 0) body.put("project", project)
            val repoUrl = normalizeRepoUrl(request.repo)
            if (repoUrl.isNotBlank()) {
                val repo = JSONObject().put("url", repoUrl)
                if (request.branch.isNotBlank()) repo.put("startingRef", request.branch)
                body.put("repos", JSONArray().put(repo))
            }
        } else {
            val repoUrl = normalizeRepoUrl(request.repo)
            if (repoUrl.isNotBlank()) {
                val repo = JSONObject().put("url", repoUrl)
                if (request.branch.isNotBlank()) repo.put("startingRef", request.branch)
                body.put("repos", JSONArray().put(repo))
            }
        }
        var res = post("/v1/agents", apiKey, body)
        if (res.code == 400 && body.has("project")) {
            body.remove("project")
            body.remove("projectId")
            val retry = post("/v1/agents", apiKey, body)
            if (retry.code == 401 || retry.code == 403) throw Unauthorized(explain(retry.code, retry.body))
            if (retry.code in 200..299) res = retry
        }
        if (res.code == 401 || res.code == 403) throw Unauthorized(explain(res.code, res.body))
        if (res.code !in 200..299) throw ApiException(explain(res.code, res.body))
        return JSONObject(res.body)
    }

    fun startRun(apiKey: String, agentId: String, text: String, selection: ModelSelection): RunStart {
        val path = "/v1/agents/${Uri.encode(agentId)}/runs"
        val body = JSONObject()
            .put("prompt", JSONObject().put("text", text))
            .put("model", selection.toJson())
        var res = post(path, apiKey, body)
        if (res.code == 401 || res.code == 403) throw Unauthorized(explain(res.code, res.body))
        var modelSent = true
        if (res.code == 400 && modelRejected(res.body)) {
            body.remove("model")
            val retry = post(path, apiKey, body)
            if (retry.code == 401 || retry.code == 403) throw Unauthorized(explain(retry.code, retry.body))
            if (retry.code !in 200..299) throw ApiException(explain(retry.code, retry.body))
            res = retry
            modelSent = false
        } else if (res.code !in 200..299) {
            throw ApiException(explain(res.code, res.body))
        }
        val json = JSONObject(res.body)
        val runId = json.optJSONObject("run")?.optStr("id").orEmpty().ifBlank { json.optStr("id") }
        if (runId.isBlank()) throw ApiException("Cursor 已接受跟随消息，但没有返回 run id")
        return RunStart(runId, modelSent)
    }

    suspend fun collectRunText(
        apiKey: String,
        agentId: String,
        runId: String,
        onText: suspend (String) -> Unit
    ): String {
        val streamed = try {
            readStream(apiKey, agentId, runId, onText)
        } catch (e: Unauthorized) {
            throw e
        } catch (e: ApiException) {
            throw e
        } catch (_: Exception) {
            ""
        }
        if (streamed.isNotBlank()) return streamed
        return pollRun(apiKey, agentId, runId, onText)
    }

    private suspend fun readStream(
        apiKey: String,
        agentId: String,
        runId: String,
        onText: suspend (String) -> Unit
    ): String {
        val path = "/v1/agents/${Uri.encode(agentId)}/runs/${Uri.encode(runId)}/stream"
        val conn = open("GET", path, apiKey, "text/event-stream", 90_000)
        try {
            val code = conn.responseCode
            if (code == 401 || code == 403) {
                val err = readBody(conn, false)
                throw Unauthorized(explain(code, err))
            }
            if (code !in 200..299) return ""
            var event = ""
            val data = StringBuilder()
            var acc = ""
            val reader = conn.inputStream.bufferedReader(Charsets.UTF_8)
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("event:") -> event = line.substringAfter(":").trim()
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.substringAfter(":").trim())
                        }
                        line.isEmpty() && data.isNotEmpty() -> {
                            acc = applyStreamEvent(event.ifBlank { "message" }, data.toString(), acc, onText)
                            event = ""
                            data.clear()
                        }
                    }
                }
            } finally {
                reader.close()
            }
            return acc
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun applyStreamEvent(
        event: String,
        data: String,
        acc: String,
        onText: suspend (String) -> Unit
    ): String {
        val json = try {
            JSONObject(data)
        } catch (_: Exception) {
            return acc
        }
        return when (event) {
            "assistant" -> {
                val next = acc + json.optStr("text")
                onText(next)
                next
            }
            "result" -> {
                val full = json.optStr("text")
                val next = if (full.length >= acc.length && full.isNotBlank()) full else acc
                if (next.isNotBlank()) onText(next)
                next
            }
            "error" -> throw ApiException(json.optStr("message").ifBlank { json.optStr("code").ifBlank { "Cloud Agent 运行失败" } })
            else -> acc
        }
    }

    private suspend fun pollRun(
        apiKey: String,
        agentId: String,
        runId: String,
        onText: suspend (String) -> Unit
    ): String {
        val path = "/v1/agents/${Uri.encode(agentId)}/runs/${Uri.encode(runId)}"
        var last = ""
        repeat(20) {
            val res = try {
                get(path, apiKey)
            } catch (_: IOException) {
                null
            }
            if (res != null && (res.code == 401 || res.code == 403)) throw Unauthorized(explain(res.code, res.body))
            if (res != null && res.code in 200..299) {
                val obj = try {
                    JSONObject(res.body)
                } catch (_: Exception) {
                    null
                }
                if (obj != null) {
                    val result = obj.optStr("result")
                    if (result.isNotBlank()) {
                        last = result
                        onText(result)
                    }
                    when (obj.optStr("status")) {
                        "FINISHED" -> return last
                        "ERROR", "CANCELLED", "EXPIRED" -> {
                            if (last.isBlank()) throw ApiException("Cloud Agent 运行结束：${obj.optStr("status")}")
                            return last
                        }
                    }
                }
            }
            delay(2000)
        }
        return last
    }

    private fun get(path: String, apiKey: String, accept: String = "application/json", readMs: Int = 30_000): HttpResult {
        val conn = open("GET", path, apiKey, accept, readMs)
        return try {
            finish(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun post(path: String, apiKey: String, body: JSONObject): HttpResult {
        val conn = open("POST", path, apiKey, "application/json", 45_000)
        return try {
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(bytes) }
            finish(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(method: String, path: String, apiKey: String, accept: String, readMs: Int): HttpURLConnection {
        val conn = (URL(BASE + path).openConnection() as HttpURLConnection)
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = readMs
        conn.setRequestProperty("Authorization", basic(apiKey))
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("User-Agent", "CursorMobile-Android/4")
        return conn
    }

    private fun finish(conn: HttpURLConnection): HttpResult {
        val code = try {
            conn.responseCode
        } catch (e: IOException) {
            throw IOException("无法连接 api.cursor.com，请检查网络（${e.message ?: "网络错误"}）", e)
        }
        val ok = code in 200..299
        return HttpResult(code, readBody(conn, ok))
    }

    private fun readBody(conn: HttpURLConnection, ok: Boolean): String {
        val stream = if (ok) conn.inputStream else conn.errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    }

    private fun basic(apiKey: String): String {
        val token = Base64.encodeToString("$apiKey:".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "Basic $token"
    }

    private fun modelRejected(body: String): Boolean {
        val text = body.lowercase()
        return text.contains("model") || text.contains("params") || text.contains("additional")
    }

    fun explain(code: Int, body: String): String {
        val trimmed = body.trim()
        if (trimmed.startsWith("<")) {
            return when (code) {
                401 -> "登录凭证无效（HTTP 401）。请退出后重新用 Cursor 账号登录。"
                403 -> "当前账号没有访问 Cloud Agents 的权限（HTTP 403）。"
                else -> "Cursor API 错误（HTTP $code）"
            }
        }
        val obj = try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            null
        }
        val apiCode = obj?.optStr("code").orEmpty()
        val message = obj?.optStr("message").orEmpty().ifBlank {
            obj?.optJSONObject("error")?.optStr("message").orEmpty()
        }.ifBlank { trimmed.replace("\\s+".toRegex(), " ").take(180) }
        val detail = listOf(apiCode, message).filter { it.isNotBlank() }.joinToString("：")
        return when (code) {
            401 -> "登录凭证无效（HTTP 401）。请退出后重新用 Cursor 账号登录。" +
                if (detail.isNotBlank()) " $detail" else ""
            403 -> "当前账号没有访问 Cloud Agents 的权限（HTTP 403）。" +
                if (detail.isNotBlank()) " $detail" else ""
            429 -> "请求过于频繁（HTTP 429），请稍后再试。"
            else -> "Cursor API 错误（HTTP $code）" + if (detail.isNotBlank()) "：$detail" else ""
        }
    }

    private fun collectPages(
        apiKey: String,
        path: String,
        arrayKeys: List<String>,
        readMs: Int = 30_000
    ): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        repeat(50) {
            val page = buildString {
                append(path)
                if (!cursor.isNullOrBlank()) {
                    append(if (path.contains('?')) '&' else '?')
                    append("limit=100&cursor=").append(URLEncoder.encode(cursor, Charsets.UTF_8.name()))
                }
            }
            val res = try {
                get(page, apiKey, readMs = readMs)
            } catch (_: IOException) {
                return out
            }
            if (res.code == 401 || res.code == 403) throw Unauthorized(explain(res.code, res.body))
            if (res.code !in 200..299) return out
            val json = try {
                JSONObject(res.body)
            } catch (_: Exception) {
                return out
            }
            val arr = arrayKeys.firstNotNullOfOrNull { json.optJSONArray(it) }
            if (arr != null) {
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out += it }
            }
            val next = json.optStr("nextCursor").ifBlank { json.optStr("nextPageToken") }
            if (next.isBlank() || next == cursor || !seen.add(next)) return out
            cursor = next
        }
        return out
    }
}

internal fun JSONObject.optStr(key: String): String {
    if (!has(key) || isNull(key)) return ""
    return optString(key).takeUnless { it == "null" }.orEmpty()
}

private fun parseNamedItems(objects: List<JSONObject>): List<ProjectRef> = objects.mapNotNull { obj ->
    val name = obj.optStr("displayName").ifBlank { obj.optStr("name") }.ifBlank { obj.optStr("slug") }
    val id = obj.optStr("id").ifBlank { obj.optStr("projectId") }
    val repo = obj.optStr("repository").ifBlank { obj.optStr("repoUrl") }.ifBlank { obj.optJSONObject("repo")?.optStr("url").orEmpty() }
    if (name.isBlank() && id.isBlank()) null else ProjectRef(name.ifBlank { id }, id, repo)
}

private fun parseNamedItems(body: String): List<ProjectRef> {
    val root = try {
        JSONObject(body)
    } catch (_: Exception) {
        return emptyList()
    }
    val arrays = listOf("items", "projects", "data")
        .mapNotNull { key -> root.optJSONArray(key) }
    val objects = arrays.flatMap { arr ->
        buildList {
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(it) }
        }
    }
    return parseNamedItems(objects)
}

private fun ModelSelection.toJson(): JSONObject {
    val model = JSONObject().put("id", id)
    if (params.isNotEmpty()) {
        val arr = JSONArray()
        params.forEach { (pid, value) ->
            arr.put(JSONObject().put("id", pid).put("value", value))
        }
        model.put("params", arr)
    }
    return model
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            val value = optString(i)
            if (value.isNotBlank() && value != "null") add(value)
        }
    }
}

private fun JSONArray?.toParams(): List<ModelParam> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            val obj = optJSONObject(i) ?: continue
            val id = obj.optStr("id")
            val values = buildList {
                val arr = obj.optJSONArray("values") ?: return@buildList
                for (j in 0 until arr.length()) {
                    val value = arr.optJSONObject(j)?.optStr("value").orEmpty()
                    if (value.isNotBlank()) add(value)
                }
            }
            if (id.isNotBlank() && values.isNotEmpty()) add(ModelParam(id, values))
        }
    }
}

private fun JSONArray?.toVariants(): List<ModelVariant> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            val obj = optJSONObject(i) ?: continue
            val params = buildList {
                val arr = obj.optJSONArray("params") ?: return@buildList
                for (j in 0 until arr.length()) {
                    val param = arr.optJSONObject(j) ?: continue
                    val id = param.optStr("id")
                    val value = param.optStr("value")
                    if (id.isNotBlank() && value.isNotBlank()) add(id to value)
                }
            }
            add(ModelVariant(obj.optStr("displayName"), params, obj.optBoolean("isDefault")))
        }
    }
}

internal fun shortRepo(url: String): String {
    val cleaned = url
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("github.com/")
        .trim('/')
        .removeSuffix(".git")
    return cleaned.ifBlank { url }
}

internal fun normalizeRepoUrl(input: String): String {
    val trimmed = input.trim().removeSuffix(".git")
    if (trimmed.isBlank()) return ""
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    if (trimmed.startsWith("github.com/")) return "https://$trimmed"
    return "https://github.com/$trimmed"
}
