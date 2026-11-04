package top.wkbin.taixu.harness.team

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import top.wkbin.taixu.core.database.TeamSuggestionRepository
import top.wkbin.taixu.core.model.bot.*

/** Repository failures are separately exercised against real Room/SQLite. No model API calls. */
class TeamSuggestionTest {
    private class Store : TeamSuggestionRepository {
        var sourceLive = true
        var legacyOwner: String? = null
        var legacyRevision = 0
        var legacyTaskStatus = "CANCELLED"
        var raceAtCommit = false
        override suspend fun legacyOwnershipSnapshot(sessionId: String, groupId: String): String {
            require(sessionId != "member" && groupId == "g" && legacyOwner == null)
            return "owner=$sessionId;workspace=/workspace/native;revision=$legacyRevision;status=$legacyTaskStatus"
        }
        override suspend fun reauthorizeLegacyGroup(sessionId: String, groupId: String, expected: String, approvalId: String) {
            if (raceAtCommit) legacyRevision++
            require(legacyOwnershipSnapshot(sessionId, groupId) == expected)
            legacyOwner = sessionId
        }
        val rows = linkedMapOf<String, TeamSuggestion>()
        override suspend fun requireOwner(sessionId: String, groupId: String) {
            require(sessionId == "owner" && groupId == "g")
        }
        override suspend fun requireMember(sessionId: String, groupId: String) {
            require(sourceLive && sessionId == "member" && groupId == "g")
        }
        override suspend fun verifyAdoptedSource(sessionId: String, groupId: String, id: String, proposalId: String) {
            requireOwner(sessionId, groupId)
            val row = rows.getValue(id)
            require(row.status == "adopted" && row.proposalId == proposalId)
            requireMember(row.sourceSessionId, groupId)
        }
        private suspend fun reader(session: String, group: String) {
            if (session == "owner") requireOwner(session, group) else requireMember(session, group)
        }
        override suspend fun list(sessionId: String, groupId: String): List<TeamSuggestion> {
            reader(sessionId, groupId)
            return rows.values.filter { it.groupId == groupId && (sessionId == "owner" || it.sourceSessionId == sessionId) }
        }
        override suspend fun get(sessionId: String, groupId: String, id: String) = list(sessionId, groupId).find { it.id == id }
        override suspend fun suggest(sessionId: String, groupId: String, id: String, key: String,
            reason: String, operation: String, before: String, now: Long): TeamSuggestion {
            requireMember(sessionId, groupId)
            rows.values.find { it.sourceSessionId == sessionId && it.idempotencyKey == key }?.let {
                require(it.operationJson == operation && it.reason == reason && it.groupId == groupId)
                return it
            }
            return TeamSuggestion(id, groupId, "native-assignment", sessionId, key, reason, operation, before, now, 1, "team@1", "persisted-membership").also { rows[id] = it }
        }
        override suspend fun decide(sessionId: String, groupId: String, id: String, status: String, proposalId: String?, now: Long): Boolean {
            requireOwner(sessionId, groupId)
            val old = rows.getValue(id)
            if (old.status != "pending") return false
            if (status == "adopted") requireMember(old.sourceSessionId, groupId)
            rows[id] = old.copy(status = status, proposalId = proposalId, decidedBy = sessionId, decidedAt = now)
            return true
        }
    }
    private fun args(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private val input = args("""{"action":"suggest","groupId":"g","idempotencyKey":"request-1","reason":"需要续额","operation":{"op":"communication-policy","groupId":"g","expectedRevision":0,"policy":{"groupLimit":200,"memberLimit":40,"maxHop":8}}}""")
    private fun decision(action: String, id: String, group: String = "g") = args("""{"action":"$action","groupId":"$group","suggestionId":"$id"}""")
    private suspend fun denied(block: suspend () -> Unit) {
        try { block(); fail("Expected rejection") }
        catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }
    private class Fixture {
        val port = TeamServiceTest.Port().also {
            it.groups["g"] = GroupChat("g", "G", "admin", createdAt = 1, teamGroupId = "team", teamVersion = 1)
        }
        val store = Store()
        var enabled = true
        val audit = mutableListOf<String>()
        val service = TeamService(object : TeamPort by port {
            override suspend fun memberSession(sessionId: String) = sessionId == "member"
        }, TeamAudit { _, event, _ -> audit += event }, suggestions = store, managementEnabled = { enabled })
    }
    @Test fun memberRequestBecomesNewOwnerProposalAndStillNeedsNativeApproval(): Unit = runBlocking {
        val f = Fixture()
        val first = f.service.execute("member", input)
        assertEquals(first, f.service.execute("member", input))
        val id = f.store.rows.keys.single()
        assertEquals("native-assignment", f.store.rows.getValue(id).sourceAssignmentId)
        denied { f.service.execute("member", decision("adopt", id)) }
        denied { f.service.execute("stranger", decision("adopt", id)) }
        denied { f.service.execute("owner", decision("suggestions", id, "other")) }
        denied { f.service.prepare("owner", id, "call") }
        val adopted = f.service.execute("owner", decision("adopt", id))
        val proposal = Regex("proposalId：([^\\n]+)").find(adopted)!!.groupValues[1]
        assertNotEquals(id, proposal)
        denied { f.service.execute("owner", decision("adopt", id)) }
        denied { f.service.prepare("member", proposal, "call") }
        denied { f.service.applyNative("owner", proposal, "not-armed") }
        assertEquals(0, f.port.writes)
        f.service.prepare("owner", proposal, "native")
        f.service.applyNative("owner", proposal, "native")
        assertEquals(200L, f.port.communication.policy.groupLimit)
        denied { f.service.applyNative("owner", proposal, "native") }
        assertTrue(f.audit.containsAll(listOf("suggested", "suggestion_adopted", "applied")))
    }
    @Test fun forgedIdentityCrossGroupAndChangedSnapshotFailClosed(): Unit = runBlocking {
        val f = Fixture()
        denied { f.service.execute("member", JsonObject(input + ("ownerSessionId" to JsonPrimitive("owner")))) }
        denied { f.service.execute("member", JsonObject(input + ("groupId" to JsonPrimitive("other")))) }
        val bad = args("""{"op":"communication-policy","groupId":"other","expectedRevision":0,"policy":{}}""")
        denied { f.service.execute("member", JsonObject(input + ("operation" to bad))) }
        f.service.execute("member", input)
        f.port.communication = f.port.communication.copy(revision = 1)
        denied { f.service.execute("owner", decision("adopt", f.store.rows.keys.single())) }
        assertEquals("pending", f.store.rows.values.single().status)
    }
    @Test fun disabledManagementAllowsOnlyScopedReadAndRejectIsTerminal(): Unit = runBlocking {
        val f = Fixture()
        f.service.execute("member", input)
        val id = f.store.rows.keys.single()
        f.enabled = false
        denied { f.service.execute("member", input) }
        denied { f.service.execute("owner", decision("adopt", id)) }
        denied { f.service.execute("owner", decision("reject", id)) }
        assertTrue(f.service.execute("member", decision("suggestions", id)).contains(id))
        assertFalse(aiAdminRequiresEnabled("team", decision("suggestions", id)))
        assertTrue(aiAdminRequiresEnabled("team", input))
        f.enabled = true
        f.service.execute("owner", decision("reject", id))
        assertEquals("rejected", f.store.rows.getValue(id).status)
        denied { f.service.execute("owner", decision("adopt", id)) }
    }

    @Test fun revokedBeforeAdoptAllowsRejectButCreatesNoUsableProposal(): Unit = runBlocking {
        val f = Fixture()
        f.service.execute("member", input)
        val id = f.store.rows.keys.single()
        f.store.sourceLive = false
        denied { f.service.execute("owner", decision("adopt", id)) }
        f.service.execute("owner", decision("reject", id))
        assertEquals("rejected", f.store.rows.getValue(id).status)
        assertEquals(0, f.port.writes)
    }
    @Test fun revokedAfterAdoptOrPreparePermanentlyConsumesProposalWithoutWrites(): Unit = runBlocking {
        for (prepareFirst in listOf(false, true)) {
            val f = Fixture()
            f.service.execute("member", input)
            val id = f.store.rows.keys.single()
            val output = f.service.execute("owner", decision("adopt", id))
            val proposal = Regex("proposalId：([^\n]+)").find(output)!!.groupValues[1]
            assertTrue(output.contains("suggestionId：$id"))
            if (prepareFirst) f.service.prepare("owner", proposal, "native")
            f.store.sourceLive = false
            if (prepareFirst) denied { f.service.applyNative("owner", proposal, "native") }
            else denied { f.service.prepare("owner", proposal, "native") }
            f.store.sourceLive = true
            denied { f.service.prepare("owner", proposal, "again") }
            denied { f.service.applyNative("owner", proposal, "native") }
            assertEquals(0, f.port.writes)
            assertTrue("suggestion_source_invalidated" in f.audit)
        }
    }
    private val takeover = args("""{"action":"propose","reason":"明确接管旧群，不恢复运行","operation":{"op":"reauthorize-group","groupId":"g"}}""")
    private suspend fun legacyProposal(f: Fixture, session: String = "owner"): String {
        val output = f.service.execute(session, takeover)
        assertTrue(output.contains("owner=$session"))
        assertTrue(output.contains("workspace=/workspace/native"))
        return Regex("proposalId：([^\n]+)").find(output)!!.groupValues[1]
    }
    @Test fun legacyTakeoverRequiresNativeApprovalAndSingleOwnerWithoutActivation(): Unit = runBlocking {
        val f = Fixture()
        val first = legacyProposal(f)
        val competitor = legacyProposal(f, "stranger")
        denied { f.service.execute("member", takeover) }
        denied { f.service.execute("owner", args("""{"action":"propose","reason":"forged","operation":{"op":"reauthorize-group","groupId":"g","ownerSessionId":"owner"}}""")) }
        denied { f.service.applyNative("owner", first, "unarmed") }
        assertNull(f.store.legacyOwner)
        f.service.prepare("owner", first, "native")
        f.service.prepare("stranger", competitor, "other-native")
        assertTrue(f.service.applyNative("owner", first, "native").contains("legacy reauthorized"))
        denied { f.service.applyNative("stranger", competitor, "other-native") }
        denied { f.service.applyNative("owner", first, "native") }
        assertEquals("owner", f.store.legacyOwner)
        assertEquals("CANCELLED", f.store.legacyTaskStatus)
        assertEquals(0, f.port.writes)
    }
    @Test fun legacySnapshotChangesAndCommitRaceConsumeApproval(): Unit = runBlocking {
        for (race in listOf(false, true)) {
            val f = Fixture()
            val id = legacyProposal(f)
            f.service.prepare("owner", id, "native")
            if (race) f.store.raceAtCommit = true else f.store.legacyRevision++
            denied { f.service.applyNative("owner", id, "native") }
            f.store.legacyRevision = 0
            f.store.raceAtCommit = false
            denied { f.service.prepare("owner", id, "retry") }
            assertNull(f.store.legacyOwner)
            assertEquals("CANCELLED", f.store.legacyTaskStatus)
        }
    }

}
