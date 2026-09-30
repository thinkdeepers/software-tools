package com.cursor.mobile.android.data

data class AgentRequest(
    val repo: String,
    val branch: String,
    val prompt: String,
    val model: String,
    val tier: ModelTier,
    val machine: MachineKind = MachineKind.CLOUD,
    val scope: WorkScope = WorkScope.REPOSITORY,
    val projectName: String = ""
) {
    fun selection(catalog: List<RemoteModel>): ModelSelection = selectModel(tier, model, catalog)
}
