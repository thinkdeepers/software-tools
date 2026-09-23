package com.cursor.mobile.data.api

import com.cursor.mobile.data.model.GitBranch
import com.cursor.mobile.data.model.GitInfo
import com.cursor.mobile.data.model.RunStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

sealed class StreamEvent {
    data class Status(val runId: String?, val status: RunStatus) : StreamEvent()
    data class AssistantDelta(val text: String) : StreamEvent()
    data class ThinkingDelta(val text: String) : StreamEvent()
    data class ToolCall(
        val callId: String,
        val name: String,
        val status: String,
        val args: String? = null,
        val result: String? = null
    ) : StreamEvent()
    data class Result(
        val runId: String?,
        val status: RunStatus,
        val text: String?,
        val durationMs: Long?,
        val branches: List<GitBranch>
    ) : StreamEvent()
    data class Error(val code: String?, val message: String?) : StreamEvent()
    data object Done : StreamEvent()
    data object Heartbeat : StreamEvent()
}

@Serializable
private data class StatusPayload(val runId: String? = null, val status: String? = null)

@Serializable
private data class TextPayload(val text: String? = null)

@Serializable
private data class ToolCallPayload(
    val callId: String? = null,
    val name: String? = null,
    val status: String? = null,
    val args: JsonElement? = null,
    val result: JsonElement? = null
)

@Serializable
private data class ResultPayload(
    val runId: String? = null,
    val status: String? = null,
    val text: String? = null,
    val durationMs: Long? = null,
    val git: GitInfo? = null
)

@Serializable
private data class ErrorPayload(val code: String? = null, val message: String? = null)

class RunStreamClient(
    private val okHttpClient: OkHttpClient,
    private val json: kotlinx.serialization.json.Json = ApiJson.instance
) {
    fun stream(agentId: String, runId: String, lastEventId: String? = null): Flow<StreamEvent> = callbackFlow {
        val url = "${CursorApiFactory.BASE_URL}v1/agents/$agentId/runs/$runId/stream"
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .get()
        if (!lastEventId.isNullOrBlank()) {
            requestBuilder.header("Last-Event-ID", lastEventId)
        }
        val request = requestBuilder.build()

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                val event = parseEvent(type, data) ?: return
                trySend(event)
                if (event is StreamEvent.Done) {
                    close()
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val message = t?.message
                    ?: response?.message
                    ?: "SSE 连接失败"
                trySend(StreamEvent.Error(code = response?.code?.toString(), message = message))
                close(t)
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val eventSource = EventSources.createFactory(okHttpClient)
            .newEventSource(request, listener)

        awaitClose { eventSource.cancel() }
    }

    private fun parseEvent(type: String?, data: String): StreamEvent? {
        if (data.isBlank()) return null
        return when (type) {
            "status" -> {
                val payload = json.decodeFromString(StatusPayload.serializer(), data)
                StreamEvent.Status(payload.runId, RunStatus.from(payload.status))
            }
            "assistant" -> {
                val payload = json.decodeFromString(TextPayload.serializer(), data)
                StreamEvent.AssistantDelta(payload.text.orEmpty())
            }
            "thinking" -> {
                val payload = json.decodeFromString(TextPayload.serializer(), data)
                StreamEvent.ThinkingDelta(payload.text.orEmpty())
            }
            "tool_call" -> {
                val payload = json.decodeFromString(ToolCallPayload.serializer(), data)
                StreamEvent.ToolCall(
                    callId = payload.callId ?: "tool",
                    name = payload.name ?: "tool",
                    status = payload.status ?: "running",
                    args = payload.args?.toString(),
                    result = payload.result?.toString()
                )
            }
            "result" -> {
                val payload = json.decodeFromString(ResultPayload.serializer(), data)
                StreamEvent.Result(
                    runId = payload.runId,
                    status = RunStatus.from(payload.status),
                    text = payload.text,
                    durationMs = payload.durationMs,
                    branches = payload.git?.branches.orEmpty()
                )
            }
            "error" -> {
                val payload = json.decodeFromString(ErrorPayload.serializer(), data)
                StreamEvent.Error(payload.code, payload.message)
            }
            "done" -> StreamEvent.Done
            "heartbeat" -> StreamEvent.Heartbeat
            "interaction_update" -> null
            else -> null
        }
    }
}
