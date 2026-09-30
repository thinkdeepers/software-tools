package com.cursor.mobile.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cursor.mobile.android.data.AgentStore
import com.cursor.mobile.android.ui.components.Kicker
import com.cursor.mobile.android.ui.theme.accentGradient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    darkTheme: Boolean,
    onToggleTheme: (Boolean) -> Unit,
    notifications: Boolean,
    onToggleNotifications: (Boolean) -> Unit,
    onOpenDrawer: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onOpenDrawer) { Icon(Icons.Filled.Menu, contentDescription = "侧边栏") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(16.dp)).background(accentGradient()),
                    contentAlignment = Alignment.Center
                ) {
                    Text("C", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(AgentStore.accountLabel.ifBlank { "已登录" }, fontWeight = FontWeight.SemiBold)
                    Text("Cloud Agents API", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Kicker("外观与通知")
            SettingCard("浅色科技风（默认）", "干净透亮，浅底 + 蓝紫渐变", !darkTheme) { onToggleTheme(!it) }
            SettingCard("推送通知", "完成 / 需确认 / 待 Review 即推", notifications) { onToggleNotifications(it) }

            Kicker("模型与隐私")
            InfoCard(
                listOf(
                    "默认模型" to "Composer 2.5（占位，对话页可单次切换）",
                    "隐私" to "需开启云端存储（对标 iOS Privacy Mode）",
                    "环境 / Secrets / MCP" to "请去 Web 管理，与 iOS 一致不在端内",
                    "Remote Control" to "桌面端 Settings > Agents 开启后出现 My Machine 会话"
                )
            )
        }
    }
}

@Composable
private fun SettingCard(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
        )
    }
}

@Composable
private fun InfoCard(rows: List<Pair<String, String>>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        rows.forEach { (k, v) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(k, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(v, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
