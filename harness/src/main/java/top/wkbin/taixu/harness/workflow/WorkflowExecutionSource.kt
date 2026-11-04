package top.wkbin.taixu.harness.workflow

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import top.wkbin.taixu.core.database.AiModelEntity
import top.wkbin.taixu.core.model.ApprovalMode
import top.wkbin.taixu.core.model.RunMode

/** Native snapshot only. Never construct from node config or model-supplied arguments. */
@Serializable
data class WorkflowModelBinding(val modelId: String, val variant: String, val fingerprint: String) {
    fun verify(model: AiModelEntity) {
        check(model.id == modelId && variant.isNotBlank() && fingerprint == fingerprint(model)) {
            "来源模型已变化；需要新的原生审批"
        }
        check(variant in model.model.split(',').map(String::trim)) { "来源模型变体已删除" }
    }
    companion object {
        /** All persisted configuration participates, including secret reference, never secret bytes. */
        fun fingerprint(model: AiModelEntity): String = MessageDigest.getInstance("SHA-256")
            .digest(model.copy(isActive = false).toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

@Serializable
data class WorkflowExecutionSource(
    val sourceSessionId: String,
    val sourceOperationId: String,
    val modelBinding: WorkflowModelBinding,
    val targetJson: String,
    val workspace: String,
    val approvalMode: String,
    val runMode: String,
    val definitionHash: String,
    val authorizationId: String,
    val pluginStamps: Map<String,String> = emptyMap(),
) {
    fun authorizationDigest(): String = MessageDigest.getInstance("SHA-256")
        .digest(kotlinx.serialization.json.Json.encodeToString(serializer(),this).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun verify(binding: WorkflowExecutionBinding) {
        check(sourceSessionId.isNotBlank() && sourceOperationId.isNotBlank() && authorizationId.isNotBlank())
        check(ApprovalMode.entries.any { it.id == approvalMode } && RunMode.entries.any { it.id == runMode })
        check(targetJson == binding.targetJson && workspace == binding.workspace &&
            approvalMode == binding.approvalMode && runMode == binding.runMode && definitionHash == binding.definitionHash && pluginStamps == binding.pluginStamps) {
            "来源授权与执行租约不一致；需要新的原生审批"
        }
    }
}
