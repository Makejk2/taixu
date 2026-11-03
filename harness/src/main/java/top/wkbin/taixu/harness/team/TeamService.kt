package top.wkbin.taixu.harness.team

import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import top.wkbin.taixu.core.model.bot.*
import top.wkbin.taixu.harness.bot.IndependentTeamStart

/** Proposals are immutable, session-bound, expiring and one-shot. Restart never re-authorizes one. */
internal class TeamService(
    private val port: TeamPort,
    private val journal: TeamAudit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Proposal(val id: String, val session: String, val reason: String,
        val operation: JsonObject, val before: String, val createdAt: Long,
        var pendingCall: String? = null, var consumed: Boolean = false)
    private val lock = Mutex()
    private val proposals = LinkedHashMap<String, Proposal>()

    suspend fun disableManagement() = lock.withLock { proposals.entries.removeAll { text(it.value.operation, "op") != "stop" } }

    suspend fun audit(session: String, action: String, detail: String) = journal.record(session, action, detail)
    private suspend fun owner(session: String) {
        require(session.isNotBlank()) { "必须绑定原生会话" }
        require(!port.memberSession(session)) { "团队成员会话无权修改团队或自身权限；请回到用户主会话" }
    }
    private suspend fun team(id: String) = requireNotNull(port.teams().firstOrNull { it.id == id }) { "团队不存在：$id" }
    private fun render(team: BotMemberGroup) = Json.encodeToString(team)
    private fun text(args: JsonObject, key: String) = (args[key] as? JsonPrimitive)?.content.orEmpty()
    private fun required(args: JsonObject, key: String) = text(args, key).also { require(it.isNotBlank()) { "缺少 $key" } }
    private fun revision(args: JsonObject) = requireNotNull(args["expectedRevision"]?.jsonPrimitive?.intOrNull) { "缺少 expectedRevision" }
    private fun members(args: JsonObject): List<String> = args["memberIds"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    private fun specs(args: JsonObject): List<JsonObject> = args["members"]?.jsonArray?.map { it.jsonObject }.orEmpty()
    private fun definition(args: JsonObject): BotTeamDefinition = Json.decodeFromJsonElement(requireNotNull(args["definition"]) { "缺少 definition" })
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun describe(p: Proposal) = buildString {
        appendLine("proposalId：${p.id}")
        appendLine("reason：${p.reason}")
        appendLine("snapshotSha256：${hash(p.operation.toString() + "\n" + p.before)}")
        appendLine("operation：${p.operation}")
        appendLine("before：${p.before}")
        appendLine("expiresAt：${p.createdAt + TTL}")
        append("consumed：${p.consumed}")
    }
    private fun proposal(session: String, id: String): Proposal {
        val p = requireNotNull(proposals[id]) { "提案不存在或进程已重启；必须重新提案" }
        require(p.session == session) { "提案不属于当前会话" }
        require(!p.consumed && clock() - p.createdAt in 0..TTL) { "提案已使用或已过期" }
        return p
    }

    suspend fun execute(session: String, args: JsonObject): String = lock.withLock {
        when (required(args, "action")) {
            "list" -> port.teams().joinToString("\n", transform = ::render).ifEmpty { "暂无持久团队" }
            "get" -> render(team(required(args, "teamId")))
            "status" -> port.status(required(args, "groupId"))
            "proposal" -> describe(proposal(session, required(args, "proposalId")))
            "propose" -> {
                owner(session)
                val reason = required(args, "reason").also { require(it.length <= 4000) { "reason 过长" } }
                val raw = requireNotNull(args["operation"]) { "缺少 operation" }
                require(raw.toString().length <= 100_000) { "团队提案过大" }
                val op = Json.parseToJsonElement(raw.toString()).jsonObject
                require(text(op, "op") in WRITES) { "不支持的团队写操作" }
                val canonical = buildJsonObject {
                    op.forEach { (key, value) -> put(key, value) }
                    if (text(op, "op") == "create") put("teamId", UUID.randomUUID().toString())
                    if (text(op, "op") == "start") put("groupId", UUID.randomUUID().toString())
                }
                validate(canonical, session)
                proposals.entries.removeAll { it.value.consumed || clock() - it.value.createdAt > TTL }
                require(proposals.size < 256) { "待审批提案过多" }
                val p = Proposal(UUID.randomUUID().toString(), session, reason, canonical, before(canonical, session), clock())
                // Durable audit before retaining/returning the proposal; an audit failure fails closed.
                audit(session, "proposed", describe(p))
                proposals[p.id] = p
                describe(p)
            }
            "apply" -> error("apply 必须经过原生 TeamExecutionGate，不接受工具参数自证批准")
            else -> error("未知 team action")
        }
    }

    /** Only the native gate can arm a proposal. The exact native call id is required at replay. */
    suspend fun prepare(session: String, id: String, callId: String): String = lock.withLock {
        owner(session)
        val p = proposal(session, id)
        if (before(p.operation, session) != p.before) {
            p.consumed = true
            p.pendingCall = null
            audit(session, "invalidated", p.id)
            error("审批前目标快照已变化，请重新提案")
        }
        validate(p.operation, session)
        val description = "团队变更必须由用户原生批准（完全访问也不豁免）。\n${describe(p)}"
        audit(session, "approval_requested", description)
        p.pendingCall = callId
        description
    }

    suspend fun applyNative(session: String, id: String, callId: String): String = lock.withLock {
        owner(session)
        val p = proposal(session, id)
        require(p.pendingCall == callId) { "没有匹配当前调用的原生审批请求" }
        if (before(p.operation, session) != p.before) {
            p.consumed = true
            p.pendingCall = null
            audit(session, "invalidated", p.id)
            error("目标状态已变化；原批准永久失效，请重新提案")
        }
        validate(p.operation, session)
        // Mark before any side effect. A cancellation, crash or partial failure is never retried.
        audit(session, "applying", describe(p))
        p.consumed = true
        try {
            val output = mutate(p.operation, session, p.id)
            audit(session, "applied", "${p.id}\n$output")
            output
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) {
            audit(session, "failed_or_partial", "${p.id}\n${e.message}")
            error("提案已消费；可能已有部分变更，禁止重放，请 list/get/status 核对。${e.message}")
        }
    }

    private suspend fun before(op: JsonObject, session: String): String = when (text(op, "op")) {
        "create" -> "new-team:${required(op, "teamId")};existingMembers:${memberState(members(op))}"
        "stop" -> Json.encodeToString(requireNotNull(port.group(required(op, "groupId"))) { "群聊不存在" })
        else -> team(required(op, "teamId")).let { render(it) + "\nmembers:" + memberState(it.memberIds + it.publishedSnapshot?.memberIds.orEmpty()) } +
            if (text(op, "op") == "start") "\nsource:" + port.startSourceSnapshot(session) else ""
    }

    private suspend fun memberState(ids: List<String>) = ids.distinct().sorted().map { port.memberSnapshot(it) }.joinToString("\n")

    private suspend fun validate(op: JsonObject, session: String) {
        val allowed = when (text(op, "op")) {
            "create" -> setOf("op", "teamId", "name", "memberIds", "members", "definition")
            "draft" -> setOf("op", "teamId", "expectedRevision", "name", "memberIds", "definition")
            "publish" -> setOf("op", "teamId", "expectedRevision")
            "start" -> setOf("op", "teamId", "groupId", "name", "task", "members")
            "stop" -> setOf("op", "groupId")
            else -> error("未知团队操作")
        }
        require(op.keys.all { it in allowed }) { "不支持的操作参数；不能修改权限或审批模式" }
        when (text(op, "op")) {
            "create" -> {
                require(text(op, "name").isNotBlank() && text(op, "name").length <= 100) { "团队名称须为1至100字" }
                val specs = specs(op)
                val keys = specs.map { required(it, "key") }
                require(keys.distinct().size == keys.size) { "成员 key 重复" }
                specs.forEach { require(it.keys.all { key -> key in setOf("key", "name", "role", "systemPrompt", "modelId", "modelVariant") }) { "不支持的成员参数；成员权限不可修改" } }
                specs.forEach { require(text(it, "name").isNotBlank() && text(it, "role").isNotBlank() && text(it, "systemPrompt").isNotBlank()) { "自定义成员缺少 name/role/systemPrompt" } }
                val ids = members(op) + keys
                require(ids.size in 1..30 && ids.distinct().size == ids.size) { "成员必须唯一且为1至30人" }
                members(op).forEach { require(port.botExists(it)) { "成员不存在：$it" } }
                definition(op).validate(ids)
                require(port.teams().none { it.id == text(op, "teamId") }) { "团队已存在" }
            }
            "draft" -> {
                val old = team(required(op, "teamId"))
                require(old.draftRevision == revision(op)) { "草稿修订已变化" }
                val next = draft(op, old)
                require(next.name.isNotBlank() && next.name.length <= 100) { "团队名称无效" }
                require(next.memberIds.size in 1..30 && next.memberIds.distinct().size == next.memberIds.size) { "成员无效" }
                next.memberIds.forEach { require(port.botExists(it)) { "成员不存在：$it" } }
                requireNotNull(next.definition).validate(next.memberIds)
            }
            "publish" -> {
                val t = team(required(op, "teamId"))
                require(t.draftRevision == revision(op)) { "草稿修订已变化" }
                requireNotNull(t.definition).validate(t.memberIds)
            }
            "start" -> {
                val t = team(required(op, "teamId"))
                require(t.publishedVersion > 0 && t.publishedSnapshot != null) { "请先发布团队" }
                require(required(op, "task").length <= 30_000) { "任务过长" }
                require(port.group(required(op, "groupId")) == null) { "启动群已经存在，禁止重复派发" }
                val published = requireNotNull(t.publishedSnapshot)
                port.validateStart(session, published, startMembers(op, published))
            }
            "stop" -> {
                val g = requireNotNull(port.group(required(op, "groupId"))) { "群聊不存在" }
                require(g.teamGroupId != null) { "仅允许停止独立团队群聊" }
            }
            else -> error("未知团队操作")
        }
    }

    private suspend fun startMembers(op: JsonObject, published: TeamPublishedVersion): List<IndependentTeamStart.Member> {
        if ("members" !in op) return published.memberIds.map { id ->
            val snapshot = Json.parseToJsonElement(port.memberSnapshot(id)).jsonObject
            val role = text(snapshot, "role").ifBlank { id }
            IndependentTeamStart.Member(id, "按你的原生角色「$role」独立承担总体任务中对应职责，向团队汇报结果或阻塞。总体任务：\n${required(op, "task")}")
        }
        return requireNotNull(op["members"]).jsonArray.map { value ->
            val member = value.jsonObject
            require(member.keys.all { it in setOf("botId", "instruction", "writePaths") }) { "不支持的成员启动参数" }
            val paths = member["writePaths"]?.jsonArray?.map { path ->
                val p = path.jsonPrimitive
                require(p.isString) { "writePaths 必须是字符串数组" }
                p.content
            }.orEmpty()
            IndependentTeamStart.Member(required(member, "botId"), required(member, "instruction"), paths)
        }.also { require(it.isNotEmpty()) { "启动成员不能为空" } }
    }

    private fun draft(op: JsonObject, old: BotMemberGroup) = old.copy(
        name = if ("name" in op) text(op, "name") else old.name,
        memberIds = if ("memberIds" in op) members(op) else old.memberIds,
        definition = if ("definition" in op) definition(op) else old.definition,
    )
    private suspend fun mutate(op: JsonObject, session: String, proposalId: String): String = when (text(op, "op")) {
        "create" -> create(op)
        "draft" -> {
            val next = draft(op, team(required(op, "teamId")))
            port.save(next)
            "已保存草稿（未发布）：${next.id}；${render(team(next.id))}"
        }
        "publish" -> "已发布 ${required(op, "teamId")}@${port.publish(required(op, "teamId"), revision(op))}；未自动升级任何现有群聊"
        "start" -> {
            val t = team(required(op, "teamId"))
            val published = requireNotNull(t.publishedSnapshot)
            val id = required(op, "groupId")
            val group = GroupChat(id, text(op, "name").ifBlank { published.name }, published.definition.adminBotId,
                createdAt = clock(), teamGroupId = t.id, teamVersion = published.version)
            port.createGroup(group, t.id)
            val actual = requireNotNull(port.group(id))
            require(actual.teamGroupId == t.id && actual.teamVersion == published.version) { "发布版本竞态；已建群 $id，但未启动，请核对" }
            val result = port.start(IndependentTeamStart.Request(id, session, "team-start:$proposalId", published, startMembers(op, published)))
            "已持久建群并原生受理独立任务：groupId=$id，team=${t.id}@${published.version}；receipts=${result.receipts}；仅代表已排队，不代表正在运行或任务已完成，请 status 查询"
        }
        "stop" -> {
            val id = required(op, "groupId")
            port.stop(id)
            "已请求停止团队群 $id 当前工作；保留团队、群聊与执行记录。可用 status 核对；后续显式新启动不受此停止请求影响。"
        }
        else -> error("未知团队操作")
    }

    private suspend fun create(op: JsonObject): String {
        val created = mutableListOf<String>()
        val keyMap = mutableMapOf<String, String>()
        var saving = false
        try {
            specs(op).forEach { spec ->
                val id = port.createMember(spec)
                created += id
                keyMap[required(spec, "key")] = id
            }
            fun resolve(key: String) = keyMap[key] ?: key
            val input = definition(op)
            val def = input.copy(adminBotId = resolve(input.adminBotId),
                supervisors = input.supervisors.map { resolve(it.key) to resolve(it.value) }.toMap(),
                governance = GroupGovernance(input.governance.rules.map { it.copy(
                    authorId = resolve(it.authorId), reviewerId = resolve(it.reviewerId), executorId = resolve(it.executorId)) }))
            val t = BotMemberGroup(required(op, "teamId"), required(op, "name"), clock(),
                members(op) + created, def)
            def.validate(t.memberIds)
            saving = true
            port.save(t)
            return "已创建持久自定义团队草稿（未发布、未启动）：${render(team(t.id))}"
        } catch (e: Exception) {
            // Never delete members after an uncertain DB commit; they may already be referenced.
            if (!saving && e !is CancellationException) created.forEach { id ->
                try { port.deleteMember(id) } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Original error and created IDs remain available for reconciliation. */ }
            }
            if (e is CancellationException) throw e
            error("创建未完成，teamId=${text(op, "teamId")}，已创建成员=$created；请核对残留。${e.message}")
        }
    }

    companion object {
        private const val TTL = 15 * 60 * 1000L
        private val WRITES = setOf("create", "draft", "publish", "start", "stop")
    }
}
