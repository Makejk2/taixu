package top.wkbin.taixu.harness.approval

import java.util.UUID
import top.wkbin.taixu.core.database.AgentApprovalRepository
import top.wkbin.taixu.harness.*
import top.wkbin.taixu.harness.events.HarnessEvent
import top.wkbin.taixu.harness.events.HarnessEventBus

/** Shared native pending-request lifecycle; background lanes defer without executing. */
internal suspend fun requestNativeApproval(
    approvalRepository: AgentApprovalRepository?, approvalPolicyEngine: ApprovalPolicyEngine,
    approvalAuditor: ApprovalAuditRecorder?, eventBus: HarnessEventBus?,
    toolCall: ToolCall, sessionId: String, workspace: String, decision: ApprovalDecision,
    operationId: String?, allowApprovalRequest: Boolean, now: Long,
): ToolResult {
        if (!allowApprovalRequest) {
            // 后台 Lane 没有可暂停的审批 UI，只能结构化交接：标记 approvalDeferred，
            // 由 Lane 收集成待办上交父智能体。若只回一句失败文字，模型下一轮会输出
            // "已交由主智能体"，而那句话曾被当成完成结论。
            approvalAuditor?.deferred(
                sessionId, toolCall.id,
                toolCall.rawToolName ?: toolCall.tool.name.lowercase(),
                decision.riskLevel, decision.reason,
            )
            return ToolResult(
                id = UUID.randomUUID().toString(),
                createdAt = now,
                toolCallId = toolCall.id,
                success = false,
                output = buildString {
                    append("该工具需要用户审批（${decision.summary}），子智能体后台 Lane 不支持暂停审批，本次调用未执行。")
                    append("\n原因：").append(decision.reason)
                    append("\n请不要重试同一调用，也不要声称已完成：把该操作作为待办写进结论，")
                    append("由主智能体在主会话重新发起并等待用户批准。")
                },
                approvalDeferred = true,
            )
        }
        val repository = checkNotNull(approvalRepository) { "审批仓库未初始化" }
        val request = approvalPolicyEngine.createRequest(sessionId, toolCall, workspace, decision, operationId)
        repository.create(request)
        eventBus?.emit(
            HarnessEvent.ApprovalRequested(
                sessionId = sessionId,
                timestamp = now,
                operationId = operationId,
                approvalRequestId = request.id,
                toolName = request.toolName,
                riskLevel = request.riskLevel,
            ),
        )
        return ToolResult(
            id = UUID.randomUUID().toString(),
            createdAt = now,
            toolCallId = toolCall.id,
            success = false,
            output = "等待用户批准：${decision.summary}\n${decision.reason}",
            awaitingApproval = true,
            approvalRequestId = request.id,
        )
}
