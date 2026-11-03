package top.wkbin.taixu.core.database

import androidx.room.Query

/** Queries composed into BotAssignmentDao; validation runs INSIDE its createIndependent transaction. */
interface NativeTeamStartDao {
    @Query("SELECT * FROM harness_sessions WHERE id = :id LIMIT 1")
    suspend fun findTeamStartSourceSession(id: String): HarnessSessionEntity?

    @Query("SELECT * FROM persona_bots WHERE sessionId = :sessionId OR id IN " +
        "(SELECT botId FROM bot_group_sessions WHERE sessionId = :sessionId) OR id IN " +
        "(SELECT botId FROM bot_assignments WHERE independent = 1 AND sessionId = :sessionId) LIMIT 1")
    suspend fun findTeamStartSourceBot(sessionId: String): BotEntity?

    @Query("SELECT v.memberIdsJson FROM team_versions v JOIN group_chats g ON g.teamGroupId = v.teamId AND g.teamVersion = v.version WHERE g.id = :groupId")
    suspend fun teamStartPublishedMembers(groupId: String): String?

    @Query("SELECT * FROM persona_bots WHERE id = :botId LIMIT 1")
    suspend fun findTeamStartTarget(botId: String): BotEntity?

}

/** Never call as a substitute for native approval; this is transaction-time identity revalidation. */
internal suspend fun BotAssignmentDao.validateNativeTeamRoot(
    task: BotAssignmentEntity, session: HarnessSessionEntity, batch: BotWorkBatchEntity?,
    sourceSessionId: String?, dispatchOperationId: String?, group: String,
    team: top.wkbin.taixu.core.model.bot.GroupTeam,
) {
            // Revalidate inside the admission transaction; a string prefix never grants authority.
            val sourceId = requireNotNull(sourceSessionId) { "缺少可信来源会话" }
            val source = requireNotNull(findTeamStartSourceSession(sourceId)) { "来源会话不存在" }
            require(findTeamStartSourceBot(sourceId) == null && assignmentForSession(sourceId) == null) { "来源不是主用户会话" }
            require(linkedTeamId(group) != null && (linkedVersion(group) ?: 0) > 0 && linkedDefinition(group) != null) { "群聊未绑定已发布团队" }
            require(task.reportToBotId == team.adminBotId && teamAdmin(group) == team.adminBotId) { "启动请求者必须是团队主控" }
            val publishedMembers = kotlinx.serialization.json.Json.decodeFromString<List<String>>(requireNotNull(teamStartPublishedMembers(group)))
            require(task.botId in publishedMembers && task.reportToBotId in publishedMembers) { "成员不属于已发布团队" }
            require(task.botId in team.memberIds && task.parentAssignmentId == null && task.batchId == null && batch == null) { "原生团队启动只能创建成员根任务" }
            require(!dispatchOperationId.isNullOrBlank() && !task.rootWorkId.isNullOrBlank() &&
                task.stageId == "delegate:root:${task.botId}" && task.workItemId == task.botId) { "缺少根任务幂等标识" }
            val expectedRoot = java.util.UUID.nameUUIDFromBytes(listOf(sourceId, group, requireNotNull(dispatchOperationId))
                .joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)).toString()
            require(task.rootWorkId == expectedRoot) { "根任务幂等标识与来源不匹配" }
            val workspace = source.workspace.ifBlank { "/workspace" }.replace('\\', '/')
            val base = java.nio.file.Paths.get(if (workspace.startsWith("/")) workspace else "/workspace/$workspace").normalize()
            val paths = kotlinx.serialization.json.Json.decodeFromString<List<String>>(task.writePathsJson)
            require(paths.all { raw ->
                val path = raw.replace('\\', '/')
                path.isNotBlank() && '\u0000' !in path && path.split('/').none { it == ".." } &&
                    base.resolve(path).normalize().startsWith(base)
            }) { "写入路径必须在来源工作区内" }
            require(task.proposalId == null && task.proposalVersion == null && governanceAllows(task.copy(teamBinding = linkedBinding(group)))) {
                "团队启动批准不能替代执行成员的方案审议"
            }
            val target = requireNotNull(findTeamStartTarget(task.botId)) { "执行成员不存在" }
            val approval = top.wkbin.taixu.core.model.ApprovalMode.entries.firstOrNull { it.id == source.approvalMode }?.id
                ?: top.wkbin.taixu.core.model.ApprovalMode.ASSISTED.id
            require(session.workspace == source.workspace && session.projectType == source.projectType &&
                session.executionTargetJson == source.executionTargetJson && session.approvalMode == approval &&
                session.runMode == source.runMode && session.modelId == target.modelId && session.modelVariant == target.modelVariant) {
                "根任务工作区、执行目标、权限或成员模型继承不匹配"
            }
}
