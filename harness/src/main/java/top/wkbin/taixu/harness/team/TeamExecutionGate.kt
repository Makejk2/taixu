package top.wkbin.taixu.harness.team

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import top.wkbin.taixu.core.model.RunMode
import top.wkbin.taixu.harness.ApprovalDecision
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.approval.planBlockedResult

/**
 * Must be dispatched BEFORE generic FULL_ACCESS/session-grant shortcuts, including approved replay.
 * approvedNative MUST come only from ToolExecutor's real native approval replay, never tool JSON.
 * The native approval must retain the original ToolCall.id. No backend public apply bypass exists.
 */
suspend fun executeTeam(
    backend: TeamToolBackend?,
    sessionRunMode: suspend (String) -> RunMode?,
    call: ToolCall,
    sessionId: String,
    approvedNative: Boolean,
    now: Long,
    requestApproval: suspend (ApprovalDecision) -> ToolResult,
): ToolResult {
    fun result(success: Boolean, output: String) = ToolResult(
        UUID.randomUUID().toString(), now, call.id, success, output)
    val b = backend ?: return result(false, "团队后端不可用；未执行。")
    return try {
        require(sessionId.isNotBlank()) { "必须绑定原生会话" }
        val mode = requireNotNull(sessionRunMode(sessionId)) { "无法核验原生会话" }
        val action = (call.args["action"] as? JsonPrimitive)?.content.orEmpty()
        b.audit(sessionId, "attempt", call.args.toString())
        if (mode == RunMode.PLAN && action !in READS) {
            return planBlockedResult(now, call.id, TeamToolSpec.name, "PLAN 仅允许 list/get/status/proposal；不得创建提案、修改团队或启动/停止成员。")
        }
        if (action == "apply") {
            // No changes are accepted on apply: the stored immutable operation is the entire authority.
            require(call.args.keys.all { it in setOf("action", "proposalId") }) { "apply 仅允许 action/proposalId，不能修改快照或自证批准" }
            val id = (call.args["proposalId"] as? JsonPrimitive)?.content.orEmpty()
            require(id.isNotBlank()) { "缺少 proposalId" }
            if (!approvedNative) {
                val description = b.service.prepare(sessionId, id, call.id)
                requestApproval(ApprovalDecision(true, "critical", description, "批准持久团队变更"))
            } else result(true, b.service.applyNative(sessionId, id, call.id))
        } else {
            val (success, output) = b.execute(sessionId, call.args)
            result(success, output)
        }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        result(false, "团队操作未完成：${e.message}")
    }
}

/** Native tool policy marker; it must never be waived by FULL_ACCESS or remembered grants. */
fun teamApproval(args: JsonObject): ApprovalDecision =
    if ((args["action"] as? JsonPrimitive)?.content == "apply")
        ApprovalDecision(true, "critical", "持久团队变更必须经过快照绑定的专用原生审批。", "团队变更")
    else ApprovalDecision(false)

private val READS = setOf("list", "get", "status", "proposal", "suggestions")
