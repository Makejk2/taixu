package top.wkbin.taixu.core.database

import top.wkbin.taixu.core.model.bot.TeamSuggestion

/** Native session IDs only. No public owner mutation or unscoped suggestion lookup. */
interface TeamSuggestionRepository {
    suspend fun legacyOwnershipSnapshot(sessionId: String, groupId: String): String = error("旧群接管未装配")
    suspend fun reauthorizeLegacyGroup(sessionId: String, groupId: String, expected: String, approvalId: String) {
        error("旧群接管未装配")
    }
    suspend fun list(sessionId: String, groupId: String): List<TeamSuggestion>
    suspend fun get(sessionId: String, groupId: String, id: String): TeamSuggestion?
    suspend fun requireOwner(sessionId: String, groupId: String)
    suspend fun verifyAdoptedSource(sessionId: String, groupId: String, id: String, proposalId: String)
    suspend fun requireMember(sessionId: String, groupId: String)
    suspend fun suggest(sessionId: String, groupId: String, id: String, key: String,
        reason: String, operation: String, before: String, now: Long): TeamSuggestion
    suspend fun decide(sessionId: String, groupId: String, id: String, status: String, proposalId: String?, now: Long): Boolean
}
class RoomTeamSuggestionRepository(private val dao: TeamSuggestionDao) : TeamSuggestionRepository {
    override suspend fun legacyOwnershipSnapshot(sessionId: String, groupId: String) = dao.legacyOwnershipSnapshot(sessionId, groupId)
    override suspend fun reauthorizeLegacyGroup(sessionId: String, groupId: String, expected: String, approvalId: String) =
        dao.reauthorizeLegacyGroup(sessionId, groupId, expected, approvalId)
    override suspend fun list(sessionId: String, groupId: String) = dao.listSuggestions(sessionId, groupId).map { it.model() }
    override suspend fun get(sessionId: String, groupId: String, id: String) = dao.getSuggestion(sessionId, groupId, id)?.model()
    override suspend fun requireOwner(sessionId: String, groupId: String) {
        dao.verifyMainUser(sessionId)
        require(dao.teamGroupOwner(groupId)?.ownerSessionId == sessionId) { "仅群聊所属主用户可处理建议" }
    }
    override suspend fun verifyAdoptedSource(sessionId: String, groupId: String, id: String, proposalId: String) =
        dao.verifyAdoptedSource(sessionId, groupId, id, proposalId)
    override suspend fun requireMember(sessionId: String, groupId: String) { dao.suggestionSource(sessionId, groupId) }
    override suspend fun suggest(sessionId: String, groupId: String, id: String, key: String,
        reason: String, operation: String, before: String, now: Long) =
        dao.suggest(sessionId, groupId, id, key, reason, operation, before, now).model()
    override suspend fun decide(sessionId: String, groupId: String, id: String, status: String, proposalId: String?, now: Long) =
        dao.decideSuggestion(sessionId, groupId, id, status, proposalId, now)
}
private fun TeamSuggestionEntity.model() = TeamSuggestion(id, groupId, sourceAssignmentId, sourceSessionId,
    idempotencyKey, reason, operationJson, beforeSnapshot, createdAt, sourceRevision, sourceTeamBinding, sourceMembershipStamp, status, proposalId, decidedBy, decidedAt)
