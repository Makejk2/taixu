package top.wkbin.taixu.harness.team

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import top.wkbin.taixu.core.model.RunMode
import top.wkbin.taixu.core.model.bot.*
import top.wkbin.taixu.harness.*
import top.wkbin.taixu.harness.bot.IndependentTeamStart

class TeamServiceTest {
    private class Port : TeamPort {
        val rows = linkedMapOf<String, BotMemberGroup>()
        val groups = linkedMapOf<String, GroupChat>()
        var writes = 0
        var starts = 0
        var stops = 0
        var member = false
        var failStart = false
        var cancelStart = false
        var sourceVersion = "owner-source-v1"
        var lastStart: IndependentTeamStart.Request? = null
        val bots = mutableSetOf("admin", "author", "reviewer", "executor")
        override suspend fun memberSession(sessionId: String) = member
        override suspend fun teams() = rows.values.toList()
        override suspend fun botExists(id: String) = id in bots
        override suspend fun memberSnapshot(id: String): String {
            require(id in bots)
            return """{"id":"$id","role":"role-$id"}"""
        }
        override suspend fun createMember(spec: JsonObject): String = "created-${bots.size}".also { bots += it; writes++ }
        override suspend fun deleteMember(id: String) { bots -= id }
        override suspend fun save(team: BotMemberGroup) {
            require(rows[team.id]?.draftRevision == null || rows[team.id]?.draftRevision == team.draftRevision)
            rows[team.id] = team.copy(draftRevision = team.draftRevision + 1); writes++
        }
        override suspend fun publish(id: String, revision: Int): Int {
            val old = rows.getValue(id); require(old.draftRevision == revision)
            val v = old.publishedVersion + 1
            rows[id] = old.copy(publishedVersion = v, publishedSnapshot = TeamPublishedVersion(id, v, old.name, old.memberIds, old.definition!!, 1))
            writes++; return v
        }
        override suspend fun group(id: String) = groups[id]
        override suspend fun createGroup(group: GroupChat, teamId: String) { groups[group.id] = group; writes++ }
        override suspend fun startSourceSnapshot(sessionId: String) = "$sessionId:$sourceVersion"
        private val starter = IndependentTeamStart(object : IndependentTeamStart.Port {
            override suspend fun validate(sourceSessionId: String, published: TeamPublishedVersion, members: List<IndependentTeamStart.Member>) {
                require(sourceSessionId == "owner")
                members.forEach { member ->
                    require(member.botId in bots)
                    require(member.writePaths.all { top.wkbin.taixu.harness.HarnessPathResolver().isWithinWorkspace(it, "/workspace") })
                }
            }
            override suspend fun admit(request: IndependentTeamStart.Request, member: IndependentTeamStart.Member): IndependentTeamStart.Receipt {
                starts++
                if (cancelStart) throw CancellationException("cancelled during native admission")
                check(!failStart) { "admission unavailable" }
                return IndependentTeamStart.Receipt(member.botId, "task-${member.botId}", "session-${member.botId}")
            }
            override suspend fun cancel(groupId: String) { stops++ }
        })
        override suspend fun validateStart(sessionId: String, published: TeamPublishedVersion, members: List<IndependentTeamStart.Member>) =
            starter.validate(sessionId, published, members)
        override suspend fun start(request: IndependentTeamStart.Request): IndependentTeamStart.Result {
            lastStart = request
            return starter.start(request)
        }
        override suspend fun stop(groupId: String) { stops++ }
        override suspend fun status(groupId: String) = groups.getValue(groupId).toString()
    }
    private fun args(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun create() = args("""{"action":"propose","reason":"建立真实持久团队","operation":{"op":"create","name":"研发团队","memberIds":["admin"],"definition":{"adminBotId":"admin"}}}""")
    private fun id(output: String) = Regex("proposalId：([^\\n]+)").find(output)!!.groupValues[1]
    private suspend fun expectFailure(message: String? = null, block: suspend () -> Unit) {
        val failure = try {
            block(); throw AssertionError("Expected rejection")
        } catch (e: IllegalArgumentException) { e } catch (e: IllegalStateException) { e }
        message?.let { assertTrue("Expected '$it', got '${failure.message}'", failure.message.orEmpty().contains(it)) }
    }
    private suspend fun commit(service: TeamService, session: String, proposal: String) {
        service.prepare(session, proposal, "native-call")
        service.applyNative(session, proposal, "native-call")
    }

    @Test fun disablingErasesNativeApprovedManagementProposals(): Unit = runBlocking {
        val port = Port(); val service = TeamService(port, TeamAudit { _, _, _ -> })
        val proposal = id(service.execute("owner", create()))
        service.prepare("owner", proposal, "native-call")
        service.disableManagement()
        expectFailure { service.applyNative("owner", proposal, "native-call") }
        assertEquals(0, port.writes)
    }

    @Test fun immutableReasonAndNativeSingleUse(): Unit = runBlocking {
        val port = Port(); val audit = mutableListOf<String>()
        val service = TeamService(port, TeamAudit { _, action, detail -> audit += "$action:$detail" })
        val output = service.execute("owner", create()); val proposal = id(output)
        assertEquals(0, port.writes)
        expectFailure { service.applyNative("owner", proposal, "native-call") }
        service.prepare("owner", proposal, "native-call")
        expectFailure { service.applyNative("other", proposal, "native-call") }
        expectFailure { service.applyNative("owner", proposal, "forged-call") }
        service.applyNative("owner", proposal, "native-call")
        assertEquals(1, port.writes)
        assertEquals(0, port.rows.values.single().publishedVersion)
        expectFailure { service.applyNative("owner", proposal, "native-call") }
        assertTrue(audit.any { it.contains("reason：建立真实持久团队") && it.contains("snapshotSha256") })
    }

    @Test fun customMembersGovernancePublicationAndIndependentGroup(): Unit = runBlocking {
        val port = Port(); val service = TeamService(port, TeamAudit { _, _, _ -> })
        val definition = """{"adminBotId":"admin","governance":{"rules":[{"id":"code","name":"代码审议","authorId":"author","reviewerId":"reviewer","executorId":"executor"}]}}"""
        val op = args("""{"action":"propose","reason":"代码交付制衡","operation":{"op":"create","name":"自定义团队","memberIds":["admin","author","reviewer","executor"],"members":[{"key":"designer","name":"设计","role":"设计","systemPrompt":"负责设计"}],"definition":$definition}}""")
        commit(service, "owner", id(service.execute("owner", op)))
        val team = port.rows.values.single()
        assertEquals(5, team.memberIds.size)
        assertEquals("code", team.definition!!.governance.rules.single().id)
        val publish = args("""{"action":"propose","reason":"发布审议规则","operation":{"op":"publish","teamId":"${team.id}","expectedRevision":1}}""")
        commit(service, "owner", id(service.execute("owner", publish)))
        assertEquals(1, port.rows.getValue(team.id).publishedVersion)
        // Whole-team start must not bypass the executor's native review requirement.
        val blocked = args("""{"action":"propose","reason":"不能绕过审议","operation":{"op":"start","teamId":"${team.id}","task":"执行交付"}}""")
        expectFailure { service.execute("owner", blocked) }
        assertEquals(0, port.starts)
        assertTrue(port.groups.isEmpty())
        // Start planning/review members independently; controlled execution follows its own reviewed proposal.
        val start = args("""{"action":"propose","reason":"启动独立规划与审核","operation":{"op":"start","teamId":"${team.id}","task":"执行交付","members":[{"botId":"admin","instruction":"独立协调"},{"botId":"author","instruction":"准备方案"},{"botId":"reviewer","instruction":"独立审阅"}]}}""")
        commit(service, "owner", id(service.execute("owner", start)))
        assertEquals(3, port.starts)
        assertEquals(listOf("admin", "author", "reviewer"), port.lastStart!!.members.map { it.botId })
        assertTrue(port.lastStart!!.members.all { it.writePaths.isEmpty() })
        val group = port.groups.values.single()
        assertEquals(team.id, group.teamGroupId)
        val stop = args("""{"action":"propose","reason":"用户停止","operation":{"op":"stop","groupId":"${group.id}"}}""")
        commit(service, "owner", id(service.execute("owner", stop)))
        assertEquals(1, port.stops)
    }

    @Test fun memberCannotModifyEvenWithNativeReplay(): Unit = runBlocking {
        val port = Port(); val service = TeamService(port, TeamAudit { _, _, _ -> })
        val proposal = id(service.execute("owner", create()))
        service.prepare("owner", proposal, "native-call")
        port.member = true
        expectFailure { service.execute("owner", create()) }
        expectFailure { service.applyNative("owner", proposal, "native-call") }
        assertEquals(0, port.writes)
    }

    @Test fun draftRevisionChangeInvalidatesApprovalAndOldPublicationStaysFixed(): Unit = runBlocking {
        val port = Port(); val service = TeamService(port, TeamAudit { _, _, _ -> })
        commit(service, "owner", id(service.execute("owner", create())))
        val team = port.rows.values.single()
        val publish = args("""{"action":"propose","reason":"发布","operation":{"op":"publish","teamId":"${team.id}","expectedRevision":1}}""")
        commit(service, "owner", id(service.execute("owner", publish)))
        val draft = args("""{"action":"propose","reason":"修改名称","operation":{"op":"draft","teamId":"${team.id}","expectedRevision":1,"name":"新草稿"}}""")
        val proposal = id(service.execute("owner", draft)); service.prepare("owner", proposal, "native-call")
        port.rows[team.id] = port.rows.getValue(team.id).copy(draftRevision = 2)
        expectFailure { service.applyNative("owner", proposal, "native-call") }
        assertEquals("研发团队", port.rows.getValue(team.id).publishedSnapshot!!.name)
    }

    @Test fun auditFailureAndRestartFailClosed(): Unit = runBlocking {
        val port = Port()
        val broken = TeamService(port, TeamAudit { _, _, _ -> error("disk full") })
        expectFailure { broken.execute("owner", create()) }
        assertEquals(0, port.writes)
        val service = TeamService(port, TeamAudit { _, _, _ -> })
        val proposal = id(service.execute("owner", create()))
        val restarted = TeamService(port, TeamAudit { _, _, _ -> })
        expectFailure { restarted.prepare("owner", proposal, "native-call") }
    }

    @Test fun partialStartNeverReplays(): Unit = runBlocking {
        val port = Port(); val service = TeamService(port, TeamAudit { _, _, _ -> })
        commit(service, "owner", id(service.execute("owner", create())))
        val t = port.rows.values.single()
        port.publish(t.id, 1)
        val start = args("""{"action":"propose","reason":"启动","operation":{"op":"start","teamId":"${t.id}","task":"执行"}}""")
        val proposal = id(service.execute("owner", start)); service.prepare("owner", proposal, "native-call")
        port.failStart = true
        expectFailure("禁止重放") { service.applyNative("owner", proposal, "native-call") }
        expectFailure("提案已使用") { service.applyNative("owner", proposal, "native-call") }
        assertEquals(1, port.starts); assertEquals(1, port.groups.size); assertEquals(1, port.stops)
    }

    @Test fun cancelledStartConsumesApprovalAndCannotReplay(): Unit = runBlocking {
        val port = Port(); val service = TeamService(port, TeamAudit { _, _, _ -> })
        commit(service, "owner", id(service.execute("owner", create())))
        val team = port.rows.values.single(); port.publish(team.id, 1)
        val proposal = id(service.execute("owner", args("""{"action":"propose","reason":"启动","operation":{"op":"start","teamId":"${team.id}","task":"执行"}}""")))
        service.prepare("owner", proposal, "native-call")
        port.cancelStart = true
        try {
            service.applyNative("owner", proposal, "native-call"); fail("Expected cancellation")
        } catch (e: CancellationException) {
            assertTrue(e.message.orEmpty().contains("禁止自动重放"))
        }
        port.cancelStart = false
        try {
            service.applyNative("owner", proposal, "native-call"); fail("Expected consumed proposal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("提案已使用"))
        }
        assertEquals(1, port.starts); assertEquals(1, port.stops); assertEquals(1, port.groups.size)
    }

    @Test fun deterministicDefaultStartUsesApprovedProposalAndTrustedSource(): Unit = runBlocking {
        val port = Port(); val service = TeamService(port, TeamAudit { _, _, _ -> })
        commit(service, "owner", id(service.execute("owner", create())))
        val t = port.rows.values.single()
        port.rows[t.id] = t.copy(memberIds = listOf("admin", "author"))
        port.publish(t.id, 1)
        val proposal = id(service.execute("owner", args("""{"action":"propose","reason":"启动","operation":{"op":"start","teamId":"${t.id}","task":"交付"}}""")))
        service.prepare("owner", proposal, "native-call")
        val output = service.applyNative("owner", proposal, "native-call")
        val request = port.lastStart!!
        assertEquals("owner", request.sourceSessionId)
        assertEquals("team-start:$proposal", request.operationKey)
        assertEquals(listOf("admin", "author"), request.members.map { it.botId })
        assertTrue(request.members.all { it.writePaths.isEmpty() })
        assertNotEquals(request.members[0].instruction, request.members[1].instruction)
        assertTrue(output.contains("task-admin") && output.contains("session-author"))
        assertEquals(2, port.starts)
        expectFailure { service.applyNative("owner", proposal, "native-call") }
        assertEquals(2, port.starts)
    }

    @Test fun explicitMemberInstructionsAndPathsAreImmutableAndSourceChangesInvalidateApproval(): Unit = runBlocking {
        val port = Port(); val service = TeamService(port, TeamAudit { _, _, _ -> })
        commit(service, "owner", id(service.execute("owner", create())))
        val t = port.rows.values.single(); port.publish(t.id, 1)
        val proposal = id(service.execute("owner", args("""{"action":"propose","reason":"启动","operation":{"op":"start","teamId":"${t.id}","task":"总体","members":[{"botId":"admin","instruction":"专项","writePaths":["src/one.kt"]}]}}""")))
        service.prepare("owner", proposal, "native-call")
        port.sourceVersion = "changed-approval-mode"
        expectFailure("目标状态已变化") { service.applyNative("owner", proposal, "native-call") }
        assertEquals(0, port.groups.size); assertEquals(0, port.starts)
        port.sourceVersion = "owner-source-v1"
        expectFailure("提案已使用") { service.applyNative("owner", proposal, "native-call") }
        val fresh = id(service.execute("owner", args("""{"action":"propose","reason":"重新核准来源","operation":{"op":"start","teamId":"${t.id}","task":"总体","members":[{"botId":"admin","instruction":"专项","writePaths":["src/one.kt"]}]}}""")))
        service.prepare("owner", fresh, "fresh-call")
        val output = service.applyNative("owner", fresh, "fresh-call")
        assertTrue(output.contains("task-admin"))
        assertEquals(listOf("src/one.kt"), port.lastStart!!.members.single().writePaths)
        assertEquals("专项", port.lastStart!!.members.single().instruction)
    }

    @Test fun validatesEveryMemberAndPathBeforeCreatingGroupAndRejectsModelOperationKey(): Unit = runBlocking {
        val port = Port(); val service = TeamService(port, TeamAudit { _, _, _ -> })
        commit(service, "owner", id(service.execute("owner", create())))
        val t = port.rows.values.single(); port.publish(t.id, 1)
        for (extra in listOf(
            "\"operationKey\":\"forged\"",
            "\"members\":[{\"botId\":\"admin\",\"instruction\":\"ok\"},{\"botId\":\"deleted\",\"instruction\":\"bad\"}]",
            "\"members\":[{\"botId\":\"admin\",\"instruction\":\"bad\",\"writePaths\":[\"../escape\"]}]"
        )) expectFailure {
            service.execute("owner", args("""{"action":"propose","reason":"启动","operation":{"op":"start","teamId":"${t.id}","task":"总体",$extra}}"""))
        }
        assertEquals(0, port.starts); assertEquals(0, port.groups.size)
    }

    @Test fun planAndFullAccessCannotBypassDedicatedGate(): Unit = runBlocking {
        val port = Port(); val service = TeamService(port, TeamAudit { _, _, _ -> })
        val backend = TeamToolBackend(service)
        val planCall = ToolCall("plan", 1L, HarnessTool.READ, create())
        val blocked = executeTeam(backend, { RunMode.PLAN }, planCall, "owner", true, 1L) { error("PLAN must not prompt") }
        assertFalse(blocked.success); assertEquals("PLAN_BLOCKED", blocked.errorCode)
        val proposal = id(service.execute("owner", create()))
        val apply = ToolCall("apply", 1L, HarnessTool.READ, args("""{"action":"apply","proposalId":"$proposal"}"""))
        val forged = executeTeam(backend, { RunMode.BUILD }, apply, "owner", true, 1L) { error("unreachable") }
        assertFalse(forged.success)
        var prompts = 0
        val awaiting = executeTeam(backend, { RunMode.BUILD }, apply, "owner", false, 1L) {
            prompts++; assertTrue(it.reason.contains("reason：")); ToolResult("r", 1L, apply.id, false, "waiting", awaitingApproval = true)
        }
        assertTrue(awaiting.awaitingApproval); assertEquals(1, prompts); assertEquals(0, port.writes)
        val applied = executeTeam(backend, { RunMode.BUILD }, apply, "owner", true, 1L) { error("already approved") }
        assertTrue(applied.success); assertEquals(1, port.writes)
    }
}
