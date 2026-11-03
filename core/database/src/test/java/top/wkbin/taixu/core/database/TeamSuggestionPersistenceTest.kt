package top.wkbin.taixu.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TeamSuggestionPersistenceTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = db.teamSuggestionDao()
    private val repo = RoomTeamSuggestionRepository(dao)
    @After fun close() = db.close()

    private suspend fun seed() {
        // Explicit schema-aware fixtures use actual SQLite tables, never fake authorization callbacks.
        val sql = db.openHelper.writableDatabase
        fun insert(table: String, values: Map<String, Any?>) {
            val row = values.toMutableMap()
            sql.query("PRAGMA table_info($table)").use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1)
                    if (name !in row && c.getInt(3) == 1 && c.isNull(4))
                        row[name] = if (c.getString(2) == "INTEGER") 1 else ""
                }
            }
            sql.execSQL("INSERT INTO $table (${row.keys.joinToString()}) VALUES (${row.keys.joinToString { "?" }})", row.values.toTypedArray())
        }
        for (s in listOf("owner", "stranger", "member", "member2", "workflow:execution:node", "subagent:child")) insert("harness_sessions", mapOf("id" to s))
        insert("persona_bots", mapOf("id" to "bot", "sessionId" to "bot-dm"))
        insert("bot_member_groups", mapOf("id" to "team"))
        insert("team_versions", mapOf("teamId" to "team", "version" to 1, "memberIdsJson" to "[\"bot\"]"))
        insert("group_chats", mapOf("id" to "g", "adminBotId" to "bot", "teamGroupId" to "team", "teamVersion" to 1))
        insert("group_chats", mapOf("id" to "other", "adminBotId" to "bot"))
        insert("group_members", mapOf("groupId" to "g", "botId" to "bot"))
        insert("bot_assignments", mapOf("id" to "task", "groupId" to "g", "botId" to "bot", "sessionId" to "member", "independent" to 1, "rootWorkId" to "root", "status" to "RUNNING", "teamBinding" to "team@1"))
        insert("bot_assignments", mapOf("id" to "task2", "groupId" to "g", "botId" to "bot", "sessionId" to "member2", "independent" to 1, "rootWorkId" to "root", "status" to "RUNNING", "teamBinding" to "team@1"))
        dao.insertTeamGroupOwner(TeamGroupOwnerEntity("g", "owner"))
        dao.bindNativeRootOwner("g", "root", "owner")
    }
    private suspend fun denied(block: suspend () -> Unit) {
        try { block(); fail("Expected rejection") }
        catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }
    @Test fun nativeAttributionIsolationAndOneShotDecision(): Unit = runBlocking {
        seed()
        val row = repo.suggest("member", "g", "s1", "key", "private reason", "{}", "snapshot", 1)
        assertEquals("task", row.sourceAssignmentId)
        assertEquals(row, repo.suggest("member", "g", "ignored", "key", "private reason", "{}", "new snapshot", 2))
        denied { repo.suggest("member", "g", "s2", "key", "changed reason", "{}", "snapshot", 2) }
        denied { repo.suggest("owner", "g", "s2", "key", "forged", "{}", "snapshot", 2) }
        denied { repo.get("stranger", "g", "s1") }
        denied { repo.get("member", "other", "s1") }
        assertNull(repo.get("member2", "g", "s1"))
        assertTrue(repo.list("member2", "g").isEmpty())
        assertEquals("private reason", repo.get("owner", "g", "s1")?.reason)
        denied { repo.decide("member", "g", "s1", "adopted", "p", 3) }
        denied { dao.bindNativeRootOwner("g", "root", "stranger") }
        assertTrue(repo.decide("owner", "g", "s1", "adopted", "p", 3))
        assertFalse(repo.decide("owner", "g", "s1", "adopted", "p2", 4))
        assertFalse(repo.decide("owner", "g", "s1", "rejected", null, 4))
        assertEquals("p", RoomTeamSuggestionRepository(dao).get("owner", "g", "s1")?.proposalId)
    }

    @Test fun cancelledSourceCannotBeAdoptedButOwnerCanReject(): Unit = runBlocking {
        seed()
        repo.suggest("member", "g", "s1", "key", "reason", "{}", "before", 1)
        db.openHelper.writableDatabase.execSQL("UPDATE bot_assignments SET status='CANCELLED',revision=revision+1 WHERE id='task'")
        denied { repo.decide("owner", "g", "s1", "adopted", "p", 2) }
        denied { repo.suggest("member", "g", "s2", "key2", "reason", "{}", "before", 2) }
        assertTrue(repo.decide("owner", "g", "s1", "rejected", null, 3))
    }
    @Test fun adoptedSourceMustRemainLiveAndKeepItsRevisionMembershipAndBinding(): Unit = runBlocking {
        seed()
        repo.suggest("member", "g", "s1", "key", "reason", "{}", "before", 1)
        assertTrue(repo.decide("owner", "g", "s1", "adopted", "p", 2))
        repo.verifyAdoptedSource("owner", "g", "s1", "p")
        val sql = db.openHelper.writableDatabase
        val changes = listOf(
            "UPDATE bot_assignments SET status='CANCELLED' WHERE id='task'" to
                "UPDATE bot_assignments SET status='RUNNING' WHERE id='task'",
            "UPDATE bot_assignments SET revision=revision+1 WHERE id='task'" to
                "UPDATE bot_assignments SET revision=revision-1 WHERE id='task'",
            "DELETE FROM group_members WHERE groupId='g'" to
                "INSERT INTO group_members(groupId,botId,displayOrder,joinedAt) VALUES('g','bot',1,1)",
            "UPDATE group_members SET joinedAt=joinedAt+1 WHERE groupId='g'" to
                "UPDATE group_members SET joinedAt=joinedAt-1 WHERE groupId='g'",
            "UPDATE group_chats SET teamVersion=2 WHERE id='g'" to
                "UPDATE group_chats SET teamVersion=1 WHERE id='g'",
            "UPDATE team_versions SET memberIdsJson='[]' WHERE teamId='team'" to
                "UPDATE team_versions SET memberIdsJson='[\"bot\"]' WHERE teamId='team'",
        )
        for ((change, restore) in changes) {
            sql.execSQL(change)
            denied { repo.verifyAdoptedSource("owner", "g", "s1", "p") }
            sql.execSQL(restore)
            repo.verifyAdoptedSource("owner", "g", "s1", "p")
        }
        denied { repo.verifyAdoptedSource("owner", "g", "s1", "other-proposal") }
        denied { dao.verifyMainUser("workflow:execution:node") }
        denied { dao.verifyMainUser("subagent:child") }
        denied { dao.bindNativeRootOwner("other", "legacy-root", "owner") }
        assertNull(dao.teamGroupOwner("other"))
    }
    private suspend fun legacySeed() {
        seed()
        db.openHelper.writableDatabase.execSQL("DELETE FROM team_root_owners")
        db.openHelper.writableDatabase.execSQL("DELETE FROM team_group_owners")
        db.openHelper.writableDatabase.execSQL("UPDATE harness_sessions SET workspace='/workspace/legacy' WHERE id='owner'")
        db.openHelper.writableDatabase.execSQL("UPDATE bot_assignments SET status='CANCELLED',proposalId='old-approval' WHERE groupId='g'")
    }
    @Test fun legacyTakeoverIsAtomicOneWinnerAndDoesNotReviveCancelledWork(): Unit = runBlocking {
        legacySeed()
        val owner = repo.legacyOwnershipSnapshot("owner", "g")
        val stranger = repo.legacyOwnershipSnapshot("stranger", "g")
        assertTrue(owner.contains("/workspace/legacy"))
        assertTrue(owner.contains("not historical ownership"))
        val tasks = dao.legacyTasks("g")
        // The native approval conversation itself updates chat activity, not authority.
        db.openHelper.writableDatabase.execSQL("UPDATE harness_sessions SET updatedAt=updatedAt+1 WHERE id='owner'")
        repo.reauthorizeLegacyGroup("owner", "g", owner, "native-once")
        denied { repo.reauthorizeLegacyGroup("stranger", "g", stranger, "competitor") }
        denied { repo.reauthorizeLegacyGroup("owner", "g", owner, "native-once") }
        assertEquals("owner", dao.teamGroupOwner("g")?.ownerSessionId)
        assertEquals(TeamRootOwnerEntity("root", "g", "owner"), dao.teamRootOwner("root"))
        assertEquals(tasks, dao.legacyTasks("g"))
        db.openHelper.readableDatabase.query("SELECT kind,detail FROM agent_approval_audits WHERE kind='team_legacy_reauthorized'").use {
            assertTrue(it.moveToFirst())
            assertTrue(it.getString(1).contains("not historical ownership"))
            assertFalse(it.moveToNext())
        }
    }
    @Test fun legacyFrozenFactsRejectChangesAndRollbackWithoutOwners(): Unit = runBlocking {
        val sql = db.openHelper.writableDatabase
        val changes = listOf(
            "UPDATE bot_assignments SET status='COMPLETED' WHERE id='task'",
            "UPDATE bot_assignments SET revision=revision+1 WHERE id='task'",
            "UPDATE bot_assignments SET sessionId='member2' WHERE id='task'",
            "UPDATE bot_assignments SET rootWorkId='changed-root' WHERE id='task'",
            "UPDATE harness_sessions SET workspace='/changed' WHERE id='member'",
            "UPDATE harness_sessions SET approvalMode='assisted' WHERE id='owner'",
            "UPDATE team_versions SET definitionJson='changed' WHERE teamId='team'",
            "UPDATE group_members SET joinedAt=joinedAt+1 WHERE groupId='g'",
            "UPDATE group_chats SET name='changed' WHERE id='g'",
        )
        changes.forEach { change ->
            // Each mutation starts from the same facts: moving task to member2 must not
            // remove member from the snapshot tested by the next workspace mutation.
            db.clearAllTables()
            legacySeed()
            val snapshot = repo.legacyOwnershipSnapshot("owner", "g")
            sql.execSQL(change)
            assertNotEquals("Mutation must change frozen facts: $change", snapshot,
                repo.legacyOwnershipSnapshot("owner", "g"))
            val tasks = dao.legacyTasks("g")
            denied { repo.reauthorizeLegacyGroup("owner", "g", snapshot, "p") }
            assertNull(dao.teamGroupOwner("g"))
            assertNull(dao.teamRootOwner("root"))
            assertNull(dao.teamRootOwner("changed-root"))
            assertEquals(tasks, dao.legacyTasks("g"))
            sql.query("SELECT COUNT(*) FROM agent_approval_audits WHERE kind='team_legacy_reauthorized'").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        }
    }
    @Test fun legacyRootCrossGroupAndAlreadyOwnedGroupsCannotBeSeized(): Unit = runBlocking {
        legacySeed()
        denied { repo.legacyOwnershipSnapshot("member", "g") }
        db.openHelper.writableDatabase.execSQL("UPDATE bot_assignments SET groupId='other' WHERE id='task2'")
        denied { repo.legacyOwnershipSnapshot("owner", "g") }
        assertNull(dao.teamGroupOwner("g"))
        db.openHelper.writableDatabase.execSQL("UPDATE bot_assignments SET groupId='g' WHERE id='task2'")
        // A root owner references team_group_owners, not merely group_chats.
        dao.insertTeamGroupOwner(TeamGroupOwnerEntity("other", "stranger"))
        dao.insertTeamRootOwner(TeamRootOwnerEntity("root", "other", "stranger"))
        denied { repo.legacyOwnershipSnapshot("owner", "g") }
        assertNull(dao.teamGroupOwner("g"))
        assertEquals(TeamRootOwnerEntity("root", "other", "stranger"), dao.teamRootOwner("root"))
        db.openHelper.writableDatabase.execSQL("DELETE FROM team_root_owners")
        dao.insertTeamGroupOwner(TeamGroupOwnerEntity("g", "stranger"))
        denied { repo.legacyOwnershipSnapshot("owner", "g") }
        assertEquals("stranger", dao.teamGroupOwner("g")?.ownerSessionId)
    }

    @Test fun twoNativeUsersCompeteForLegacyGroupOnlyOneCommits(): Unit = runBlocking {
        legacySeed()
        val snapshots = listOf("owner", "stranger").associateWith { repo.legacyOwnershipSnapshot(it, "g") }
        val outcomes = snapshots.map { (session, snapshot) ->
            async(Dispatchers.IO) {
                try { repo.reauthorizeLegacyGroup(session, "g", snapshot, "approved-$session"); true }
                catch (_: IllegalArgumentException) { false }
                catch (_: IllegalStateException) { false }
            }
        }.awaitAll()
        assertEquals(1, outcomes.count { it })
        val owner = requireNotNull(dao.teamGroupOwner("g"))
        assertEquals(owner.ownerSessionId, dao.teamRootOwner("root")?.ownerSessionId)
        assertTrue(dao.legacyTasks("g").all { it.status == "CANCELLED" })
    }
    @Test fun completedLegacyWorkAlsoStaysCompleted(): Unit = runBlocking {
        legacySeed()
        db.openHelper.writableDatabase.execSQL("UPDATE bot_assignments SET status='COMPLETED' WHERE groupId='g'")
        val tasks = dao.legacyTasks("g")
        repo.reauthorizeLegacyGroup("owner", "g", repo.legacyOwnershipSnapshot("owner", "g"), "native")
        assertEquals(tasks, dao.legacyTasks("g"))
    }

}
