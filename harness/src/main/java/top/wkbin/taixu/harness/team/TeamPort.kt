package top.wkbin.taixu.harness.team

import kotlinx.serialization.json.JsonObject
import top.wkbin.taixu.harness.bot.IndependentTeamStart
import top.wkbin.taixu.core.model.bot.TeamPublishedVersion
import top.wkbin.taixu.core.model.bot.BotMemberGroup
import top.wkbin.taixu.core.model.bot.GroupChat

/** All implementations are native app infrastructure, never model-provided callbacks. */
internal interface TeamPort {
    suspend fun memberSession(sessionId: String): Boolean
    suspend fun teams(): List<BotMemberGroup>
    suspend fun botExists(id: String): Boolean
    suspend fun memberSnapshot(id: String): String
    suspend fun createMember(spec: JsonObject): String
    suspend fun deleteMember(id: String)
    suspend fun save(team: BotMemberGroup)
    suspend fun publish(id: String, revision: Int): Int
    suspend fun group(id: String): GroupChat?
    suspend fun createGroup(group: GroupChat, teamId: String)
    suspend fun createOwnedGroup(group: GroupChat, teamId: String, sourceSessionId: String) {
        error("可信团队建群未装配")
    }
    suspend fun startSourceSnapshot(sessionId: String): String
    suspend fun validateStart(sessionId: String, published: TeamPublishedVersion, members: List<IndependentTeamStart.Member>)
    suspend fun start(request: IndependentTeamStart.Request): IndependentTeamStart.Result
    suspend fun stop(groupId: String)
    suspend fun communicationSnapshot(groupId: String): top.wkbin.taixu.core.model.bot.TeamCommunicationSnapshot =
        error("持久通信仓库尚未装配")
    suspend fun communicationPolicy(groupId: String, expectedRevision: Int,
        policy: top.wkbin.taixu.core.model.bot.TeamCommunicationPolicy, sourceSession: String, approvalId: String) {
        error("持久通信仓库尚未装配")
    }
    suspend fun status(groupId: String): String
}

internal fun interface TeamAudit {
    suspend fun record(sessionId: String, action: String, detail: String)
}
