package top.wkbin.taixu.harness.bot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import top.wkbin.taixu.core.model.bot.TeamPublishedVersion

/** Native admission coordinator: no planner/model turn is involved. Receipt means queued, not completed. */
internal class IndependentTeamStart(private val port: Port) {
    data class Member(val botId: String, val instruction: String, val writePaths: List<String> = emptyList())
    data class Request(val groupId: String, val sourceSessionId: String, val operationKey: String,
        val published: TeamPublishedVersion, val members: List<Member>)
    data class Receipt(val botId: String, val assignmentId: String, val sessionId: String)
    data class Result(val receipts: List<Receipt>)

    interface Port {
        /** Must validate ALL members, source identity, paths, routing, governance and budget without admission. */
        suspend fun validate(sourceSessionId: String, published: TeamPublishedVersion, members: List<Member>)
        suspend fun admit(request: Request, member: Member): Receipt
        /** Cancel the entire new group, including a commit whose acknowledgement was lost. */
        suspend fun cancel(groupId: String)
    }

    suspend fun validate(sourceSessionId: String, published: TeamPublishedVersion, members: List<Member>) {
        require(sourceSessionId.isNotBlank()) { "缺少可信来源会话" }
        require(members.size in 1..30 && members.map { it.botId }.distinct().size == members.size) { "启动成员须唯一且为1至30人" }
        require(members.all { it.botId in published.memberIds }) { "启动成员不属于已发布团队" }
        require(members.all { it.instruction.isNotBlank() && it.instruction.length <= 30_000 }) { "成员任务须为1至30000字" }
        require(members.all { it.writePaths.size <= 100 && it.writePaths.distinct().size == it.writePaths.size }) { "成员写入路径重复或过多" }
        published.definition.validate(published.memberIds)
        val team = published.definition.team(published.memberIds)
        // A team-management approval is NOT a review-rule approval. Never bypass a controlled executor.
        require(members.none { published.definition.governance.executionRules(team, it.botId).isNotEmpty() }) {
            "启动包含受审议规则控制的执行成员；须先通过原生方案审议，不能用团队启动批准替代"
        }
        port.validate(sourceSessionId, published, members)
    }

    suspend fun start(request: Request): Result {
        require(request.groupId.isNotBlank() && request.operationKey.startsWith("team-start:")) { "缺少原生批准操作标识" }
        validate(request.sourceSessionId, request.published, request.members)
        val receipts = mutableListOf<Receipt>()
        try {
            // Only admission is sequential. Every receipt owns a native root session; runtime pumps them independently.
            request.members.forEach { member ->
                val receipt = port.admit(request, member)
                receipts += receipt // retain even a malformed acknowledgement for reconciliation
                check(receipt.botId == member.botId && receipt.assignmentId.isNotBlank() && receipt.sessionId.isNotBlank()) { "成员受理回执无效" }
                check(receipts.map { it.assignmentId }.distinct().size == receipts.size &&
                    receipts.map { it.sessionId }.distinct().size == receipts.size) { "成员未获得独立任务/会话" }
            }
            return Result(receipts)
        } catch (failure: Exception) {
            var cleanupFailure: String? = null
            withContext(NonCancellable) {
                try { port.cancel(request.groupId) } catch (e: Exception) { cleanupFailure = e.message ?: e::class.simpleName }
            }
            val detail = "团队启动未完成；groupId=${request.groupId}；已受理=$receipts；未继续受理其余成员；" +
                (cleanupFailure?.let { "停止未确认=$it；必须 status 核对" } ?: "已请求取消本群全部任务（含回执未知项）") +
                "；禁止自动重放。${failure.message}"
            if (failure is CancellationException) throw CancellationException(detail).also { it.initCause(failure) }
            throw IllegalStateException(detail, failure)
        }
    }
}
