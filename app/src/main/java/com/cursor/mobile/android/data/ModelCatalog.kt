package com.cursor.mobile.android.data

enum class ModelTier(val label: String, val hint: String, val model: String) {
    SPEED("高速", "秒回·日常小改", "Composer 2.5"),
    BALANCED("均衡", "质量与速度兼顾", "Claude Sonnet 4.5"),
    MAX("最强", "难啃的重构与疑难杂症", "GPT-5");

    companion object {
        fun fromModel(model: String): ModelTier =
            entries.firstOrNull { it.model == model } ?: BALANCED

        fun fromName(name: String): ModelTier =
            entries.firstOrNull { it.name == name } ?: BALANCED
    }
}
