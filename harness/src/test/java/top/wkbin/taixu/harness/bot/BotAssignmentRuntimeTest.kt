package top.wkbin.taixu.harness.bot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.database.*
import top.wkbin.taixu.core.database.repository.*
import top.wkbin.taixu.core.model.SessionRunState
import top.wkbin.taixu.core.model.bot.*
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.groupchat.GroupTurnOutcome
import top.wkbin.taixu.harness.queue.PromptQueue
import top.wkbin.taixu.harness.session.PromptSubmission
import top.wkbin.taixu.harness.session.SessionControl

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BotAssignmentRuntimeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val tasks = RoomBotAssignmentRepository(db.botAssignmentDao(), Json)
    private val groups = RoomGroupChatRepository(db.groupChatDao(), Json)
    private val bots = RoomPersonaBotRepository(db.personaBotDao())
    private val sessions = RoomHarnessSessionRepository(db.harnessSessionDao())
    private val control = FakeControl()
    private val tracker = BotAssignmentTracker(tasks)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = AppLogger(context) { it }
    @After fun close() = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }

    private suspend fun seed() {
        val chief = BotEntity("chief", "Chief", "lead", "persona", "seed", null, null, "dm", true, "#123456", 1, 1)
        db.personaBotDao().upsert(chief)
        db.personaBotDao().upsert(chief.copy(id = "member", name = "Member", sessionId = "member-dm", isMainBot = false, modelId = "member-model"))
        db.groupChatDao().upsertGroup(GroupChatEntity("group", "Team", "chief", "", 1))
        groups.addMember("group", "chief", 0)
        groups.addMember("group", "member", 1)
        sessions.upsert(HarnessSessionEntity("parent", "Group", 1, 1, null, workspace = "/workspace/project", executionTargetJson = "frozen-target"))
    }

    private suspend fun seedPublishedTeam(policy: GroupGovernance = GroupGovernance()) {
        seed()
        db.personaBotDao().upsert(db.personaBotDao().findById("member")!!.copy(
            id = "second", name = "Second", sessionId = "second-dm", modelId = "second-model"))
        val definition = BotTeamDefinition("chief", mapOf("second" to "member"), policy)
        db.botMemberGroupDao().installIndependentDefinition(
            BotMemberGroupEntity("published", "Published", 1, Json.encodeToString(definition)),
            listOf("chief", "member", "second").mapIndexed { index, id ->
                BotMemberGroupMemberEntity("published", id, index, 1)
            },
        )
        groups.attachTeam("group", "published")
    }

    private suspend fun rejected(message: String? = null, block: suspend () -> Unit) {
        try { block(); fail("Expected admission rejection") } catch (e: IllegalArgumentException) {
            message?.let { assertTrue("Expected '$it', got '${e.message}'", e.message.orEmpty().contains(it)) }
        }
    }

    @Test fun `approved native start gives admin and two members distinct idempotent roots with inherited authority`(): Unit = runBlocking {
        seedPublishedTeam()
        val source = sessions.findById("parent")!!.copy(approvalMode = "assisted", runMode = "plan", projectType = "android", modelId = "source-model", modelVariant = "source-variant")
        sessions.upsert(source)
        val entered = MutableStateFlow<Set<String>>(emptySet())
        val engine = runtime { task, _, admitted ->
            admitted(null)
            entered.update { it + task.botId }
            awaitCancellation()
        }
        val roots = listOf("chief", "member", "second").map { bot ->
            engine.createApprovedTeamRoot("parent", bot, "group", "work $bot", listOf("src/$bot"), "approved-operation")
        }
        withTimeout(10_000) { entered.first { it.size == 3 } }
        assertEquals(setOf("chief", "member", "second"), entered.value)
        assertEquals(3, roots.map { it.id }.distinct().size)
        assertEquals(3, roots.map { it.sessionId }.distinct().size)
        assertEquals(1, roots.map { it.rootWorkId }.distinct().size)
        roots.forEach { task ->
            assertNull(task.parentAssignmentId)
            assertNull(task.batchId)
            val session = sessions.findById(task.sessionId!!)!!
            assertEquals(source.workspace, session.workspace)
            assertEquals(source.projectType, session.projectType)
            assertEquals(source.approvalMode, session.approvalMode)
            assertEquals(source.runMode, session.runMode)
            assertEquals(source.executionTargetJson, session.executionTargetJson)
            assertEquals(bots.getBot(task.botId)!!.modelId, session.modelId)
            assertEquals(bots.getBot(task.botId)!!.modelVariant, session.modelVariant)
            assertEquals(listOf("src/${task.botId}"), task.writePaths)
            val again = engine.createApprovedTeamRoot("parent", task.botId, "group", "retry", listOf("src/${task.botId}"), "approved-operation")
            assertEquals(task.id, again.id)
            assertEquals(task.sessionId, again.sessionId)
            assertEquals(1, engine.history(task.id).size)
        }
        rejected("只能向本群直属下级") { engine.create("chief", "group", "chief", "parent", "ordinary self") }
        rejected("只能向本群直属下级") { engine.create("second", "group", "chief", "parent", "ordinary non-direct") }
        engine.cancelGroup("group")
        roots.forEach { task ->
            awaitStatus(task.id, BotAssignmentStatus.CANCELLED)
            val again = engine.createApprovedTeamRoot("parent", task.botId, "group", "must not replay", task.writePaths, "approved-operation")
            assertEquals(task.id, again.id)
            assertEquals(BotAssignmentStatus.CANCELLED, again.status)
            assertEquals(1, engine.history(task.id).size)
            assertEquals("cancelled", engine.history(task.id).single().status)
        }
    }

    @Test fun `approved roots reject nonexistent bot and assignment sources and workspace escape`(): Unit = runBlocking {
        seedPublishedTeam()
        val engine = runtime { _, _, _ -> awaitCancellation() }
        rejected { engine.createApprovedTeamRoot("missing", "chief", "group", "fake", emptyList(), "op") }
        sessions.upsert(HarnessSessionEntity("member-dm", "Member", 1, 1, null))
        rejected { engine.createApprovedTeamRoot("member-dm", "chief", "group", "fake", emptyList(), "op") }
        rejected { engine.createApprovedTeamRoot("parent", "chief", "group", "escape", listOf("../outside"), "op") }
        val root = engine.createApprovedTeamRoot("parent", "member", "group", "valid", emptyList(), "op")
        rejected { engine.createApprovedTeamRoot(root.sessionId!!, "chief", "group", "nested", emptyList(), "nested") }
        assertTrue(tasks.assignmentsForBot("chief").isEmpty())
    }

    @Test fun `DAO transaction independently enforces native root trust and inheritance`(): Unit = runBlocking {
        seedPublishedTeam()
        val engine = runtime { _, _, _ -> awaitCancellation() }
        val root = engine.createApprovedTeamRoot("parent", "chief", "group", "valid", emptyList(), "approved")
        val entity = db.botAssignmentDao().findById(root.id)!!
        val session = sessions.findById(root.sessionId!!)!!
        suspend fun direct(source: String?, trusted: Boolean = true, candidate: BotAssignmentEntity = entity,
            childSession: HarnessSessionEntity = session) {
            db.botAssignmentDao().createIndependent(candidate, childSession,
                BotAssignmentRequestEntity("direct", candidate.id, "fake", 1),
                dispatchOperationId = "approved", trustedNativeTeamStart = trusted, sourceSessionId = source)
        }
        // An internal boolean is not sufficient: the DAO revalidates real source identity.
        rejected("缺少可信来源会话") { direct(null) }
        rejected("来源会话不存在") { direct("missing") }
        sessions.upsert(HarnessSessionEntity("member-dm", "Member", 1, 1, null))
        rejected("来源不是主用户会话") { direct("member-dm") }
        rejected("来源不是主用户会话") { direct(root.sessionId) }
        rejected("只能向本群直属下级") { direct("parent", trusted = false) }
        rejected { direct("parent", candidate = entity.copy(parentAssignmentId = root.id)) }
        rejected { direct("parent", candidate = entity.copy(reportToBotId = "member")) }
        for (changed in listOf(
            session.copy(workspace = "/other"), session.copy(projectType = "flutter"),
            session.copy(executionTargetJson = "other-target"), session.copy(approvalMode = "different"),
            session.copy(runMode = "plan"), session.copy(modelId = "different"), session.copy(modelVariant = "different"),
        )) rejected("继承不匹配") { direct("parent", childSession = changed) }
        rejected("写入路径必须在来源工作区内") { direct("parent", candidate = entity.copy(writePathsJson = "[\"../escape\"]")) }
        rejected("根任务幂等标识与来源不匹配") { direct("parent", candidate = entity.copy(rootWorkId = "fake")) }
        // Positive control: the same real Room transaction accepts a valid deduplicated admission.
        direct("parent")
        assertEquals(1, engine.history(root.id).size)
    }

    @Test fun `native approval never bypasses governed executors or unlinked rooms`(): Unit = runBlocking {
        seedPublishedTeam(GroupGovernance(listOf(GroupReviewRule("rule", "Review", "chief", "member", "second"))))
        val engine = runtime { _, _, _ -> awaitCancellation() }
        rejected { engine.createApprovedTeamRoot("parent", "second", "group", "bypass", emptyList(), "op") }
        val root = engine.createApprovedTeamRoot("parent", "chief", "group", "author", emptyList(), "op")
        val entity = db.botAssignmentDao().findById(root.id)!!
        rejected {
            db.botAssignmentDao().createIndependent(entity.copy(botId = "second", stageId = "delegate:root:second", workItemId = "second"),
                sessions.findById(root.sessionId!!)!!, BotAssignmentRequestEntity("blocked", root.id, "bypass", 1),
                dispatchOperationId = "op", trustedNativeTeamStart = true, sourceSessionId = "parent")
        }
        db.groupChatDao().upsertGroup(GroupChatEntity("unlinked", "Old", "chief", "", 1))
        groups.addMember("unlinked", "chief", 0)
        rejected { engine.createApprovedTeamRoot("parent", "chief", "unlinked", "old", emptyList(), "op") }
    }

    private fun runtime(execute: BotAssignmentExecutor) = BotAssignmentRuntime(tasks, groups, bots, sessions, control, tracker, execute, log, scope)
    private suspend fun awaitStatus(id: String, expected: BotAssignmentStatus) = withTimeout(10_000) {
        while (tasks.get(id)?.status != expected) delay(10)
    }

    @Test fun `active followup queues without interrupting tools then reuses the same session and frozen target`(): Unit = runBlocking {
        seed()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val seen = mutableListOf<Pair<String, String>>()
        val engine = runtime { task, request, admitted ->
            val sid = requireNotNull(task.sessionId)
            val accepted = control.submit(sid, request.instruction) as PromptSubmission.Accepted
            admitted(accepted.taskId)
            synchronized(seen) { seen += sid to request.instruction }
            if (request.instruction == "first") { entered.complete(Unit); release.await() }
            control.setState(sid, SessionRunState.COMPLETED)
            GroupTurnOutcome("done ${request.instruction}", false)
        }
        val task = engine.create("member", "group", "chief", "parent", "first")
        withTimeout(10_000) { entered.await() }
        val next = engine.continueTask(task.id, "group", "second", "next")
        engine.continueTask(task.id, "group", "second", "next")
        assertEquals(1, synchronized(seen) { seen.size })
        assertEquals(BotAssignmentStatus.RUNNING, tasks.get(task.id)!!.status)
        sessions.upsert(sessions.findById("parent")!!.copy(workspace = "/changed", executionTargetJson = "changed-target"))
        release.complete(Unit)
        withTimeout(10_000) { engine.awaitRequest(next) }
        awaitStatus(task.id, BotAssignmentStatus.COMPLETED)
        assertEquals(listOf(task.sessionId to "first", task.sessionId to "second"), synchronized(seen) { seen.toList() })
        assertEquals("/workspace/project", sessions.findById(task.sessionId!!)!!.workspace)
        assertEquals("frozen-target", sessions.findById(task.sessionId!!)!!.executionTargetJson)
        assertEquals("full_access", sessions.findById(task.sessionId!!)!!.approvalMode)
        assertEquals("member-model", sessions.findById(task.sessionId!!)!!.modelId)
        assertEquals("member", bots.getBotBySessionId(task.sessionId!!)!!.id)
        assertEquals(2, tasks.get(task.id)!!.revision)
    }

    @Test fun `blocked work can receive missing input without losing the original report and artifacts`(): Unit = runBlocking {
        seed()
        val engine = runtime { task, request, admitted ->
            admitted("native")
            if (request.instruction == "first") tasks.reportBlock(task.sessionId!!, "input missing", listOf("/workspace/partial.txt"))
            GroupTurnOutcome("result ${request.instruction}", false)
        }
        val task = engine.create("member", "group", "chief", "parent", "first")
        awaitStatus(task.id, BotAssignmentStatus.BLOCKED)
        val next = engine.continueTask(task.id, "group", "here is input", "fix")
        withTimeout(10_000) { engine.awaitRequest(next) }
        awaitStatus(task.id, BotAssignmentStatus.COMPLETED)
        assertEquals("input missing", engine.history(task.id).single { it.id.startsWith("initial:") }.blockedReason)
        assertEquals(2, engine.history(task.id).size)
    }

    @Test fun `cancellation fences queued requests and late settlement cannot restart or overwrite the task`(): Unit = runBlocking {
        seed()
        val entered = CompletableDeferred<Unit>()
        val engine = runtime { task, _, admitted ->
            control.setState(task.sessionId!!, SessionRunState.RUNNING)
            admitted("native")
            entered.complete(Unit)
            awaitCancellation()
        }
        val task = engine.create("member", "group", "chief", "parent", "first")
        withTimeout(10_000) { entered.await() }
        engine.continueTask(task.id, "group", "second", "next")
        tracker.cancel(task.id)
        awaitStatus(task.id, BotAssignmentStatus.CANCELLED)
        assertEquals("cancelled", tasks.getRequest("next")!!.status)
        tasks.finishRequest("initial:${task.id}", true, false, "late success", null)
        assertEquals(BotAssignmentStatus.CANCELLED, tasks.get(task.id)!!.status)
    }

    @Test fun `uncertain native submission is never silently rerun and busy or deleted sessions reject continuation`(): Unit = runBlocking {
        seed()
        val engine = runtime { task, request, admitted ->
            control.submit(task.sessionId!!, request.instruction)
            admitted("committed-native")
            error("native side effect may already have happened")
        }
        val task = engine.create("member", "group", "chief", "parent", "first")
        awaitStatus(task.id, BotAssignmentStatus.INTERRUPTED)
        assertEquals(1, control.count)
        assertTrue(runCatching { engine.continueTask(task.id, "group", "check first") }.isFailure)
        control.setState(task.sessionId!!, SessionRunState.IDLE)
        sessions.deleteSession(task.sessionId!!)
        assertTrue(runCatching { engine.continueTask(task.id, "group", "continue") }.isFailure)
        assertEquals(1, control.count)
    }

    @Test fun `out of workspace write hints reject before creating or submitting a task`(): Unit = runBlocking {
        seed()
        val engine = runtime { _, _, _ -> error("must not dispatch") }
        listOf("/root/game", "../escape", "/workspace/other", "docs/../../escape").forEach { path ->
            assertTrue(runCatching { engine.create("member", "group", "chief", "parent", "work", listOf(path)) }.isFailure)
        }
        assertTrue(tasks.assignmentsForBot("member").isEmpty())
        assertEquals(0, control.count)
    }

    @Test fun `member model default never borrows the source variant even on the same profile`(): Unit = runBlocking {
        seed()
        sessions.upsert(sessions.findById("parent")!!.copy(modelId = "member-model", modelVariant = "chief-variant"))
        val engine = runtime { _, _, _ -> GroupTurnOutcome("done", false) }
        val task = engine.create("member", "group", "chief", "parent", "work", listOf("docs"))
        assertNull(sessions.findById(task.sessionId!!)!!.modelVariant)
        val member = bots.getBot("member")!!
        bots.saveBot(member.copy(modelVariant = "own-model"))
        val second = engine.create("member", "group", "chief", "parent", "other", listOf("/workspace/project/code"))
        assertEquals("own-model", sessions.findById(second.sessionId!!)!!.modelVariant)
    }

    @Test fun `unbound member follows global default instead of inheriting the chief profile or variant`(): Unit = runBlocking {
        seed()
        bots.saveBot(bots.getBot("member")!!.copy(modelId = null, modelVariant = null))
        sessions.upsert(sessions.findById("parent")!!.copy(modelId = "chief-model", modelVariant = "chief-variant"))
        val seen = CompletableDeferred<HarnessSessionEntity>()
        val engine = runtime { task, _, admitted ->
            seen.complete(sessions.findById(task.sessionId!!)!!)
            admitted("native")
            control.setState(task.sessionId!!, SessionRunState.COMPLETED)
            GroupTurnOutcome("done", false)
        }
        engine.create("member", "group", "chief", "parent", "work")
        val binding = withTimeout(10_000) { seen.await() }
        assertNull(binding.modelId)
        assertNull(binding.modelVariant)
        assertEquals("/workspace/project", binding.workspace)
        assertEquals("frozen-target", binding.executionTargetJson)
        assertEquals("chief-model", sessions.findById("parent")!!.modelId)
    }

    @Test fun `continued legacy task repairs its stale model binding from the member selection`(): Unit = runBlocking {
        seed()
        val engine = runtime { task, _, admitted ->
            assertEquals("member-model", sessions.findById(task.sessionId!!)!!.modelId)
            assertNull(sessions.findById(task.sessionId!!)!!.modelVariant)
            admitted("native")
            control.setState(task.sessionId!!, SessionRunState.COMPLETED)
            GroupTurnOutcome("done", false)
        }
        val task = engine.create("member", "group", "chief", "parent", "first")
        awaitStatus(task.id, BotAssignmentStatus.COMPLETED)
        sessions.upsert(sessions.findById(task.sessionId!!)!!.copy(modelId = "chief-model", modelVariant = "chief-variant"))
        val requestId = engine.continueTask(task.id, "group", "continue", "continued")
        withTimeout(10_000) { engine.awaitRequest(requestId) }
        awaitStatus(task.id, BotAssignmentStatus.COMPLETED)
        assertEquals("member-model", sessions.findById(task.sessionId!!)!!.modelId)
        assertEquals("frozen-target", sessions.findById(task.sessionId!!)!!.executionTargetJson)
    }

    @Test fun `empty source workspace retains the frozen identity while file hints resolve under workspace`(): Unit = runBlocking {
        seed()
        sessions.upsert(HarnessSessionEntity("empty-parent", "Empty", 1, 1, null,
            workspace = "", executionTargetJson = "frozen-empty-workspace"))
        val engine = runtime { _, _, _ -> GroupTurnOutcome("done", false) }
        val task = engine.create("member", "group", "chief", "empty-parent", "work", listOf("/workspace/game"))
        assertEquals("", sessions.findById(task.sessionId!!)!!.workspace)
        assertEquals("frozen-empty-workspace", sessions.findById(task.sessionId!!)!!.executionTargetJson)
    }

    @Test fun `work id groups tasks into one batch while plain dispatch stays single`(): Unit = runBlocking {
        seed()
        val engine = runtime { _, _, admitted ->
            admitted(null)
            GroupTurnOutcome("done", false)
        }
        val first = engine.create("member", "group", "chief", "parent", "first", workId = "work")
        val second = engine.create("member", "group", "chief", "parent", "second", workId = "work")
        assertEquals("work", first.batchId)
        assertEquals("work", second.batchId)
        assertEquals(2, engine.batchSize("work"))
        assertEquals(2, tasks.getBatch("work")!!.revision)
        val loose = engine.create("member", "group", "chief", "parent", "third")
        assertNull(loose.batchId)
    }

    @Test fun `synchronous nested dispatch returns once through the original parent execution`(): Unit = runBlocking {
        seed()
        val member = db.personaBotDao().findById("member")!!
        db.personaBotDao().upsert(member.copy(id = "dev", name = "Dev", sessionId = "dev-dm"))
        groups.addMember("group", "dev", 2)
        groups.saveTeam("group", mapOf("dev" to "member"))
        val parentRuns = java.util.concurrent.atomic.AtomicInteger()
        lateinit var engine: BotAssignmentRuntime
        engine = runtime { task, request, admitted ->
            val sid = requireNotNull(task.sessionId)
            val accepted = control.submit(sid, request.instruction) as PromptSubmission.Accepted
            admitted(accepted.taskId)
            if (task.botId == "member") {
                parentRuns.incrementAndGet()
                val child = engine.create("dev", "group", "member", sid, "child implementation")
                assertNull(child.batchId)
                val result = withTimeout(10_000) { engine.awaitSynchronousTask(child.id, sid) }
                assertEquals(BotAssignmentStatus.COMPLETED, result.status)
                assertEquals("child verified", result.summary)
                assertTrue(groups.pendingDeliveries().first().isEmpty())
                assertEquals(BotAssignmentStatus.RUNNING, tasks.get(task.id)!!.status)
            }
            control.setState(sid, SessionRunState.COMPLETED)
            GroupTurnOutcome(if (task.botId == "dev") "child verified" else "accepted child", false)
        }
        val parent = engine.create("member", "group", "chief", "parent", "deliver", workId = "outer")
        val result = withTimeout(10_000) { engine.awaitTask(parent.id) }
        assertEquals(BotAssignmentStatus.COMPLETED, result.status)
        assertEquals("accepted child", result.summary)
        assertEquals(1, parentRuns.get())
        assertEquals("chief", groups.pendingDeliveries().first().single().botId)
    }

    @Test fun `eight distinct members enter execution before any one is released`(): Unit = runBlocking {
        seed()
        val worker = db.personaBotDao().findById("member")!!
        repeat(8) { i ->
            db.personaBotDao().upsert(worker.copy(id = "worker-$i", name = "Worker $i", sessionId = "dm-worker-$i"))
            groups.addMember("group", "worker-$i", i + 2)
        }
        val entered = MutableStateFlow<Set<String>>(emptySet())
        val release = CompletableDeferred<Unit>()
        val engine = runtime { task, request, admitted ->
            val sid = task.sessionId!!
            val accepted = control.submit(sid, request.instruction) as PromptSubmission.Accepted
            admitted(accepted.taskId)
            entered.update { it + task.botId }
            release.await()
            control.setState(sid, SessionRunState.COMPLETED)
            GroupTurnOutcome("done ${task.botId}", false)
        }
        val children = (0 until 8).map { i -> async { engine.create("worker-$i", "group", "chief", "parent",
            "implement module-$i", writePaths = listOf("module-$i"), workId = "parallel") } }.awaitAll()
        withTimeout(10_000) { entered.first { it.size == 8 } }
        assertEquals(8, children.map { it.sessionId }.toSet().size)
        children.forEach { assertEquals(BotAssignmentStatus.RUNNING, tasks.get(it.id)!!.status) }
        assertTrue(groups.pendingDeliveries().first().isEmpty())
        release.complete(Unit)
        children.forEach { assertEquals(BotAssignmentStatus.COMPLETED, withTimeout(10_000) { engine.awaitTask(it.id) }.status) }
        assertEquals(1, groups.pendingDeliveries().first().size)
    }

    @Test fun `reopened prerequisite prevents executor entry until the same task is explicitly continued`(): Unit = runBlocking {
        seed()
        val pre = BotAssignment("pre", "group", "member", "Member", "pre", "pre", BotAssignmentStatus.COMPLETED,
            createdAt = 1, startedAt = 1)
        tasks.save(pre)
        val row = BotAssignment("dependent", "group", "member", "Member", "dependent", "dependent", BotAssignmentStatus.QUEUED,
            createdAt = 1, startedAt = 1, sessionId = "dependent-session", independent = true, reportToBotId = "chief", dependsOn = listOf("pre"))
        tasks.createIndependent(row, HarnessSessionEntity("dependent-session", "dependent", 1, 1, null),
            BotAssignmentRequest("initial:dependent", "dependent", "work", 1))
        tasks.save(pre.copy(status = BotAssignmentStatus.RUNNING))
        val entries = java.util.concurrent.atomic.AtomicInteger()
        val engine = runtime { task, _, admitted ->
            entries.incrementAndGet()
            admitted("native")
            control.setState(task.sessionId!!, SessionRunState.COMPLETED)
            GroupTurnOutcome("verified", false)
        }
        engine.start()
        awaitStatus("dependent", BotAssignmentStatus.BLOCKED)
        assertEquals(0, entries.get())
        tasks.save(pre)
        engine.continueTask("dependent", "group", "verify and continue", "resume")
        val result = withTimeout(10_000) { engine.awaitTask("dependent") }
        assertEquals(BotAssignmentStatus.COMPLETED, result.status)
        assertEquals("dependent-session", result.sessionId)
        assertEquals(1, entries.get())
    }

    @Test fun `nested team waits for child then verifies in the original parent session`(): Unit = runBlocking {
        seed()
        val member = db.personaBotDao().findById("member")!!
        db.personaBotDao().upsert(member.copy(id = "dev", name = "Dev", sessionId = "dev-dm"))
        groups.addMember("group", "dev", 2)
        groups.saveTeam("group", mapOf("dev" to "member"))
        val release = CompletableDeferred<Unit>()
        val parentSessions = java.util.Collections.synchronizedList(mutableListOf<String>())
        lateinit var engine: BotAssignmentRuntime
        engine = runtime { task, request, admitted ->
            val sid = requireNotNull(task.sessionId)
            val acceptance = control.submit(sid, request.instruction) as PromptSubmission.Accepted
            admitted(acceptance.taskId)
            if (task.botId == "member") {
                parentSessions += sid
                if (request.id.startsWith("initial:")) {
                    engine.create("dev", "group", "member", sid, "child implementation", workId = "inner")
                } else {
                    assertTrue(request.id.startsWith("child-batch:"))
                    assertTrue(request.instruction.contains("child artifact"))
                }
            } else release.await()
            control.setState(sid, SessionRunState.COMPLETED)
            GroupTurnOutcome(if (task.botId == "dev") "child artifact" else "parent accepted", false)
        }
        val parent = engine.create("member", "group", "chief", "parent", "parent goal", workId = "outer")
        awaitStatus(parent.id, BotAssignmentStatus.WAITING_CHILDREN)
        assertTrue(groups.pendingDeliveries().first().isEmpty())
        release.complete(Unit)
        val settled = withTimeout(10_000) { engine.awaitTask(parent.id) }
        assertEquals(BotAssignmentStatus.COMPLETED, settled.status)
        assertEquals(listOf(parent.sessionId, parent.sessionId), parentSessions.toList())
        assertEquals("chief", groups.pendingDeliveries().first().single().botId)
        assertEquals(parent.id, tasks.batchAssignments("inner").single().parentAssignmentId)
    }

    @Test fun `members exchange durable messages while sender runs and parent conversation stops`(): Unit = runBlocking {
        seed()
        val member = db.personaBotDao().findById("member")!!
        db.personaBotDao().upsert(member.copy(id = "peer", name = "Peer", sessionId = "peer-dm"))
        groups.addMember("group", "peer", 2)
        val senderEntered = CompletableDeferred<Unit>()
        val senderRelease = CompletableDeferred<Unit>()
        val receiverEntered = CompletableDeferred<Unit>()
        val received = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        lateinit var a: BotAssignment
        lateinit var b: BotAssignment
        lateinit var engine: BotAssignmentRuntime
        engine = runtime { task, request, admitted ->
            val sid = requireNotNull(task.sessionId)
            val accepted = control.submit(sid, request.instruction) as PromptSubmission.Accepted
            admitted(accepted.taskId)
            when {
                request.instruction == "hold-a" -> { senderEntered.complete(Unit); senderRelease.await() }
                request.instruction == "ready-b" -> receiverEntered.complete(Unit)
                task.botId == "peer" && request.id.startsWith("peer:") -> {
                    assertFalse("Source must still be working", senderRelease.isCompleted)
                    assertEquals(BotAssignmentStatus.RUNNING, tasks.get(a.id)!!.status)
                    received.complete(Unit)
                    engine.sendMemberMessage(sid, a.id, "B received A and replies independently", "reply-1")
                }
                task.botId == "member" && request.id.startsWith("peer:") -> returned.complete(Unit)
            }
            control.setState(sid, SessionRunState.COMPLETED)
            GroupTurnOutcome("done", false)
        }
        a = engine.create("member", "group", "chief", "parent", "hold-a")
        withTimeout(10000) { senderEntered.await() }
        b = engine.create("peer", "group", "chief", "parent", "ready-b")
        withTimeout(10000) { receiverEntered.await(); engine.awaitTask(b.id) }
        assertTrue(engine.memberDirectory(a.sessionId!!).contains(b.id))
        assertFalse(engine.memberDirectory(a.sessionId!!).contains("ready-b"))
        control.cancel("parent")
        val key = engine.sendMemberMessage(a.sessionId!!, b.id, "A shares findings while working", "findings-1")
        assertEquals(key, engine.sendMemberMessage(a.sessionId!!, b.id, "A shares findings while working", "findings-1"))
        withTimeout(10000) { received.await(); engine.awaitRequest(key) }
        assertFalse("Parent stop must not cancel independent source", senderRelease.isCompleted)
        assertFalse(returned.isCompleted)
        senderRelease.complete(Unit)
        withTimeout(10000) { returned.await() }
        assertEquals(1, engine.history(b.id).count { it.id == key })
    }

    @Test fun `new independent members inherit approval and plan intent without escalation`(): Unit = runBlocking {
        seed()
        sessions.upsert(sessions.findById("parent")!!.copy(approvalMode = "request", runMode = "plan"))
        val release = CompletableDeferred<Unit>()
        val engine = runtime { task, request, admitted ->
            val sid = task.sessionId!!
            val accepted = control.submit(sid, request.instruction) as PromptSubmission.Accepted
            admitted(accepted.taskId); release.await(); control.setState(sid, SessionRunState.COMPLETED)
            GroupTurnOutcome("done", false)
        }
        val task = engine.create("member", "group", "chief", "parent", "read only")
        val child = sessions.findById(task.sessionId!!)!!
        assertEquals("request", child.approvalMode)
        assertEquals("plan", child.runMode)
        release.complete(Unit)
        Unit // JUnit4 requires a void method; CompletableDeferred.complete returns Boolean.
    }

    private class FakeControl : SessionControl {
        override val currentSessionId = MutableStateFlow("parent")
        override val sessionRunStates = MutableStateFlow<Map<String, SessionRunState>>(emptyMap())
        override val sessionStatuses = MutableStateFlow<Map<String, String>>(emptyMap())
        private val messages = MutableStateFlow<List<HarnessMessage>>(emptyList())
        @Volatile var count = 0
        fun setState(id: String, state: SessionRunState) { synchronized(this) { sessionRunStates.value = sessionRunStates.value + (id to state) } }
        override fun messagesForSession(sessionId: String) = messages
        override suspend fun prepareRemoteSession(sessionId: String) = messages.value
        override suspend fun persistedMessages(sessionId: String) = messages.value
        override suspend fun deleteSession(id: String) = Unit
        override suspend fun submit(sessionId: String, text: String, imageUrls: List<String>, queue: PromptQueue): PromptSubmission {
            val nativeId = synchronized(this) { ++count }
            setState(sessionId, SessionRunState.RUNNING)
            return PromptSubmission.Accepted(PromptSubmission.Disposition.STARTED, "native-$nativeId")
        }
        override fun cancel(targetSessionId: String?) { targetSessionId?.let { setState(it, SessionRunState.IDLE) } }
        override fun send(text: String, targetSessionId: String?, imageUrls: List<String>) = Unit
        override fun steer(text: String, targetSessionId: String?, imageUrls: List<String>) = Unit
        override fun followUp(text: String, targetSessionId: String?, imageUrls: List<String>) = Unit
        override fun appendOnly(text: String, targetSessionId: String?, imageUrls: List<String>) = Unit
        override fun resolveApproval(requestId: String, approved: Boolean, rememberForSession: Boolean) = Unit
        override fun resolveQuestion(requestId: String, answersJson: String) = Unit
    }
}
