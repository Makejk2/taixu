package top.wkbin.taixu.harness.config

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.taixu.core.model.RunMode
import top.wkbin.taixu.harness.*
import top.wkbin.taixu.harness.approval.planBlockedResult

/** Independent of global FULL_ACCESS and generic session grants. Only native replay sets approvedNative. */
internal suspend fun executeAppConfig(
    backend: AppConfigToolBackend?, policy: ApprovalPolicyEngine,
    sessionRunMode: suspend (String) -> RunMode?,
    call: ToolCall, sessionId: String, approvedNative: Boolean, now: Long,
    requestApproval: suspend (ApprovalDecision) -> ToolResult,
): ToolResult {
        fun result(success: Boolean, output: String) = ToolResult(
            UUID.randomUUID().toString(), now, call.id, success, output)
        val availableBackend = backend ?: return result(false, "配置后端不可用；未执行。")
        return try {
            // Required in every mode, before PLAN rejection and before reads as well as writes.
            availableBackend.audit(sessionId, "attempt", call.args.toString())
            require(sessionId.isNotBlank()) { "配置工具必须绑定原生会话。" }
            // Missing/deleted sessions are never allowed to inherit a global BUILD setting.
            val runMode = requireNotNull(sessionRunMode(sessionId)) { "无法验证原生会话，未执行。" }
            if (runMode == RunMode.PLAN) {
                policy.planBlock(HarnessTool.APP_CONFIG, call.args)?.let { reason ->
                    availableBackend.audit(sessionId, "plan_blocked", reason)
                    return planBlockedResult(now, call.id, "app_config", reason)
                }
            }
            val action = call.args["action"]?.jsonPrimitive?.content
            if (action == "apply") {
                val (required, description) = availableBackend.approval(sessionId, call.args)
                if (required && !approvedNative) {
                    availableBackend.audit(sessionId, "approval_requested", description)
                    return requestApproval(ApprovalDecision(true, "critical", description, "受控应用配置写入"))
                }
            }
            val (success, output) = availableBackend.execute(sessionId, call.args, approvedNative)
            result(success, output)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // If durable auditing fails, the operation fails closed rather than mutating silently.
            runCatching { availableBackend.audit(sessionId, "failed", e.message.orEmpty()) }
                .onFailure { if (it is CancellationException) throw it }
            result(false, "配置操作未完成：${e.message}")
        }
}

/** No global FULL_ACCESS shortcut for configuration writes. */
internal fun appConfigApproval(args: kotlinx.serialization.json.JsonObject): ApprovalDecision =
    if (args["action"]?.jsonPrimitive?.content == "apply")
        ApprovalDecision(true, "critical", "应用配置写入必须经过专用的快照绑定原生审批。", "应用受控配置")
    else ApprovalDecision(false)
