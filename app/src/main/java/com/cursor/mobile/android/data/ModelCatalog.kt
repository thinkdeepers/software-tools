package com.cursor.mobile.android.data

enum class ModelTier(val label: String, val hint: String, val model: String) {
    SPEED("高速", "秒回·日常小改", "Composer 2.5"),
    BALANCED("均衡", "质量与速度兼顾", "Claude Sonnet 4.5"),
    MAX("最强", "难啃的重构与疑难杂症", "GPT-5");

    companion object {
        fun fromModel(model: String): ModelTier =
            entries.firstOrNull { it.model.equals(model, true) } ?: BALANCED

        fun fromName(name: String): ModelTier =
            entries.firstOrNull { it.name == name } ?: BALANCED
    }
}

data class ModelParam(val id: String, val values: List<String>)

data class ModelVariant(
    val displayName: String,
    val params: List<Pair<String, String>>,
    val isDefault: Boolean
)

data class RemoteModel(
    val id: String,
    val displayName: String,
    val aliases: List<String> = emptyList(),
    val parameters: List<ModelParam> = emptyList(),
    val variants: List<ModelVariant> = emptyList()
) {
    fun matches(name: String): Boolean {
        if (name.isBlank()) return false
        return id.equals(name, true) ||
            displayName.equals(name, true) ||
            aliases.any { it.equals(name, true) }
    }
}

data class ModelSelection(val id: String, val params: List<Pair<String, String>>)

fun localModels(): List<RemoteModel> = listOf(
    RemoteModel("composer-2", "Composer 2", aliases = listOf("Composer 2.5", "composer", "composer-latest")),
    RemoteModel("claude-4.5-sonnet-thinking", "Claude Sonnet 4.5", aliases = listOf("Claude Sonnet 4.5")),
    RemoteModel("gpt-5.2", "GPT-5", aliases = listOf("GPT-5", "gpt-5")),
    RemoteModel("gemini-2.5-pro", "Gemini 2.5 Pro", aliases = listOf("Gemini 2.5 Pro"))
)

fun modelChoices(catalog: List<RemoteModel>): List<RemoteModel> =
    catalog.ifEmpty { localModels() }

fun modelLabel(selected: String, catalog: List<RemoteModel>): String =
    modelChoices(catalog).firstOrNull { it.matches(selected) }?.displayName ?: selected.ifBlank { "选择模型" }

fun selectModel(tier: ModelTier, preferred: String, catalog: List<RemoteModel>): ModelSelection {
    val pool = if (catalog.isNotEmpty()) catalog else localModels()
    val chosen = pool.firstOrNull { it.matches(preferred) }
    if (chosen != null) {
        val params = paramsFor(chosen, tier)
        val withTier = if (params.isEmpty() && chosen.parameters.isEmpty()) fallbackParams(tier) else params
        return ModelSelection(chosen.id, withTier)
    }
    val id = preferred.takeIf { it.isNotBlank() && ' ' !in it } ?: fallbackId(tier)
    return ModelSelection(id, fallbackParams(tier))
}

fun suggestedModelId(tier: ModelTier, catalog: List<RemoteModel>): String =
    modelForTier(tier, catalog)?.id ?: tier.model

private fun fallbackId(tier: ModelTier): String = when (tier) {
    ModelTier.SPEED -> "composer-2"
    ModelTier.BALANCED -> "claude-4.5-sonnet-thinking"
    ModelTier.MAX -> "gpt-5.2"
}

private fun fallbackParams(tier: ModelTier): List<Pair<String, String>> {
    val params = mutableListOf("thinking" to effortValue(tier))
    params += "fast" to if (tier == ModelTier.SPEED) "true" else "false"
    return params
}

private fun effortValue(tier: ModelTier): String = when (tier) {
    ModelTier.SPEED -> "low"
    ModelTier.BALANCED -> "medium"
    ModelTier.MAX -> "high"
}

private fun modelForTier(tier: ModelTier, catalog: List<RemoteModel>): RemoteModel? {
    if (catalog.isEmpty()) return null
    fun find(pred: (RemoteModel) -> Boolean) = catalog.firstOrNull(pred)
    return when (tier) {
        ModelTier.SPEED ->
            find { it.id.contains("composer", true) || it.displayName.contains("composer", true) }
                ?: catalog.first()
        ModelTier.BALANCED ->
            find { it.id.contains("sonnet", true) || it.displayName.contains("sonnet", true) }
                ?: catalog.first()
        ModelTier.MAX ->
            find {
                it.id.contains("gpt-5", true) || it.id.contains("opus", true) ||
                    it.displayName.contains("gpt-5", true) || it.displayName.contains("opus", true)
            } ?: catalog.last()
    }
}

private fun paramsFor(model: RemoteModel, tier: ModelTier): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    val effort = effortValue(tier)
    val effortParam = model.parameters.firstOrNull {
        val id = it.id.lowercase()
        id == "thinking" || id == "effort" || id.contains("reason")
    }
    if (effortParam != null) {
        val value = effortParam.values.firstOrNull { it.equals(effort, true) }
            ?: when (tier) {
                ModelTier.SPEED -> effortParam.values.firstOrNull()
                ModelTier.BALANCED -> effortParam.values.getOrNull(effortParam.values.size / 2)
                ModelTier.MAX -> effortParam.values.lastOrNull()
            }
        if (value != null) out += effortParam.id to value
    }
    val fast = model.parameters.firstOrNull { it.id.equals("fast", true) }
    if (fast != null) {
        val want = if (tier == ModelTier.SPEED) "true" else "false"
        val value = fast.values.firstOrNull { it.equals(want, true) } ?: fast.values.firstOrNull()
        if (value != null) out += fast.id to value
    }
    if (out.isEmpty()) {
        val variant = when (tier) {
            ModelTier.SPEED -> model.variants.firstOrNull { variant ->
                variant.params.any { it.first.equals("fast", true) && it.second.equals("true", true) } ||
                    variant.displayName.contains("fast", true)
            }
            ModelTier.MAX -> model.variants.firstOrNull { variant ->
                variant.params.any { (id, value) ->
                    val key = id.lowercase()
                    (key == "thinking" || key == "effort" || key.contains("reason")) &&
                        (value.equals("high", true) || value.equals("max", true) || value.equals("xhigh", true))
                } || variant.displayName.contains("high", true) || variant.displayName.contains("max", true)
            }
            ModelTier.BALANCED -> model.variants.firstOrNull { it.isDefault }
        } ?: model.variants.firstOrNull { it.isDefault }
        if (variant != null) out += variant.params
    }
    return out
}
