package com.cursor.mobile.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cursor.mobile.android.ui.theme.accentGradient

@Composable
fun ComposerBar(
    value: String,
    onValueChange: (String) -> Unit,
    model: String,
    onSend: () -> Unit,
    enabled: Boolean = true
) {
    val sendSource = remember { MutableInteractionSource() }
    val sendPressed by sendSource.collectIsPressedAsState()
    val canSend = enabled && value.isNotBlank()
    val sendTint by animateColorAsState(
        if (canSend) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "sendTint"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                1.dp,
                if (value.isNotBlank()) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(24.dp)
            )
            .padding(6.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        IconButton(onClick = {}) { Icon(Icons.Filled.Add, contentDescription = "附件/截图", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("$model · 描述任务，/ 唤起指令…", style = MaterialTheme.typography.bodySmall) },
            maxLines = 5,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            )
        )
        IconButton(onClick = {}) { Icon(Icons.Filled.Mic, contentDescription = "语音输入", tint = MaterialTheme.colorScheme.primary) }
        IconButton(
            onClick = onSend,
            enabled = canSend,
            interactionSource = sendSource
        ) {
            val sendModifier = if (canSend) {
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(accentGradient())
            } else {
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            }
            Box(
                modifier = sendModifier.padding(8.dp),
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
