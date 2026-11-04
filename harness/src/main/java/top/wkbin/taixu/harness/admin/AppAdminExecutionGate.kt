package top.wkbin.taixu.harness.admin

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import top.wkbin.taixu.core.model.RunMode
import top.wkbin.taixu.harness.ApprovalDecision
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.approval.planBlockedResult

/** Dispatch BEFORE generic approval/full-access/grant/replay shortcuts. approvedNative is exclusively
 * the real native replay retaining the original call.id; never JSON, a mode or remembered grant. */
suspend fun executeAppAdmin(
    backend: AppAdminBackend?,
    sessionRunMode: suspend (String) -> RunMode?,
    call: ToolCall,
    sessionId: String,
    approvedNative: Boolean,
    now: Long,
    requestApproval: suspend (ApprovalDecision) -> ToolResult,
): ToolResult {
    fun result(success: Boolean, output: String) = ToolResult(UUID.randomUUID().toString(), now, call.id, success, output)
    val b = backend ?: return result(false, "app_admin 原生后端未装配；未执行")
    return try {
        require(adminText(call.args, "module") == "workflow") { "不支持的模块" }
        val action = adminText(call.args, "action")
        val mode = requireNotNull(sessionRunMode(sessionId)) { "来源会话不存在" }
        if (mode == RunMode.PLAN && action !in setOf("list", "get", "status")) {
            b.service.invalidateSession(sessionId)
            return planBlockedResult(now, call.id, AppAdminToolSpec.name, "PLAN 只允许 workflow list/get/status，不得提案或运行/停止。")
        }
        if (action == "apply") {
            adminKeys(call.args, setOf("module", "action", "proposalId"))
            val id = adminText(call.args, "proposalId")
            if (!approvedNative) {
                val description = b.service.prepare(sessionId, id, call.id)
                requestApproval(ApprovalDecision(true, "critical", description, "工作流变更（仅此快照）"))
            } else result(true, b.service.applyNative(sessionId, id, call.id))
        } else {
            val (ok, output) = b.execute(sessionId, call.args)
            result(ok, output)
        }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) {
        // Never echo decoder/port exceptions: they can contain credentials, configs or raw logs.
        result(false, "app_admin 未完成：身份/参数/快照或原生审批校验失败。若进入过执行，可能存在部分副作用；请 list/get/status 核对，禁止重放。")
    }
}
fun appAdminApproval(args: JsonObject): ApprovalDecision =
    if ((args["action"] as? JsonPrimitive)?.content == "apply")
        ApprovalDecision(true, "critical", "必须经过快照绑定的逐次原生审批；FULL_ACCESS 和历史授权不豁免", "工作流变更")
    else ApprovalDecision(false)
