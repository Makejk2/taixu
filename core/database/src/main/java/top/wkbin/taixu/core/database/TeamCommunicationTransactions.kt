package top.wkbin.taixu.core.database

import kotlinx.serialization.json.Json
import top.wkbin.taixu.core.model.bot.TeamCommunicationMessage
import top.wkbin.taixu.core.model.bot.TeamCommunicationPolicy
import top.wkbin.taixu.core.model.bot.TeamCommunicationSnapshot

/** Helpers are only invoked by BotAssignmentDao's @Transaction entry points. */
internal suspend fun BotAssignmentDao.communicationSnapshotNative(groupId: String): TeamCommunicationSnapshot {
    val row = communicationPolicy(groupId)
    return TeamCommunicationSnapshot(row.policy(), row?.revision ?: 0,
        communicationCharged(groupId), communicationHeldCount(groupId))
}

internal suspend fun BotAssignmentDao.approveCommunicationPolicyNative(
    groupId: String, expectedRevision: Int, policy: TeamCommunicationPolicy,
    sourceSession: String, approvalId: String, now: Long,
) {
    policy.validate()
    require(sourceSession.isNotBlank() && approvalId.isNotBlank() && communicationSessionExists(sourceSession))
    require(nativeTeamSourceBotCount(sourceSession) == 0 && assignmentForSession(sourceSession) == null) {
        "普通 Bot、群聊及成员任务会话不能授权通信预算"
    }
    require(linkedTeamId(groupId) != null) { "仅支持原有团队群" }
    val old = communicationPolicy(groupId)
    require((old?.revision ?: 0) == expectedRevision) { "通信策略修订已变化，请重新提案" }
    require(expectedRevision in 0 until Int.MAX_VALUE)
    saveCommunicationPolicy(TeamCommunicationPolicyEntity(groupId, Json.encodeToString(policy),
        expectedRevision + 1, sourceSession, approvalId, now))
}

internal suspend fun BotAssignmentDao.readCommunicationInboxNative(
    sourceSession: String, after: Long, limit: Int,
): List<TeamCommunicationMessage> {
    require(after >= 0 && limit in 1..100)
    require(communicationSessionExists(sourceSession)) { "成员会话已删除" }
    val task = requireNotNull(assignmentForSession(sourceSession))
    require(task.independent && task.status == "RUNNING" && hasOpenTaskAncestry(task)) {
        "仅运行中成员可读自己的收件箱"
    }
    val group = requireNotNull(task.groupId)
    val binding = linkedBinding(group)
    require(task.teamBinding == binding && deliveryMember(group, task.botId)) {
        "团队版本或成员归属已变化"
    }
    val result = mutableListOf<TeamCommunicationMessage>()
    var cursor = after
    // Filter INSIDE the transaction, then keep scanning: an unauthorized early row must not
    // produce an empty page that hides later authorized deliveries. Never read held rows.
    while (result.size < limit) {
        val page = communicationInbox(task.id, cursor, limit - result.size)
        if (page.isEmpty()) break
        for (row in page) {
            cursor = requireNotNull(row.deliverySequence)
            if (row.groupId != group || row.teamBinding != binding) continue
            val sender = findById(row.sourceAssignmentId) ?: continue
            if (!sender.independent || sender.botId != row.sourceMemberId || sender.groupId != group ||
                sender.teamBinding != binding || !deliveryMember(group, sender.botId) ||
                sender.status in setOf("CANCELLED", "FAILED", "INTERRUPTED") ||
                sender.sessionId?.let { communicationSessionExists(it) } != true ||
                !hasOpenTaskAncestry(sender)) continue
            result += row.model()
        }
    }
    return result
}
