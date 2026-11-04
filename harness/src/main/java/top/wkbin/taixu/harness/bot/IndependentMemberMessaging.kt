package top.wkbin.taixu.harness.bot

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import top.wkbin.taixu.core.database.HarnessSessionRepository
import top.wkbin.taixu.core.database.repository.BotAssignmentRepository
import top.wkbin.taixu.core.database.repository.GroupChatRepository
import top.wkbin.taixu.core.model.bot.BotAssignmentStatus

/** Envelopes are constructed by native code. Peer text is data, not user approval or system policy. */
internal suspend fun sendIndependentMemberMessage(
    tasks: BotAssignmentRepository, groups: GroupChatRepository, sessions: HarnessSessionRepository,
    sourceSession: String, targetId: String, text: String, messageId: String,
    enqueue: suspend (String, String, String, String) -> String,
): String {
    require(text.isNotBlank() && text.length <= 8000) { "消息须为1至8000字符" }
    require(messageId.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "message_id须为1至100位字母、数字、点、下划线或连字符" }
    val source = requireNotNull(tasks.assignmentForSession(sourceSession)) { "仅独立成员任务可发送同伴消息" }
    require(source.independent && source.status == BotAssignmentStatus.RUNNING) { "来源成员任务未在运行" }
    val groupId = requireNotNull(source.groupId) { "任务缺少团队群" }
    val group = requireNotNull(groups.getGroup(groupId)) { "团队群已删除" }
    val target = requireNotNull(tasks.get(targetId)) { "目标独立任务不存在，请先list-assignments查询" }
    require(target.id != source.id && target.botId != source.botId) { "不能给自身任务发送同伴消息" }
    require(target.groupId == groupId && target.independent) { "不能跨群或向临时子智能体发信" }
    val binding = group.teamGroupId?.let { "$it@${group.teamVersion}" }
    require(source.teamBinding == binding && target.teamBinding == binding) { "团队版本已变化，旧任务不能通信" }
    val members = groups.getMembers(groupId).map { it.id }.toSet()
    require(source.botId in members && target.botId in members) { "发送方或接收方已移出团队" }
    require(target.status !in setOf(BotAssignmentStatus.CANCELLED, BotAssignmentStatus.FAILED, BotAssignmentStatus.INTERRUPTED)) {
        "目标任务已取消、失败或副作用待核对；不得用消息自动重启"
    }
    requireNotNull(target.sessionId?.let { sessions.findById(it) }) { "目标成员独立会话已删除" }
    // Source, membership, version, idempotency, quota and enqueue are rechecked in one Room transaction.
    // `enqueue` is retained only for source compatibility; using it would split charge from delivery.
    val receipt = tasks.sendCommunication(sourceSession, targetId, text, messageId)
    return if (receipt.state == "queued") receipt.id else buildJsonObject {
        put("message_id", receipt.id); put("state", receipt.state); put("hold_reason", receipt.holdReason)
        put("notice", when (receipt.state) {
            "held" -> "消息已持久保留尚未投递；预算或跳数不足请用户team propose/apply续额，原群原任务恢复；速率/队列背压解除后续送。"
            "cancelled" -> "此消息已终止，不会自动恢复或再次投递；请核对任务与成员状态后由用户决定后续操作。"
            else -> "返回持久状态供核对，不代表投递或执行完成；不会自动重放未知副作用。"
        })
    }.toString()
}

/** Peer discovery exposes routing metadata only, never a colleague's private task history. */
internal suspend fun independentMemberDirectory(tasks: BotAssignmentRepository, groups: GroupChatRepository, sourceSession: String): String {
    val source = requireNotNull(tasks.assignmentForSession(sourceSession)) { "仅独立成员任务可查询同伴收件目录" }
    require(source.independent && source.status == BotAssignmentStatus.RUNNING) { "来源成员任务未在运行" }
    val groupId = requireNotNull(source.groupId)
    val group = requireNotNull(groups.getGroup(groupId)) { "团队群已删除" }
    val binding = group.teamGroupId?.let { "$it@${group.teamVersion}" }
    require(source.teamBinding == binding) { "团队版本已变化" }
    val members = groups.getMembers(groupId).map { it.id }.toSet()
    require(source.botId in members) { "来源成员已移出团队" }
    return buildJsonArray {
        tasks.observeForGroup(groupId).first().filter {
            it.independent && it.botId != source.botId && it.botId in members && it.teamBinding == binding &&
                it.status !in setOf(BotAssignmentStatus.CANCELLED, BotAssignmentStatus.FAILED, BotAssignmentStatus.INTERRUPTED)
        }.take(100).forEach { row -> add(buildJsonObject {
            put("assignment_id", row.id); put("member_id", row.botId); put("member_name", row.botName)
            put("status", row.status.name)
        }) }
    }.toString()
}
