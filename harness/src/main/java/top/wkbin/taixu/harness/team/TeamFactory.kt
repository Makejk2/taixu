package top.wkbin.taixu.harness.team

import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import top.wkbin.taixu.core.database.HarnessSessionRepository
import top.wkbin.taixu.harness.HarnessPathResolver
import top.wkbin.taixu.harness.bot.IndependentTeamStart
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
    // Must be wired by the application. Missing trusted source infrastructure fails closed.
    sessions: HarnessSessionRepository? = null,
): TeamToolBackend = TeamToolBackend(TeamService(object : TeamPort {
    private suspend fun source(sessionId: String) = requireNotNull(
        requireNotNull(sessions) { "团队启动尚未装配可信会话仓库，未启动任何成员" }.findById(sessionId)
    ) { "来源会话不存在" }.also {
        require(!memberSession(sessionId)) { "成员会话不能作为团队启动来源" }
    }
    private val starter = IndependentTeamStart(object : IndependentTeamStart.Port {
        override suspend fun validate(sourceSessionId: String, published: TeamPublishedVersion, members: List<IndependentTeamStart.Member>) {
            val source = source(sourceSessionId)
            val workspace = source.workspace.ifBlank { "/workspace" }
            members.forEach { member ->
                requireNotNull(bots.getBot(member.botId)) { "成员已删除：${member.botId}" }
                require(member.writePaths.all { HarnessPathResolver().isWithinWorkspace(it, workspace) }) { "写入路径必须在来源工作区内" }

            }
            // A new group has new root budgets with the GroupChat default (0 = unlimited).
            // No existing budget is resumed/overridden; Runtime rechecks the transactional budget at admission.
        }
        override suspend fun admit(request: IndependentTeamStart.Request, member: IndependentTeamStart.Member): IndependentTeamStart.Receipt {
            // Internal native-approved path: the operation key is deduplication, never an approval claim.
            val task = independentRuntime().createApprovedTeamRoot(
                sourceSessionId = request.sourceSessionId, botId = member.botId, groupId = request.groupId,
                instruction = member.instruction, writePaths = member.writePaths,
                stableOperationKey = request.operationKey,
            )
            check(task.independent) { "返回任务并非独立任务" }
            return IndependentTeamStart.Receipt(member.botId, task.id, requireNotNull(task.sessionId))
        }
        override suspend fun cancel(groupId: String) {
            // Stop native jobs before changing their active status, then durably cancel all rows (including queued requests).
            val tasks = assignments.observeForGroup(groupId).first().filter { it.independent }
            var failure: Exception? = null
            try { independentRuntime().cancelGroup(groupId) } catch (e: Exception) { failure = e }
            tasks.forEach { task ->
                try { assignments.cancelAssignment(task.id) } catch (e: Exception) { failure = e }
            }
            failure?.let { throw it }
        }
    })
    override suspend fun startSourceSnapshot(sessionId: String) = Json.encodeToString(source(sessionId).let {
        buildJsonObject {
            put("id", it.id); put("workspace", it.workspace); put("projectType", it.projectType)
            put("approvalMode", it.approvalMode); put("runMode", it.runMode); put("executionTargetJson", it.executionTargetJson)
        }
    })
    override suspend fun validateStart(sessionId: String, published: TeamPublishedVersion, members: List<IndependentTeamStart.Member>) =
        starter.validate(sessionId, published, members)
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
    override suspend fun start(request: IndependentTeamStart.Request): IndependentTeamStart.Result {
        val actual = requireNotNull(groups.getGroup(request.groupId)) { "群聊不存在" }
        require(actual.teamGroupId == request.published.teamId && actual.teamVersion == request.published.version) { "群聊发布版本不一致" }
        require(actual.taskBudgetTokens >= 0) { "任务预算无效" }
        return starter.start(request)
    }
    override suspend fun stop(groupId: String) {
        // Includes independently admitted member sessions as well as round/delivery/stream work.
        try { independentRuntime().cancelGroup(groupId) }
        finally {
            assignments.observeForGroup(groupId).first().filter { it.independent }.forEach { assignments.cancelAssignment(it.id) }
            dispatcher().cancelGroup(groupId)
        }
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
