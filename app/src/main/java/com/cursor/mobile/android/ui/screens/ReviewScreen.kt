package com.cursor.mobile.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursor.mobile.android.data.AgentStore
import com.cursor.mobile.android.ui.components.EmptyState
import com.cursor.mobile.android.ui.components.Kicker
import com.cursor.mobile.android.ui.components.MessageBody
import com.cursor.mobile.android.ui.theme.CursorPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(sessionId: String, onBack: () -> Unit) {
    val session = AgentStore.sessions.firstOrNull { it.id == sessionId }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review · PR", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CapsuleButton("Merge · Squash（占位）", filled = true)
                CapsuleButton("Request changes", filled = false)
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Kicker("Pull request")
                    Text(
                        session?.repoUrl?.let { com.cursor.mobile.android.data.shortRepo(it) }?.takeIf { it.isNotBlank() && !it.startsWith("http") }
                            ?: session?.repo?.takeIf { it.isNotBlank() && !it.startsWith("http") }
                            ?: "未归类",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (!session?.prUrl.isNullOrBlank()) {
                        Text(session?.prUrl ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (!session?.summary.isNullOrBlank()) {
                        MessageBody(
                            session?.summary ?: "",
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            MaterialTheme.typography.bodySmall
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CiPill("CI 通过", CursorPalette.Success)
                        CiPill("2 approvals", CursorPalette.NeonViolet)
                        CiPill("可合并", CursorPalette.BrandBlue)
                    }
                    Text("分支和 PR 来自 Cloud Agents。文件 diff 仍在 Cursor Web 查看。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { EmptyState("没有本地 diff", "打开会话链接可在 Cursor Web 看完整改动。", compact = true) }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Artifacts（占位）", fontWeight = FontWeight.SemiBold)
                    Text("screenshots/login-fix-demo.mp4 · logs/ci.log", fontFamily = com.cursor.mobile.android.ui.theme.CodeFont, style = MaterialTheme.typography.bodySmall)
                    Text("云端录屏与截图在接后端后展示真实内容。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { Box(Modifier.size(8.dp)) }
        }
    }
}

@Composable
private fun CapsuleButton(label: String, filled: Boolean) {
    Box(
        modifier = Modifier
            .height(40.dp)
            .wrapContentWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (filled) CursorPalette.BrandBlue else MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                if (filled) CursorPalette.BrandBlue else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(20.dp)
            )
            .clickable {}
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (filled) Color.White else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        )
    }
}

@Composable
private fun CiPill(text: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(999.dp)).background(color))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}
