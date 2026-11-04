package top.wkbin.taixu.core.model.workflow

import kotlinx.serialization.Serializable

/** Native evidence references only; never accept this structure from proposal JSON. */
@Serializable
data class WorkflowExpansionAdmission(
    val reviewId: String,
    val parentHash: String,
    val definitionHash: String,
    val requestDigest: String,
    val pluginStamps: Map<String, String> = emptyMap(),
)
