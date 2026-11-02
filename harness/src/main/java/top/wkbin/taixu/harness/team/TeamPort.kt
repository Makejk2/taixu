package top.wkbin.taixu.harness.team

import kotlinx.serialization.json.JsonObject
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
    suspend fun start(groupId: String, task: String)
    suspend fun stop(groupId: String)
    suspend fun status(groupId: String): String
}

internal fun interface TeamAudit {
    suspend fun record(sessionId: String, action: String, detail: String)
}
