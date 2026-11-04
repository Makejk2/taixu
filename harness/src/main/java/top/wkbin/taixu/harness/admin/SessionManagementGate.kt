package top.wkbin.taixu.harness.admin

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import top.wkbin.taixu.core.model.RunMode
import top.wkbin.taixu.harness.*

/** Must dispatch before FULL_ACCESS/grants/cache. approvedNative is trusted native replay only.
 * Caller MUST run ManagementActorGuard first, including on read/propose/replay. */
suspend fun executeSessionManagement(
    backend: SessionManagementBackend?,
    sessionRunMode: suspend (String) -> RunMode?,
    call: ToolCall,
    sessionId: String,
    approvedNative: Boolean,
    now: Long,
    requestApproval: suspend (ApprovalDecision) -> ToolResult,
): ToolResult {
    fun result(ok: Boolean, text: String) = ToolResult(UUID.randomUUID().toString(), now, call.id, ok, text)
    val b = backend ?: return result(false, "session 原生后端未装配；未执行")
    return try {
        require(sessionText(call.args, "module") == "session")
        val action = sessionText(call.args, "action")
        val mode = requireNotNull(sessionRunMode(sessionId))
        if (mode == RunMode.PLAN && action !in setOf("list", "get")) {
            b.invalidateSession(sessionId); return result(false, "PLAN 仅允许 session list/get")
        }
        if (action == "apply") {
            sessionKeys(call.args, setOf("module", "action", "proposalId"))
            val id = sessionText(call.args, "proposalId")
            if (!approvedNative) requestApproval(ApprovalDecision(true, "critical", b.proposals.prepare(sessionId, id, call.id), "会话变更（仅此快照）"))
            else {
                val execution = b.proposals.apply(sessionId, id, call.id)
                result(execution.state == SessionExecutionState.APPLIED, buildJsonObject {
                    put("state", execution.state.name); put("sessionId", execution.sessionId)
                    put("stopCompleted", execution.stopCompleted)
                }.toString())
            }
        } else { val (ok, output) = b.execute(sessionId, call.args); result(ok, output) }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { result(false, "session 未完成：原生审批、身份、参数或快照校验失败。可能存在部分副作用，请 list/get 核对；不可重放。") }
}
