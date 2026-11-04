package top.wkbin.taixu.harness.admin.rules

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import top.wkbin.taixu.core.model.RunMode
import top.wkbin.taixu.harness.*

/** Caller holds the shared management epoch boundary. Only native replay supplies approved. */
suspend fun executeUserRules(backend: UserRulesBackend?, actor: RuleManagementActor,
    call: ToolCall, mode: RunMode?, approved: Boolean, now: Long,
    request: suspend (ApprovalDecision) -> ToolResult): ToolResult {
    fun result(ok: Boolean, text: String) = ToolResult(UUID.randomUUID().toString(), now, call.id, ok, text)
    val service = backend ?: return result(false, "RULES_BACKEND_UNAVAILABLE")
    return try {
        val action = (call.args["action"] as? JsonPrimitive)?.content ?: error("missing_action")
        require(mode != null)
        if (mode == RunMode.PLAN && action !in setOf("list", "get")) return result(false, "PLAN仅允许规则查询")
        if (action == "apply") {
            require(call.args.keys == setOf("module", "action", "proposalId"))
            val id = call.args.getValue("proposalId").jsonPrimitive.content
            if (!approved) {
                val p = service.prepare(actor, id, call.id)
                request(ApprovalDecision(true, "critical", "规则${p.action}：${p.ruleId}；预期版本${p.expectedRevision}。" +
                    "规则只可增加限制，不能提升权限；批准后以持久CAS写入。", "用户规则（本次快照）"))
            } else result(true, service.applyNative(actor, id, call.id).toString())
        } else {
            require(!approved)
            result(true, service.execute(actor, call.args).toString())
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { result(false, "RULE_MANAGEMENT_REJECTED；如已进入写入请核对版本，不自动重放") }
}
