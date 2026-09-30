package com.cursor.mobile.android.ui.screens

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.cursor.mobile.android.MainActivity
import com.cursor.mobile.android.data.AuthRepository
import com.cursor.mobile.android.ui.theme.CursorPalette
import com.cursor.mobile.android.ui.theme.accentGradient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onLoggedIn: () -> Unit, initialError: String? = null) {
    val context = LocalContext.current
    val waiting = LoginController.waiting
    val error = LoginController.message ?: initialError
    val loginUrl = LoginController.loginUrl
    val browserNote = LoginController.browserNote
    LaunchedEffect(initialError) {
        if (!initialError.isNullOrBlank() && LoginController.message.isNullOrBlank()) {
            LoginController.message = initialError
        }
    }
    LaunchedEffect(LoginController.succeeded) {
        if (LoginController.succeeded) {
            LoginController.succeeded = false
            onLoggedIn()
        }
    }
    val startLogin = {
        LoginController.start(context) { url ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                LoginController.browserNote = "这台手机没有可用的浏览器，请复制链接后手动打开。"
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        startLogin()
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(accentGradient()),
                contentAlignment = Alignment.Center
            ) {
                Text("◈", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
            Text("登录 Cursor", style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp), modifier = Modifier.padding(top = 14.dp))
            Text(
                "使用和电脑端相同的 Cursor 账号",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "点下面的按钮会打开 Cursor 登录页。确认后页面停在浏览器里，点通知「回到 Cursor」，或从最近任务切回来。Cursor 网页本身不会自动打开这个应用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        startLogin()
                    }
                },
                enabled = !waiting,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CursorPalette.BrandBlue, contentColor = Color.White)
            ) {
                if (waiting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                else Text("用 Cursor 账号登录", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp))
            }
            if (AuthRepository.hasPendingSession(context) && !waiting) {
                Text(
                    "浏览器登录已保存，但还没有换发出 Cloud Agents 凭证。点下面重试；失败原因会留在这页，不会当成登录完成。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
                TextButton(onClick = { LoginController.retry(context) }) { Text("重试换发凭证") }
            }
            if (waiting) {
                Text(
                    "正在等待浏览器确认。完成后点通知回到这里；如果没看到通知，从最近任务打开 Cursor。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
                TextButton(onClick = { LoginController.cancel() }) { Text("取消") }
            }
            browserNote?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
            loginUrl?.let { url ->
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Cursor 登录", url))
                    LoginController.browserNote = "登录链接已复制"
                }) { Text("复制登录链接") }
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

private object LoginController {
    var waiting by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var loginUrl by mutableStateOf<String?>(null)
    var browserNote by mutableStateOf<String?>(null)
    var succeeded by mutableStateOf(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    fun start(context: Context, open: (String) -> Unit) {
        job?.cancel()
        waiting = true
        message = null
        browserNote = null
        succeeded = false
        job = scope.launch {
            val failure = AuthRepository.signIn(context) { url ->
                loginUrl = url
                open(url)
            }
            waiting = false
            if (failure == null) {
                bringBack(context)
                succeeded = true
            } else {
                message = failure
            }
        }
    }

    fun retry(context: Context) {
        job?.cancel()
        waiting = true
        message = null
        job = scope.launch {
            val failure = AuthRepository.retryMint(context)
            waiting = false
            if (failure == null) {
                bringBack(context)
                succeeded = true
            } else {
                message = failure
            }
        }
    }

    fun cancel() {
        job?.cancel()
        waiting = false
        message = "已取消登录"
    }

    private fun bringBack(context: Context) {
        val launch = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel("login", "登录", NotificationManager.IMPORTANCE_HIGH))
        }
        val notification = Notification.Builder(context, "login")
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("Cursor 登录完成")
            .setContentText("点此回到应用")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            manager.notify(1001, notification)
        } catch (_: SecurityException) {
            // 没开通知权限时，仍尝试把应用拉回前台
        }
        try {
            context.startActivity(launch)
        } catch (_: Exception) {
            // 后台拉起可能被系统拦住，通知仍可点回来
        }
    }
}
