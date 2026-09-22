package com.cursor.mobile.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cursor.mobile.data.model.AgentSummary
import com.cursor.mobile.data.repository.CursorRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AgentWatch(
    context: Context,
    private val repository: CursorRepository
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val seen = mutableMapOf<String, String>()

    fun start() {
        if (job?.isActive == true) return
        ensureChannel()
        job = scope.launch {
            while (isActive) {
                if (repository.hasSession()) {
                    runCatching { repository.listAgents(includeArchived = false) }
                        .onSuccess { publish(it) }
                }
                delay(45_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun publish(agents: List<AgentSummary>) {
        ensureChannel()
        agents.forEach { agent ->
            val previous = seen[agent.id]
            val current = agent.status?.uppercase().orEmpty()
            if (previous != null && previous in ACTIVE && current !in ACTIVE) {
                notify(agent)
            }
            seen[agent.id] = current
        }
    }

    private fun notify(agent: AgentSummary) {
        val notification = NotificationCompat.Builder(appContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(agent.name?.ifBlank { null } ?: "云程任务已完成")
            .setContentText("状态：${agent.status ?: "IDLE"}，可以回来查看或继续下指令")
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(appContext).notify(agent.id.hashCode(), notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = appContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, "云程任务", NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL = "yuncheng_agents"
        private val ACTIVE = setOf("ACTIVE", "RUNNING", "CREATING")
    }
}
