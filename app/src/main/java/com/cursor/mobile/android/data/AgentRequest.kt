package com.cursor.mobile.android.data

data class AgentRequest(
    val repo: String,
    val branch: String,
    val prompt: String,
    val model: String,
    val tier: ModelTier,
    val machine: MachineKind = MachineKind.CLOUD
) {
    fun toPayload(): Map<String, String> = mapOf(
        "repo" to repo,
        "branch" to branch,
        "prompt" to prompt,
        "model" to model,
        "intensity" to tier.name.lowercase(),
        "effort" to when (tier) {
            ModelTier.SPEED -> "low"
            ModelTier.BALANCED -> "medium"
            ModelTier.MAX -> "high"
        },
        "machine" to machine.name.lowercase(),
        "source" to "androidApp"
    )
}
