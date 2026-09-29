package com.cursor.mobile.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cursor.mobile.android.data.FakeRepository
import com.cursor.mobile.android.ui.components.DiffRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(sessionId: String, onBack: () -> Unit) {
    val diffs = FakeRepository.diffsFor(sessionId)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review · PR") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") } }
            )
        },
        bottomBar = {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Merge（Squash，占位）") }
                OutlinedButton(onClick = {}) { Text("Request changes") }
                OutlinedButton(onClick = {}) { Text("+ Follow-up") }
            }
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("fix/login-crash → main · CI ✅ · 2 approvals（占位）", fontWeight = FontWeight.SemiBold)
                    Text("对标 iOS：diff / commits / checks / 评论 / reviewer / auto-merge 全在这一页。", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(diffs) { DiffRow(it) }
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Artifacts（占位）", fontWeight = FontWeight.SemiBold)
                    Text("screenshots/login-fix-demo.mp4 · logs/ci.log · Demo 录屏与截图在接后端后展示真实内容。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
