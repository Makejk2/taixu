package top.wkbin.taixu.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.wkbin.taixu.core.model.bot.*
import top.wkbin.taixu.core.database.repository.RoomGroupChatRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TeamCommunicationPersistenceTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = db.botAssignmentDao()
    private val tasks = RoomBotAssignmentRepository(dao, Json)
    private val groups = RoomGroupChatRepository(db.groupChatDao(), Json)
    @After fun close() = db.close()

    private suspend fun seed(policy: TeamCommunicationPolicy = TeamCommunicationPolicy(groupLimit=1,memberLimit=1,rateLimit=100,queueLimit=100)) {
        val members = listOf("chief", "a", "b")
        members.forEach { db.personaBotDao().upsert(BotEntity(it,it,"role","",it,null,null,"dm-$it",false,"#123456",1,1)) }
        groups.saveIndependentTeam(BotMemberGroup("team", "Team", 1, members, BotTeamDefinition("chief")))
        groups.createReferencedGroup(GroupChat("g", "Group", "chief", createdAt=1), "team")
        db.harnessSessionDao().upsert(HarnessSessionEntity("owner", "Owner", 1, 1, null))
        listOf("a", "b").forEach { member ->
            val task = BotAssignment(member, "g", member, member, "task", "work", BotAssignmentStatus.RUNNING,
                createdAt=1, startedAt=1, independent=true, sessionId="session-$member", reportToBotId="chief", teamBinding="team@1")
            dao.insertIndependentSession(HarnessSessionEntity("session-$member", "Task", 1, 1, null))
            dao.insertIndependentAssignment(task.toEntity(Json))
        }
        dao.approveCommunicationPolicy("g", 0, policy, "owner", "approval-1", 10)
    }

    @Test(timeout = 20_000) fun concurrentBudgetIdempotencyAndOriginalTaskResume(): Unit = runBlocking {
        seed()
        val receipts = coroutineScope { (1..12).map { n -> async { tasks.sendCommunication("session-a", "b", "message $n", "$n") } }.awaitAll() }
        assertEquals(1, receipts.count { it.state == "queued" })
        assertEquals(11, receipts.count { it.state == "held" })
        assertEquals(1L, tasks.communicationSnapshot("g").charged)
        val duplicate = tasks.sendCommunication("session-a", "b", "message 1", "1")
        assertEquals(receipts.first().id, duplicate.id)
        assertEquals(1L, tasks.communicationSnapshot("g").charged)
        // A new repository instance reads the same durable pending messages and counters.
        val recreated = RoomBotAssignmentRepository(dao, Json)
        assertEquals(11L, recreated.communicationSnapshot("g").held)
        assertEquals(1, recreated.readCommunicationInbox("session-b").size) // held text cannot bypass quota
        recreated.approveCommunicationPolicy("g", 1, TeamCommunicationPolicy(groupLimit=12,memberLimit=12,rateLimit=100,queueLimit=100), "owner", "approval-2")
        assertEquals(12L, recreated.communicationSnapshot("g").charged)
        assertEquals(0L, recreated.communicationSnapshot("g").held)
        assertEquals("session-b", recreated.get("b")!!.sessionId)
        val first = recreated.readCommunicationInbox("session-b", 0, 5)
        val second = recreated.readCommunicationInbox("session-b", first.last().sequence, 100)
        assertEquals(12, (first + second).map { it.id }.toSet().size)
        assertTrue((first + second).all { recreated.getRequest(it.id)!!.status == "pending" })
        assertEquals(BotAssignmentStatus.RUNNING, recreated.get("b")!!.status)
    }

    @Test(timeout = 20_000) fun retryCannotMutateContentAndMemberCannotApprove(): Unit = runBlocking {
        seed()
        tasks.sendCommunication("session-a", "b", "original", "same")
        assertFailure { tasks.sendCommunication("session-a", "b", "changed", "same") }
        assertFailure { tasks.approveCommunicationPolicy("g", 1, TeamCommunicationPolicy(), "session-a", "spoof") }
        assertFailure { tasks.readCommunicationInbox("owner") }
        assertFailure { tasks.sendCommunication("owner", "b", "fake", "fake") }
        assertEquals(1L, tasks.communicationSnapshot("g").charged)
    }

    @Test(timeout = 20_000) fun hopBudgetIsRenewableAndQueueRateAreIndependent(): Unit = runBlocking {
        seed(TeamCommunicationPolicy(groupLimit=0,memberLimit=0,maxHop=1,rateLimit=1,rateWindowMs=100,queueLimit=1,unlimitedExplicit=true))
        val first = dao.sendCommunication("session-a", "b", "first", "1", 1000)
        dao.insertAssignmentRequest(BotAssignmentRequestEntity("peer:parent", "a", """{"hop":1}""", 1))
        val a = tasks.get("a")!!
        tasks.save(a.copy(activeRequestId="peer:parent"))
        val held = dao.sendCommunication("session-a", "b", "second", "2", 1001)
        assertEquals("hop_budget", held.holdReason)
        dao.approveCommunicationPolicy("g", 1, TeamCommunicationPolicy(groupLimit=0,memberLimit=0,maxHop=3,rateLimit=1,rateWindowMs=100,queueLimit=1,unlimitedExplicit=true), "owner", "hop-renewal", 1002)
        dao.resumeCommunicationPage("g", 0, 1002)
        assertEquals("rate_backpressure", dao.communication(held.id)!!.holdReason)
        dao.resumeCommunicationPage("g", 0, 1200)
        assertEquals("queue_backpressure", dao.communication(held.id)!!.holdReason)
        // Complete only the first queued request; no new group, task or session is created.
        dao.claimAssignmentRequest(first.id)
        dao.settleAssignmentRequest(first.id, "completed", "done", null)
        dao.resumeCommunicationPage("g", 0, 1201)
        assertEquals("queued", dao.communication(held.id)!!.state)
        assertEquals(2L, dao.communicationCharged("g"))
    }

    @Test(timeout = 20_000) fun releasedOldHoldIsVisibleAfterPreviouslyDeliveredCursor(): Unit = runBlocking {
        seed(TeamCommunicationPolicy(groupLimit=20,memberLimit=20,maxHop=1,rateLimit=100,queueLimit=100))
        dao.insertAssignmentRequest(BotAssignmentRequestEntity("peer:parent", "a", """{"hop":1}""", 1))
        tasks.save(tasks.get("a")!!.copy(activeRequestId="peer:parent"))
        val hold = tasks.sendCommunication("session-a", "b", "older held", "older")
        tasks.save(tasks.get("a")!!.copy(activeRequestId=null))
        val ready = tasks.sendCommunication("session-a", "b", "newer delivered", "newer")
        val cursor = tasks.readCommunicationInbox("session-b").single()
        assertEquals(ready.id, cursor.id)
        tasks.approveCommunicationPolicy("g", 1, TeamCommunicationPolicy(groupLimit=20,memberLimit=20,maxHop=4,rateLimit=100,queueLimit=100), "owner", "resume-hop")
        assertEquals(hold.id, tasks.readCommunicationInbox("session-b", cursor.sequence).single().id)
    }

    @Test(timeout = 20_000) fun cancelledTargetTerminatesHeldMessageWithoutRestart(): Unit = runBlocking {
        seed()
        tasks.sendCommunication("session-a", "b", "first", "1")
        val held = tasks.sendCommunication("session-a", "b", "hold", "2")
        tasks.cancelAssignment("b")
        tasks.approveCommunicationPolicy("g", 1, TeamCommunicationPolicy(groupLimit=5,memberLimit=5,rateLimit=100,queueLimit=100), "owner", "renew")
        assertEquals("cancelled", dao.communication(held.id)!!.state)
        assertEquals(BotAssignmentStatus.CANCELLED, tasks.get("b")!!.status)
        assertNull(tasks.getRequest(held.id))
        // Even an explicit later task status change must not revive a pre-cancellation hold.
        tasks.save(tasks.get("b")!!.copy(status=BotAssignmentStatus.RUNNING))
        tasks.resumeCommunications("g")
        assertEquals("cancelled", dao.communication(held.id)!!.state)
        assertNull(tasks.getRequest(held.id))
    }

    @Test(timeout = 20_000) fun ordinaryBotAndGroupSessionCannotApprove(): Unit = runBlocking {
        seed()
        db.harnessSessionDao().upsert(HarnessSessionEntity("dm-chief", "Bot", 1, 1, null))
        db.harnessSessionDao().upsert(HarnessSessionEntity("group-chief", "Group", 1, 1, null))
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO bot_group_sessions(botId,groupId,sessionId,createdAt) VALUES ('chief','g','group-chief',1)")
        for (session in listOf("dm-chief", "group-chief", "session-a")) {
            assertFailure { tasks.approveCommunicationPolicy("g", 1, TeamCommunicationPolicy(), session, "spoof") }
        }
        assertEquals(1, tasks.communicationSnapshot("g").revision)
    }

    @Test(timeout = 20_000) fun deletedSessionTerminatesHoldEvenIfTaskAndSessionRecreated(): Unit = runBlocking {
        seed()
        tasks.sendCommunication("session-a", "b", "first", "1")
        val held = tasks.sendCommunication("session-a", "b", "hold", "2")
        val original = requireNotNull(dao.findById("b"))
        db.harnessSessionDao().deleteSession("session-b")
        assertEquals("cancelled", dao.communication(held.id)!!.state)
        db.harnessSessionDao().upsert(HarnessSessionEntity("session-b", "Recreated", 1, 1, null))
        dao.insertIndependentAssignment(original)
        tasks.approveCommunicationPolicy("g", 1, TeamCommunicationPolicy(), "owner", "renew")
        assertEquals("cancelled", dao.communication(held.id)!!.state)
        assertNull(tasks.getRequest(held.id))
    }

    @Test(timeout = 20_000) fun inboxChecksEachSenderAndSkipsInvalidEarlyRowsWithoutLosingPage(): Unit = runBlocking {
        seed(TeamCommunicationPolicy(groupLimit=10,memberLimit=10))
        val rejected = tasks.sendCommunication("session-a", "b", "stale sender", "stale")
        db.harnessSessionDao().upsert(HarnessSessionEntity("session-chief", "Chief", 1, 1, null))
        val chief = requireNotNull(dao.findById("a")).copy(id="chief-task", botId="chief", sessionId="session-chief")
        dao.insertIndependentAssignment(chief)
        val accepted = tasks.sendCommunication("session-chief", "b", "valid sender", "valid")
        // Destination is still authorized; only the first message's sender changed version.
        db.openHelper.writableDatabase.execSQL("UPDATE bot_assignments SET teamBinding='team@2' WHERE id='a'")
        assertEquals(accepted.id, tasks.readCommunicationInbox("session-b", 0, 1).single().id)
        assertEquals("queued", dao.communication(rejected.id)!!.state)
        // Membership removal must also hide previously delivered text on subsequent reads.
        db.openHelper.writableDatabase.execSQL("DELETE FROM group_members WHERE groupId='g' AND botId='chief'")
        assertTrue(tasks.readCommunicationInbox("session-b").isEmpty())
    }

    @Test(timeout = 20_000) fun cancellingSenderCancelsOnlyUnclaimedRecipientRequests(): Unit = runBlocking {
        seed(TeamCommunicationPolicy(groupLimit=10,memberLimit=10,rateLimit=100,queueLimit=100))
        val first = tasks.sendCommunication("session-a", "b", "already claimed", "1")
        db.openHelper.writableDatabase.execSQL("UPDATE bot_assignments SET status='QUEUED' WHERE id='b'")
        assertTrue(dao.claimRequest(first.id, 100))
        val pending = tasks.sendCommunication("session-a", "b", "not claimed", "2")
        tasks.cancelAssignment("a")
        assertEquals("cancelled", dao.communication(pending.id)!!.state)
        assertEquals("cancelled", tasks.getRequest(pending.id)!!.status)
        assertEquals("submitting", tasks.getRequest(first.id)!!.status)
        assertFalse(dao.claimRequest(pending.id, 101))
        assertEquals(2L, tasks.communicationSnapshot("g").charged)
    }

    @Test(timeout = 20_000) fun deletingSenderSessionCancelsQueuedRecipientRequest(): Unit = runBlocking {
        seed()
        val mail = tasks.sendCommunication("session-a", "b", "cancel before dispatch", "1")
        db.harnessSessionDao().deleteSession("session-a")
        assertEquals("cancelled", tasks.getRequest(mail.id)!!.status)
        assertEquals("cancelled", dao.communication(mail.id)!!.state)
        db.openHelper.writableDatabase.execSQL("UPDATE bot_assignments SET status='QUEUED' WHERE id='b'")
        assertFalse(dao.claimRequest(mail.id, 100))
        assertEquals(1L, tasks.communicationSnapshot("g").charged)
    }

    @Test(timeout = 20_000) fun claimRechecksLedgerAfterDispatcherReadPendingSnapshot(): Unit = runBlocking {
        seed()
        val mail = tasks.sendCommunication("session-a", "b", "stale dispatch snapshot", "1")
        dao.markCommunicationsCancelledForAssignment("a")
        assertEquals("pending", tasks.getRequest(mail.id)!!.status)
        db.openHelper.writableDatabase.execSQL("UPDATE bot_assignments SET status='QUEUED' WHERE id='b'")
        assertFalse(dao.claimRequest(mail.id, 100))
        assertEquals("cancelled", tasks.getRequest(mail.id)!!.status)
    }

    private suspend fun assertFailure(block: suspend () -> Unit) {
        try { block(); fail("expected rejection") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
    }
}
