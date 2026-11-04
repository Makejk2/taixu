package top.wkbin.taixu.core.database

import androidx.room.*

@Dao
interface TeamSuggestionDao : TeamOwnershipDao {
    @Query("SELECT * FROM bot_assignments WHERE sessionId=:sessionId AND independent=1 LIMIT 1")
    suspend fun suggestionAssignment(sessionId: String): BotAssignmentEntity?
    @Query("SELECT * FROM group_members WHERE groupId=:groupId AND botId=:botId")
    suspend fun suggestionMember(groupId: String, botId: String): GroupMemberEntity?
    @Query("SELECT teamGroupId || '@' || teamVersion FROM group_chats WHERE id=:groupId AND teamVersion > 0")
    suspend fun suggestionBinding(groupId: String): String?
    @Query("SELECT * FROM team_suggestions WHERE id=:id AND groupId=:groupId AND (sourceSessionId=:sessionId OR EXISTS (SELECT 1 FROM team_group_owners WHERE groupId=:groupId AND ownerSessionId=:sessionId))")
    suspend fun visibleSuggestion(sessionId: String, groupId: String, id: String): TeamSuggestionEntity?
    @Query("SELECT * FROM team_suggestions WHERE groupId=:groupId AND (sourceSessionId=:sessionId OR EXISTS (SELECT 1 FROM team_group_owners WHERE groupId=:groupId AND ownerSessionId=:sessionId)) ORDER BY createdAt DESC LIMIT 100")
    suspend fun visibleSuggestions(sessionId: String, groupId: String): List<TeamSuggestionEntity>
    @Query("SELECT * FROM team_suggestions WHERE sourceSessionId=:sessionId AND idempotencyKey=:key")
    suspend fun suggestionByKey(sessionId: String, key: String): TeamSuggestionEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSuggestion(row: TeamSuggestionEntity)
    @Query("UPDATE team_suggestions SET status=:status, proposalId=:proposalId, decidedBy=:sessionId, decidedAt=:now WHERE id=:id AND groupId=:groupId AND status='pending' AND sourceRevision=:sourceRevision AND sourceTeamBinding=:sourceTeamBinding AND sourceMembershipStamp=:sourceMembershipStamp AND EXISTS (SELECT 1 FROM team_group_owners WHERE groupId=:groupId AND ownerSessionId=:sessionId)")
    suspend fun decideSuggestionRow(sessionId: String, groupId: String, id: String, status: String, proposalId: String?, now: Long, sourceRevision: Int, sourceTeamBinding: String, sourceMembershipStamp: String): Int

    suspend fun suggestionSource(sessionId: String, groupId: String): BotAssignmentEntity {
        val task = requireNotNull(suggestionAssignment(sessionId)) { "缺少原生成员身份" }
        require(task.status == "RUNNING" && task.groupId == groupId && suggestionMember(groupId, task.botId) != null) { "建议仅限运行中的本群成员" }
        require(task.teamBinding != null && task.teamBinding == suggestionBinding(groupId)) { "成员任务发布版本已失效" }
        val published = kotlinx.serialization.json.Json.decodeFromString<List<String>>(requireNotNull(teamStartPublishedMembers(groupId)))
        require(task.botId in published) { "成员不属于群聊绑定的发布版本" }
        val owner = requireNotNull(teamGroupOwner(groupId)) { "群聊缺少可信用户归属" }
        val root = requireNotNull(task.rootWorkId?.let { teamRootOwner(it) }) { "根任务缺少可信用户归属" }
        require(root.groupId == groupId && root.ownerSessionId == owner.ownerSessionId) { "根任务归属不一致" }
        return task
    }
    // No membership epoch exists: freeze every persisted membership field plus root/owner identity.
    private suspend fun membershipStamp(task: BotAssignmentEntity): String {
        val group = requireNotNull(task.groupId)
        val member = requireNotNull(suggestionMember(group, task.botId))
        return listOf(group, member.botId, member.displayOrder.toString(), member.joinedAt.toString(),
            member.supervisorBotId, task.rootWorkId, teamGroupOwner(group)?.ownerSessionId)
            .joinToString("") { if (it == null) "-1:" else "${it.length}:$it" }
    }
    private suspend fun verifySource(row: TeamSuggestionEntity) {
        val task = suggestionSource(row.sourceSessionId, row.groupId)
        require(task.id == row.sourceAssignmentId && task.revision == row.sourceRevision &&
            task.teamBinding == row.sourceTeamBinding && membershipStamp(task) == row.sourceMembershipStamp) {
            "建议来源已撤销或变更；必须重新建议"
        }
    }
    @Transaction suspend fun verifyAdoptedSource(sessionId: String, groupId: String, id: String, proposalId: String) {
        verifyMainUser(sessionId)
        require(teamGroupOwner(groupId)?.ownerSessionId == sessionId)
        val row = requireNotNull(visibleSuggestion(sessionId, groupId, id))
        require(row.status == "adopted" && row.proposalId == proposalId && row.decidedBy == sessionId)
        verifySource(row)
    }
    suspend fun suggestionReader(sessionId: String, groupId: String) {
        if (teamGroupOwner(groupId)?.ownerSessionId == sessionId) verifyMainUser(sessionId)
        else suggestionSource(sessionId, groupId)
    }
    @Transaction suspend fun listSuggestions(sessionId: String, groupId: String): List<TeamSuggestionEntity> {
        suggestionReader(sessionId, groupId)
        return visibleSuggestions(sessionId, groupId)
    }
    @Transaction suspend fun getSuggestion(sessionId: String, groupId: String, id: String): TeamSuggestionEntity? {
        suggestionReader(sessionId, groupId)
        return visibleSuggestion(sessionId, groupId, id)
    }
    @Transaction suspend fun suggest(sessionId: String, groupId: String, id: String, key: String,
        reason: String, operation: String, before: String, now: Long): TeamSuggestionEntity {
        val task = suggestionSource(sessionId, groupId)
        val old = suggestionByKey(sessionId, key)
        if (old != null) {
            require(old.groupId == groupId && old.sourceAssignmentId == task.id && old.reason == reason &&
                old.operationJson == operation && old.sourceRevision == task.revision &&
                old.sourceTeamBinding == task.teamBinding && old.sourceMembershipStamp == membershipStamp(task)) { "幂等键已用于不同建议" }
            return old
        }
        val row = TeamSuggestionEntity(id, groupId, task.id, sessionId, key, reason, operation, before, now,
            task.revision, requireNotNull(task.teamBinding), membershipStamp(task), "pending", null, null, null)
        insertSuggestion(row)
        return row
    }
    @Transaction suspend fun decideSuggestion(sessionId: String, groupId: String, id: String,
        status: String, proposalId: String?, now: Long): Boolean {
        verifyMainUser(sessionId)
        require(teamGroupOwner(groupId)?.ownerSessionId == sessionId) { "仅群聊所属主用户可处理建议" }
        require(status in setOf("adopted", "rejected") && (status == "adopted") == (proposalId != null))
        val row = visibleSuggestion(sessionId, groupId, id) ?: return false
        if (row.status != "pending") return false
        // Reject deliberately does not require a still-live source: owners can clear stale requests.
        if (status == "adopted") verifySource(row)
        return decideSuggestionRow(sessionId, groupId, id, status, proposalId, now,
            row.sourceRevision, row.sourceTeamBinding, row.sourceMembershipStamp) == 1
    }
}
