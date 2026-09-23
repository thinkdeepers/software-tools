package com.cursor.mobile.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cursor.mobile.data.model.ModelInfo

fun ModelInfo.defaultParams(): Map<String, String> {
    val variant = variants.firstOrNull { it.isDefault } ?: variants.firstOrNull()
    if (variant != null) {
        return variant.params.associate { it.id to it.value }
    }
    return parameters.mapNotNull { parameter ->
        val value = parameter.values.firstOrNull()?.value ?: return@mapNotNull null
        parameter.id to value
    }.toMap()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerBar(
    models: List<ModelInfo>,
    selectedId: String?,
    params: Map<String, String>,
    onModel: (String?) -> Unit,
    onParams: (Map<String, String>) -> Unit,
    onReload: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    val model = models.find { it.id == selectedId }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = {
            if (models.isEmpty()) onReload() else open = true
        }) {
            Text(model?.label ?: if (models.isEmpty()) "模型加载失败，点此重试" else "选择模型")
        }
        if (model != null && model.variants.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                model.variants.forEach { variant ->
                    val variantParams = variant.params.associate { it.id to it.value }
                    FilterChip(
                        selected = variantParams == params,
                        onClick = { onParams(variantParams) },
                        label = { Text(variant.displayName ?: variantParams.values.joinToString("/").ifBlank { "默认" }) }
                    )
                }
            }
        } else if (model != null) {
            model.parameters.forEach { parameter ->
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    parameter.values.forEach { option ->
                        FilterChip(
                            selected = params[parameter.id] == option.value,
                            onClick = { onParams(params + (parameter.id to option.value)) },
                            label = { Text(option.displayName ?: option.value) }
                        )
                    }
                }
            }
        }
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            TextButton(
                onClick = {
                    onModel(null)
                    open = false
                },
                modifier = Modifier.padding(horizontal = 8.dp)
            ) { Text("账号默认模型") }
            models.forEach { item ->
                ListItem(
                    headlineContent = { Text(item.label) },
                    supportingContent = { Text(item.id) },
                    modifier = Modifier.fillMaxWidth(),
                    trailingContent = {
                        TextButton(onClick = {
                            onModel(item.id)
                            open = false
                        }) { Text("使用") }
                    }
                )
            }
        }
    }
}
