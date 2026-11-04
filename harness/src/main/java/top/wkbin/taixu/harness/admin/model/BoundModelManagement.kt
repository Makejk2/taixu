package top.wkbin.taixu.harness.admin.model

import java.util.UUID
import kotlinx.coroutines.CancellationException
import top.wkbin.taixu.harness.ApprovalDecision
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.core.database.*

/** Run ManagementActorGuard first, also on native replay. Actor is built by executor from the
 * active native main-lane journal operation/context/call, NEVER from call.args. The native
 * approval continuation alone supplies approvedNative. Dispatch before generic grants. */
suspend fun executeBoundModelManagement(
    backend: ModelAdminBackend?,
    actor: ModelAdminActor,
    call: ToolCall,
    approvedNative: Boolean,
    now: Long,
    requestApproval: suspend (ApprovalDecision) -> ToolResult,
): ToolResult {
    fun result(ok: Boolean, text: String) = ToolResult(UUID.randomUUID().toString(), now, call.id, ok, text)
    val b = backend ?: return result(false, "MODEL_BACKEND_UNAVAILABLE")
    return try {
        require(actor.callId == call.id)
        when (val execution = executeModelAdmin(b, actor, call.args, approvedNative)) {
            is ModelAdminResult.ApprovalRequired -> {
                val p = execution.value
                val scope = when (p.operation) {
                    "patch" -> "修改已有档案的非敏感采样/推理参数（全局共享档案；不修改凭证、地址或请求头）"
                    "global_default" -> "切换全局默认档案（不修改当前及其他会话的显式选择）"
                    "current_session" -> "仅切换当前会话档案（不修改全局默认及其他会话）"
                    else -> error("invalid")
                }
                val preview = "$scope\n${p.let { ModelAdminResult.Proposal(it).toSafeJson() }}\n" +
                    if (p.willResetVariant) "本次将重置当前会话 variant；不展示原值。" else "本次不重置 variant。"
                requestApproval(ApprovalDecision(true, "critical", preview, "模型变更（仅此快照、逐次审批）"))
            }
            else -> result(execution !is ModelAdminResult.Rejected, execution.toSafeJson().toString())
        }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { result(false, "MODEL_ADMIN_REJECTED") }
}

/** Required durable intent/completion callback. Store only allowlisted operation/outcome codes;
 * neither profile text, endpoint, headers, variant nor exception messages enter the ledger. */
fun createModelManagementBackend(
    repository: ModelManagementRepository,
    authority: ModelAdminAuthority,
    auditRepository: AgentApprovalAuditRepository,
): ModelAdminBackend = ModelAdminBackend(ModelAdminService(repository, authority, audit = { actor, phase, outcome ->
    require(phase in setOf("model_intent", "model_completion"))
    require(outcome in setOf("patch", "global_default", "current_session", "applied", "snapshot_changed"))
    auditRepository.record(AgentApprovalAuditEntity(
        id = UUID.randomUUID().toString(), sessionId = actor.sessionId, kind = phase,
        toolName = "app_config:model", toolCallId = actor.callId, riskLevel = "critical",
        detail = outcome, createdAt = System.currentTimeMillis(),
    ))
}))
