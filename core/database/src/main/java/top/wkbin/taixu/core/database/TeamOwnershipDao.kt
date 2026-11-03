package top.wkbin.taixu.core.database

import androidx.room.*
import kotlinx.serialization.json.*

/** Composed into the native group/root creation transactions; not exposed to model tools. */
interface TeamOwnershipDao : NativeTeamStartDao {
    @Query("SELECT * FROM team_group_owners WHERE groupId=:groupId")
    suspend fun teamGroupOwner(groupId: String): TeamGroupOwnerEntity?
    @Query("SELECT * FROM team_root_owners WHERE rootWorkId=:rootWorkId")
    suspend fun teamRootOwner(rootWorkId: String): TeamRootOwnerEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTeamGroupOwner(owner: TeamGroupOwnerEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTeamRootOwner(owner: TeamRootOwnerEntity)

    @Query("SELECT * FROM group_chats WHERE id=:groupId")
    suspend fun legacyGroup(groupId: String): GroupChatEntity?
    @Query("SELECT v.* FROM team_versions v JOIN group_chats g ON g.teamGroupId=v.teamId AND g.teamVersion=v.version WHERE g.id=:groupId")
    suspend fun legacyVersion(groupId: String): TeamVersionEntity?
    @Query("SELECT * FROM bot_assignments WHERE groupId=:groupId ORDER BY id")
    suspend fun legacyTasks(groupId: String): List<BotAssignmentEntity>
    @Query("SELECT * FROM bot_assignments WHERE rootWorkId=:rootId ORDER BY id")
    suspend fun legacyRootTasks(rootId: String): List<BotAssignmentEntity>
    @Query("SELECT * FROM group_members WHERE groupId=:groupId ORDER BY botId")
    suspend fun legacyMembers(groupId: String): List<GroupMemberEntity>
    // INSERT SELECT is an owner-null CAS, not IGNORE: unexpected conflicts must propagate.
    @Query("INSERT INTO team_group_owners(groupId,ownerSessionId) SELECT :groupId,:sessionId WHERE NOT EXISTS (SELECT 1 FROM team_group_owners WHERE groupId=:groupId)")
    suspend fun claimLegacyGroup(groupId: String, sessionId: String)
    @Query("SELECT changes()")
    suspend fun legacyClaimChanges(): Int
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLegacyAudit(row: AgentApprovalAuditEntity)

    /** Exact, deterministic native facts; no caller-supplied attribution or historical-owner claim. */
    @Transaction
    suspend fun legacyOwnershipSnapshot(sessionId: String, groupId: String): String {
        verifyMainUser(sessionId)
        require(teamGroupOwner(groupId) == null) { "群聊已有 owner，禁止接管（含其他用户争用）" }
        val group = requireNotNull(legacyGroup(groupId)) { "群聊不存在" }
        val version = requireNotNull(legacyVersion(groupId)) { "旧群缺少可核验发布版本" }
        require(version.version > 0) { "旧群未绑定有效发布版本" }
        val tasks = legacyTasks(groupId)
        val roots = tasks.mapNotNull { it.rootWorkId }.distinct().sorted()
        val allRootTasks = roots.flatMap { root ->
            require(teamRootOwner(root) == null) { "根任务已有 owner，禁止抢夺：$root" }
            legacyRootTasks(root).also { rows ->
                require(rows.isNotEmpty() && rows.all { it.groupId == groupId }) { "根任务跨群归属冲突：$root" }
                require(rows.any { it.parentAssignmentId == null }) { "缺少可核验根任务：$root" }
            }
        }
        val sessions = (tasks + allRootTasks).mapNotNull { it.sessionId }.plus(sessionId).distinct().sorted()
        return buildJsonObject {
            put("attribution", "legacy reauthorized; not historical ownership")
            put("ownerSessionId", sessionId)
            put("sourceWorkspace", requireNotNull(findTeamStartSourceSession(sessionId)).workspace)
            put("group", nativeRow(group))
            put("publishedVersion", nativeRow(version))
            put("members", buildJsonArray { legacyMembers(groupId).forEach { add(nativeRow(it)) } })
            put("tasks", buildJsonArray { tasks.forEach { add(nativeRow(it)) } })
            put("rootTasks", buildJsonArray { allRootTasks.distinctBy { it.id }.sortedBy { it.id }.forEach { add(nativeRow(it)) } })
            put("sessions", buildJsonArray { sessions.forEach { id ->
                add(buildJsonObject { put("id", id); put("snapshot", findTeamStartSourceSession(id)?.let { nativeRow(it) } ?: JsonNull) })
            } })
        }.toString()
    }

    /** Called only by the native approved apply path. Never resumes work or copies old approvals. */
    @Transaction
    suspend fun reauthorizeLegacyGroup(sessionId: String, groupId: String, expected: String, approvalId: String) {
        require(approvalId.isNotBlank()) { "缺少原生批准关联" }
        require(legacyOwnershipSnapshot(sessionId, groupId) == expected) { "旧群接管快照已变化，请重新批准" }
        claimLegacyGroup(groupId, sessionId)
        check(legacyClaimChanges() == 1) { "旧群 owner-null CAS 争用，未接管" }
        legacyTasks(groupId).mapNotNull { it.rootWorkId }.distinct().forEach { root ->
            insertTeamRootOwner(TeamRootOwnerEntity(root, groupId, sessionId))
        }
        insertLegacyAudit(AgentApprovalAuditEntity(
            id = java.util.UUID.randomUUID().toString(), sessionId = sessionId,
            kind = "team_legacy_reauthorized", toolName = "team",
            detail = "nativeProposalId=$approvalId\n" + expected + "\nOwnership only; no task activation or historical approval restoration.",
            createdAt = System.currentTimeMillis(),
        ))
    }

    suspend fun verifyMainUser(sessionId: String) {
        require(!sessionId.startsWith("workflow:") && !sessionId.startsWith("subagent:")) { "派生会话不得取得主用户归属" }
        require(sessionId.isNotBlank() && findTeamStartSourceSession(sessionId) != null &&
            nativeTeamSourceBotCount(sessionId) == 0) { "来源不是原生主用户会话" }
    }

    suspend fun bindNativeRootOwner(groupId: String, rootWorkId: String, sourceSessionId: String) {
        verifyMainUser(sourceSessionId)
        val groupOwner = teamGroupOwner(groupId)
        requireNotNull(groupOwner) { "旧群尚未明确重新授权，禁止首次访问自动接管" }
        require(groupOwner.ownerSessionId == sourceSessionId) { "群聊不属于当前主用户会话" }
        val existing = teamRootOwner(rootWorkId)
        if (existing == null) insertTeamRootOwner(TeamRootOwnerEntity(rootWorkId, groupId, sourceSessionId))
        else require(existing.groupId == groupId && existing.ownerSessionId == sourceSessionId) { "根任务归属冲突" }
    }
}

/** Explicit primitive columns: JSON escaping prevents ambiguous concatenated snapshots. */
private fun nativeRow(row: GroupChatEntity): JsonObject = buildJsonObject {
    put("id", row.id)
    put("name", row.name)
    put("adminBotId", row.adminBotId)
    put("description", row.description)
    put("createdAt", row.createdAt)
    put("background", row.background)
    put("replyMode", row.replyMode)
    put("taskBudgetTokens", row.taskBudgetTokens)
    put("executionLimitsJson", row.executionLimitsJson)
    put("teamGroupId", row.teamGroupId)
    put("teamVersion", row.teamVersion)
    put("unifiedApprovalMode", row.unifiedApprovalMode)
}
private fun nativeRow(row: TeamVersionEntity): JsonObject = buildJsonObject {
    put("teamId", row.teamId)
    put("version", row.version)
    put("name", row.name)
    put("memberIdsJson", row.memberIdsJson)
    put("definitionJson", row.definitionJson)
    put("publishedAt", row.publishedAt)
}
private fun nativeRow(row: GroupMemberEntity): JsonObject = buildJsonObject {
    put("groupId", row.groupId)
    put("botId", row.botId)
    put("displayOrder", row.displayOrder)
    put("joinedAt", row.joinedAt)
    put("supervisorBotId", row.supervisorBotId)
}
private fun nativeRow(row: BotAssignmentEntity): JsonObject = buildJsonObject {
    put("id", row.id)
    put("groupId", row.groupId)
    put("botId", row.botId)
    put("botName", row.botName)
    put("title", row.title)
    put("task", row.task)
    put("status", row.status)
    put("writePathsJson", row.writePathsJson)
    put("createdAt", row.createdAt)
    put("startedAt", row.startedAt)
    put("finishedAt", row.finishedAt)
    put("summary", row.summary)
    put("error", row.error)
    put("sessionId", row.sessionId)
    put("laneName", row.laneName)
    put("reportToBotId", row.reportToBotId)
    put("independent", row.independent)
    put("revision", row.revision)
    put("activeRequestId", row.activeRequestId)
    put("dependsOnJson", row.dependsOnJson)
    put("batchId", row.batchId)
    put("parentAssignmentId", row.parentAssignmentId)
    put("budgetId", row.budgetId)
    put("proposalId", row.proposalId)
    put("proposalVersion", row.proposalVersion)
    put("teamBinding", row.teamBinding)
    put("rootWorkId", row.rootWorkId)
    put("stageId", row.stageId)
    put("workItemId", row.workItemId)
}
// Freeze session identity/authority, not chat activity timestamps or cosmetic title/pinning.
private fun nativeRow(row: HarnessSessionEntity): JsonObject = buildJsonObject {
    put("id", row.id)
    put("createdAt", row.createdAt)
    put("modelId", row.modelId)
    put("modelVariant", row.modelVariant)
    put("workspace", row.workspace)
    put("projectType", row.projectType)
    put("approvalMode", row.approvalMode)
    put("runMode", row.runMode)
    put("executionTargetJson", row.executionTargetJson)
}
