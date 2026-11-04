package top.wkbin.taixu.harness.rules

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import top.wkbin.taixu.core.database.UserRuleRepository
import top.wkbin.taixu.core.database.repository.BotAssignmentRepository
import top.wkbin.taixu.core.database.repository.PersonaBotRepository
import top.wkbin.taixu.harness.*

/** Restrictive policies run before ANY ordinary tool side effect, including approved replay. */
class UserRuleToolGuard(private val rules: UserRuleRepository,
    private val tasks: BotAssignmentRepository, private val bots: PersonaBotRepository) {
    private val engine = RuleEngine(rules)
    private val reviewed = ConcurrentHashMap<String, Map<String, Long>>()

    suspend fun check(call: ToolCall, session: String, workspace: String, operation: String?,
        nativeReplay: Boolean, request: suspend (ApprovalDecision) -> ToolResult): ToolResult? {
        // Native management has its own stronger gate; keep an authorized repair path for bad rules.
        if (call.tool in setOf(HarnessTool.APP_CONFIG, HarnessTool.TEAM)) return null
        fun blocked(message: String, code: String) = ToolResult(UUID.randomUUID().toString(),
            System.currentTimeMillis(), call.id, false, message, errorCode=code,
            policyPaused = code in setOf("USER_RULE_ROLE_RESTRICTED","USER_RULE_BLOCKED","USER_RULE_BUDGET_PAUSED"))
        return try {
            // With no user policy configured, preserve legacy call sites without an operation.
            if (rules.list().none { it.enabled }) return null
            val task = tasks.assignmentForSession(session)
            val owner = task?.rootWorkId ?: task?.id ?: session
            require(owner.isNotBlank() && !operation.isNullOrBlank())
            val bot = task?.botId?.let { bots.getBot(it) } ?: bots.getBotBySessionId(session)
            val key = "$session:$operation:${call.id}"
            val counted = rules.hasReceipt(owner, key)
            val consumed = rules.consumed(owner)
            val context = RuleExecutionContext(session, workspace,
                call.rawToolName ?: HarnessApiMapper.apiName(call.tool),
                if (task != null) "independent_member" else "user_session", bot?.role, if (counted) (consumed - 1).coerceAtLeast(0) else consumed)
            val verdict = engine.evaluate(context)
            if (verdict.allowedMemberRoles != null && (bot == null || bot.role !in verdict.allowedMemberRoles))
                return blocked("当前成员角色不在用户规则允许范围内；原任务保留，请交由获准角色处理或由用户调整规则。未执行。", "USER_RULE_ROLE_RESTRICTED")
            if (!verdict.mayExecute) return blocked("用户执行规则阻止或暂停此动作；请由原生管理调整规则/预算后继续，未执行。", "USER_RULE_BLOCKED")
            if (verdict.requireNativeReview && (!nativeReplay || reviewed[key] != verdict.storageRevisions)) {
                reviewed[key] = verdict.storageRevisions
                return request(ApprovalDecision(true, "critical", "用户规则要求逐次原生审阅：" +
                    verdict.matchedRuleIds.joinToString() + "。只批准当前工具和规则版本，不提升其他权限。\n" +
                    top.wkbin.taixu.harness.subagent.workflowToolPreview(call,workspace), "执行规则审阅"))
            }
            // Same original call is reserved once across pause/replay. Never refund uncertain effects.
            if (!rules.reserveReviewed(owner, key, verdict.maxToolCalls, verdict.storageRevisions)) return blocked(
                "持久规则预算已达上限；任务与上下文保留，调整预算后继续。", "USER_RULE_BUDGET_PAUSED")
            reviewed.remove(key)
            null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { blocked("用户规则或原生执行身份暂不可核验；未执行。", "USER_RULE_UNAVAILABLE") }
    }
}
