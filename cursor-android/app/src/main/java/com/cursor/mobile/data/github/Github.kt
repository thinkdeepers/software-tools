package com.cursor.mobile.data.github

import com.cursor.mobile.data.api.ApiJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class PullRef(val owner: String, val repo: String, val number: Int)

fun parsePullRequestUrl(raw: String): PullRef? {
    val match = Regex("""github\.com/([^/]+)/([^/]+)/pull/(\d+)""")
        .find(raw.trim()) ?: return null
    return PullRef(match.groupValues[1], match.groupValues[2], match.groupValues[3].toInt())
}

@Serializable
data class GhUser(val login: String? = null)

@Serializable
data class GhRef(val ref: String? = null, val sha: String? = null)

@Serializable
data class GhPull(
    val number: Int = 0,
    val title: String = "",
    val body: String? = null,
    val state: String = "",
    val draft: Boolean = false,
    val mergeable: Boolean? = null,
    @SerialName("mergeable_state") val mergeableState: String? = null,
    val user: GhUser? = null,
    val head: GhRef? = null,
    val base: GhRef? = null,
    val commits: Int = 0,
    @SerialName("changed_files") val changedFiles: Int = 0,
    val additions: Int = 0,
    val deletions: Int = 0,
    @SerialName("html_url") val htmlUrl: String? = null
)

@Serializable
data class GhFile(
    val filename: String,
    val status: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    val patch: String? = null
)

@Serializable
data class GhCommitDetail(val message: String = "")

@Serializable
data class GhCommit(
    val sha: String,
    val commit: GhCommitDetail = GhCommitDetail(),
    @SerialName("html_url") val htmlUrl: String? = null
)

@Serializable
data class GhStatusItem(
    val context: String? = null,
    val state: String? = null,
    val description: String? = null,
    @SerialName("target_url") val targetUrl: String? = null
)

@Serializable
data class GhCombinedStatus(
    val state: String? = null,
    val statuses: List<GhStatusItem> = emptyList()
)

@Serializable
data class GhComment(
    val id: Long = 0,
    val body: String = "",
    val user: GhUser? = null
)

data class PullReview(
    val pull: GhPull,
    val files: List<GhFile>,
    val commits: List<GhCommit>,
    val status: GhCombinedStatus?,
    val comments: List<GhComment>
)

class GithubClient(
    private val tokenProvider: () -> String?
) {
    private val http = OkHttpClient.Builder()
        .callTimeout(40, TimeUnit.SECONDS)
        .build()
    private val json = ApiJson.instance

    fun load(url: String): PullReview {
        val ref = parsePullRequestUrl(url) ?: error("无法识别的 Pull Request 地址")
        val pull = get("repos/${ref.owner}/${ref.repo}/pulls/${ref.number}", GhPull.serializer())
        val files = getList(
            "repos/${ref.owner}/${ref.repo}/pulls/${ref.number}/files?per_page=50",
            GhFile.serializer()
        )
        val commits = getList(
            "repos/${ref.owner}/${ref.repo}/pulls/${ref.number}/commits?per_page=30",
            GhCommit.serializer()
        )
        val comments = runCatching {
            getList(
                "repos/${ref.owner}/${ref.repo}/issues/${ref.number}/comments?per_page=30",
                GhComment.serializer()
            )
        }.getOrDefault(emptyList())
        val status = pull.head?.sha?.let { sha ->
            runCatching {
                get("repos/${ref.owner}/${ref.repo}/commits/$sha/status", GhCombinedStatus.serializer())
            }.getOrNull()
        }
        return PullReview(pull, files, commits, status, comments)
    }

    fun mergeSquash(url: String) {
        val ref = parsePullRequestUrl(url) ?: error("无法识别的 Pull Request 地址")
        send(
            "PUT",
            "repos/${ref.owner}/${ref.repo}/pulls/${ref.number}/merge",
            """{"merge_method":"squash"}"""
        )
    }

    fun close(url: String) {
        val ref = parsePullRequestUrl(url) ?: error("无法识别的 Pull Request 地址")
        send(
            "PATCH",
            "repos/${ref.owner}/${ref.repo}/pulls/${ref.number}",
            """{"state":"closed"}"""
        )
    }

    fun comment(url: String, body: String) {
        val ref = parsePullRequestUrl(url) ?: error("无法识别的 Pull Request 地址")
        val payload = json.encodeToString(
            kotlinx.serialization.serializer<Map<String, String>>(),
            mapOf("body" to body)
        )
        send("POST", "repos/${ref.owner}/${ref.repo}/issues/${ref.number}/comments", payload)
    }

    private fun send(method: String, path: String, payload: String) {
        val request = base(path)
            .method(method, payload.toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error(response.body?.string()?.take(300) ?: "GitHub HTTP ${response.code}")
            }
        }
    }

    private fun <T> get(path: String, serializer: kotlinx.serialization.KSerializer<T>): T {
        val request = base(path).get().build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(text.take(300).ifBlank { "GitHub HTTP ${response.code}" })
            return json.decodeFromString(serializer, text)
        }
    }

    private fun <T> getList(path: String, serializer: kotlinx.serialization.KSerializer<T>): List<T> {
        val request = base(path).get().build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(text.take(300).ifBlank { "GitHub HTTP ${response.code}" })
            val element = json.parseToJsonElement(text)
            val array = element as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            return array.map { json.decodeFromJsonElement(serializer, it) }
        }
    }

    private fun base(path: String): Request.Builder {
        val builder = Request.Builder()
            .url("https://api.github.com/$path")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
        tokenProvider()?.takeIf { it.isNotBlank() }?.let {
            builder.header("Authorization", "Bearer $it")
        }
        return builder
    }

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}
