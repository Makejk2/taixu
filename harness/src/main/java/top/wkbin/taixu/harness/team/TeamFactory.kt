package top.wkbin.taixu.harness.team

import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import top.wkbin.taixu.core.database.AgentApprovalAuditEntity
import top.wkbin.taixu.core.database.AgentApprovalAuditRepository
import top.wkbin.taixu.core.database.repository.*
import top.wkbin.taixu.core.model.bot.*
import top.wkbin.taixu.harness.bot.BotAssignmentRuntime
import top.wkbin.taixu.harness.bot.BotOrchestrator
import top.wkbin.taixu.harness.groupchat.GroupChatDispatcher

/** Production factory. Reuses the real Room repositories and application-owned group runtime. */
fun createTeamBackend(
    teams: BotMemberGroupRepository,
    groups: GroupChatRepository,
    bots: PersonaBotRepository,
    assignments: BotAssignmentRepository,
    orchestrator: () -> BotOrchestrator,
    dispatcher: () -> GroupChatDispatcher,
    independentRuntime: () -> BotAssignmentRuntime,
    audit: AgentApprovalAuditRepository,
): TeamToolBackend = TeamToolBackend(TeamService(object : TeamPort {
    override suspend fun memberSession(sessionId: String): Boolean =
        assignments.assignmentForSession(sessionId) != null ||
            bots.getBotBySessionId(sessionId) != null ||
            sessionId in bots.observeGroupSessionRefs().first()

    override suspend fun teams() = teams.observeGroups().first()
    override suspend fun botExists(id: String) = bots.getBot(id) != null
    override suspend fun memberSnapshot(id: String): String = Json.encodeToString(requireNotNull(bots.getBot(id)) { "成员已删除：$id" })
    override suspend fun createMember(spec: JsonObject): String {
        fun value(key: String) = (spec[key] as? JsonPrimitive)?.content
        // createBot prepares persistent native session and persona memory, not a lane.
        return orchestrator().createBot(
            name = requireNotNull(value("name")), role = requireNotNull(value("role")),
            systemPrompt = requireNotNull(value("systemPrompt")), modelId = value("modelId"),
            modelVariant = value("modelVariant"), isMainBot = false, allowModelSelection = false,
        ).id
    }
    override suspend fun deleteMember(id: String) = orchestrator().deleteBot(id)
    // saveGroup invokes the DAO revision-CAS transaction; unlike saveIndependentTeam it does NOT auto-publish.
    override suspend fun save(team: BotMemberGroup) = teams.saveGroup(team)
    override suspend fun publish(id: String, revision: Int) = teams.publish(id, revision, emptySet())
    override suspend fun group(id: String) = groups.getGroup(id)
    override suspend fun createGroup(group: GroupChat, teamId: String) = groups.createReferencedGroup(group, teamId)
    override suspend fun start(groupId: String, task: String) = dispatcher().onUserSend(groupId, task)
    override suspend fun stop(groupId: String) {
        // Includes independently admitted member sessions as well as round/delivery/stream work.
        independentRuntime().cancelGroup(groupId)
        dispatcher().cancelGroup(groupId)
    }
    override suspend fun status(groupId: String): String {
        val group = requireNotNull(groups.getGroup(groupId)) { "群聊不存在" }
        val tasks = assignments.observeForGroup(groupId).first()
        val deliveries = groups.listDeliveries(groupId)
        val rounds = groups.runningRounds().filter { it.groupId == groupId }
        val late = groups.watchingLateReplies().filter { it.groupId == groupId }
        val messages = groups.observeMessages(groupId).first()
        return buildJsonObject {
            put("group", Json.encodeToJsonElement(group))
            put("tasks", buildJsonArray { tasks.forEach { task -> add(buildJsonObject {
                put("id", task.id); put("memberId", task.botId); put("status", task.status.name)
                put("sessionId", task.sessionId); put("independent", task.independent)
            }) } })
            put("deliveries", buildJsonArray { deliveries.forEach { delivery -> add(buildJsonObject {
                put("id", delivery.id); put("status", delivery.status)
            }) } })
            put("runningRounds", buildJsonArray { rounds.forEach { add(it.id) } })
            put("watchingLateReplies", buildJsonArray { late.forEach { add(it.id) } })
            put("streamingMessageIds", buildJsonArray { messages.filter { it.isStreaming }.forEach { add(it.id) } })
            put("note", "持久事实快照；unknown/中断结果须核对原生任务，不会自动重跑。停止只取消当前工作，不删除团队。")
        }.toString()
    }
}, TeamAudit { session, action, detail ->
    audit.record(AgentApprovalAuditEntity(
        id = UUID.randomUUID().toString(), sessionId = session,
        kind = "team_$action", toolName = TeamToolSpec.name,
        detail = detail, createdAt = System.currentTimeMillis(),
    ))
}))
