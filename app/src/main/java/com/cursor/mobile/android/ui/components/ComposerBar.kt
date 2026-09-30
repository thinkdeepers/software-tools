package com.cursor.mobile.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cursor.mobile.android.ui.theme.accentGradient

data class ComposerAttachment(val uri: String, val name: String, val mime: String)

@Composable
fun ComposerBar(
    value: String,
    onValueChange: (String) -> Unit,
    model: String,
    onSend: () -> Unit,
    enabled: Boolean = true,
    attachments: List<ComposerAttachment> = emptyList(),
    onRemoveAttachment: (Int) -> Unit = {},
    onPickPhoto: () -> Unit = {},
    onPickFile: () -> Unit = {}
) {
    val sendSource = remember { MutableInteractionSource() }
    val sendPressed by sendSource.collectIsPressedAsState()
    val canSend = enabled && (value.isNotBlank() || attachments.isNotEmpty())
    var attachMenu by remember { mutableStateOf(false) }
    val sendTint by animateColorAsState(
        if (canSend) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "sendTint"
    )

    if (attachMenu) {
        Dialog(onDismissRequest = { attachMenu = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                    .padding(vertical = 8.dp)
            ) {
                Text("添加附件", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
                Text(
                    "照片",
                    modifier = Modifier.fillMaxWidth().clickable {
                        attachMenu = false
                        onPickPhoto()
                    }.padding(horizontal = 16.dp, vertical = 14.dp)
                )
                Text(
                    "文件",
                    modifier = Modifier.fillMaxWidth().clickable {
                        attachMenu = false
                        onPickFile()
                    }.padding(horizontal = 16.dp, vertical = 14.dp)
                )
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(horizontal = ChatTextPadding).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                attachments.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier
                            .wrapContentWidth(unbounded = true)
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(999.dp))
                            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${item.name} · ${item.mime}",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(onClick = { onRemoveAttachment(index) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "移除附件", modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                1.dp,
                if (value.isNotBlank()) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(16.dp)
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = ChatTextPadding, vertical = 8.dp),
            enabled = enabled,
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                lineHeight = 17.sp
            ),
            maxLines = 4,
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text("描述任务…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 17.sp)
                    }
                    inner()
                }
            }
        )
        IconButton(
            onClick = { attachMenu = true },
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 2.dp).size(22.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = "附件", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        }
        IconButton(
            onClick = onSend,
            enabled = canSend,
            interactionSource = sendSource,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp).size(28.dp)
        ) {
            val sendModifier = if (canSend) {
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(accentGradient())
            } else {
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            }
            Box(
                modifier = sendModifier,
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    tint = (if (sendPressed) sendTint.copy(alpha = 0.6f) else sendTint),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
    }
}
