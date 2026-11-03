package top.wkbin.taixu.harness.admin

import top.wkbin.taixu.core.database.HarnessRuntimeRepository
import top.wkbin.taixu.harness.*

/** Native operation journal, not model JSON or a session-name guess, determines the caller lane. */
class ManagementActorGuard(private val operations: HarnessRuntimeRepository) {
    suspend fun rejection(call: ToolCall, session: String, operationId: String?, nativeLane: Boolean): ToolResult? {
        if (call.tool !in setOf(HarnessTool.TEAM, HarnessTool.APP_CONFIG)) return null
        val operation = operationId?.let { operations.findOperation(it) }
        val lane = operation?.let { operations.findLane(it.sessionId, it.laneName) }
        val allowed = nativeLane && operation != null && operation.sessionId == session &&
            operation.laneName == "main" && lane?.currentOperationId == operation.id &&
            !session.startsWith("workflow:") && !session.startsWith("subagent:")
        return if (allowed) null else ToolResult(java.util.UUID.randomUUID().toString(), System.currentTimeMillis(),
            call.id, false, "管理入口须由可信原生主Lane调用；后台Lane不能借用父会话身份采纳、拒绝或批准建议。请交回主会话处理。",
            errorCode = "MANAGEMENT_ACTOR_REQUIRED")
    }
}
