package top.wkbin.taixu.harness.admin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SessionManagementServiceTest {
    private fun row(id: String, owner: SessionOwner = SessionOwner.USER, running: Boolean = false) =
        ManagedSession(id, "title", 1, 2, "revision-1", owner, running)

    private class Fake : SessionManagementPort {
        override var capabilities = SessionMutation.entries.toSet()
        val rows = linkedMapOf<String, ManagedSession>()
        val audits = mutableListOf<SessionAudit>()
        val effects = mutableListOf<String>()
        var beforeCompare: (() -> Unit)? = null
        var failDelete = false
        var failIntent = false
        var throwOnCreate = false
        override suspend fun listUserMetadata(offset: Int, limit: Int) = rows.values
            .filter { it.owner == SessionOwner.USER }.sortedWith(compareBy({ it.createdAt }, { it.id }))
            .drop(offset).take(limit)
        override suspend fun metadata(id: String) = rows[id]
        override suspend fun create(title: String): SessionExecution {
            effects += "create"
            if (throwOnCreate) error("secret exception content")
            val id = "real-created-id"
            rows[id] = ManagedSession(id, title, 3, 3, "revision-1", SessionOwner.USER, false)
            return SessionExecution(SessionExecutionState.APPLIED, id)
        }
        // Single-thread fake models an atomic native transaction; hook runs INSIDE port, after preflight.
        private fun matches(expected: ManagedSession): Boolean {
            beforeCompare?.invoke()
            return rows[expected.id] == expected && expected.owner == SessionOwner.USER
        }
        override suspend fun compareAndRename(expected: ManagedSession, title: String): SessionExecution {
            if (!matches(expected)) return SessionExecution(SessionExecutionState.CONFLICT)
            effects += "rename:${expected.id}"
            rows[expected.id] = expected.copy(title = title, revision = "revision-2")
            return SessionExecution(SessionExecutionState.APPLIED, expected.id)
        }
        override suspend fun compareAndDelete(expected: ManagedSession, sourceSessionId: String): SessionExecution {
            if (expected.id == sourceSessionId || !matches(expected)) return SessionExecution(SessionExecutionState.CONFLICT)
            if (expected.running) {
                effects += "stop:${expected.id}"
                rows[expected.id] = expected.copy(running = false, revision = "stopped")
            }
            if (failDelete) return SessionExecution(SessionExecutionState.PARTIAL, expected.id, expected.running)
            effects += "native-delete:${expected.id}"
            rows.remove(expected.id)
            return SessionExecution(SessionExecutionState.APPLIED, expected.id, expected.running)
        }
        override suspend fun audit(event: SessionAudit) {
            if (failIntent && event.outcome == null) error("audit unavailable")
            audits += event
        }
    }
    private fun fixture(): Pair<Fake, SessionManagementService> {
        val port = Fake()
        port.rows["source"] = row("source")
        port.rows["target"] = row("target")
        return port to SessionManagementService(port)
    }
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("expected rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun metadataPaginationAndCreateUseRealId() = runBlocking {
        val (p, s) = fixture()
        p.rows["bot"] = row("bot", SessionOwner.BOT)
        assertEquals(listOf("target"), s.list(1, 1).map { it.id })
        assertNull(s.get("bot"))
        rejected { s.list(0, 101) }
        val frozen = s.preflight("source", SessionCommand.Create("new"))
        assertTrue(p.effects.isEmpty())
        val result = s.executeApprovedNative("source", frozen)
        assertEquals("real-created-id", result.sessionId)
        assertEquals("new", s.get(result.sessionId!!)!!.title)
        assertEquals(2, p.audits.size)
    }

    @Test fun nativeEditBetweenPreflightAndTransactionRejects() = runBlocking {
        val (p, s) = fixture()
        val frozen = s.preflight("source", SessionCommand.Rename("target", "approved"))
        p.beforeCompare = { p.rows["target"] = p.rows.getValue("target").copy(title = "native", revision = "new") }
        assertEquals(SessionExecutionState.CONFLICT, s.executeApprovedNative("source", frozen).state)
        assertEquals("native", p.rows["target"]!!.title)
        assertTrue(p.effects.isEmpty())
    }

    @Test fun renameSucceedsAndStaleSnapshotConflicts() = runBlocking {
        val (_, s) = fixture()
        val snapshot = s.preflight("source", SessionCommand.Rename("target", "renamed"))
        assertEquals(SessionExecutionState.APPLIED, s.executeApprovedNative("source", snapshot).state)
        assertEquals("renamed", s.get("target")!!.title)
        assertEquals(SessionExecutionState.CONFLICT, s.executeApprovedNative("source", snapshot).state)
    }

    @Test fun differentSourceAndCurrentSourceAreRejected() = runBlocking {
        val (p, s) = fixture()
        val frozen = s.preflight("source", SessionCommand.Delete("target"))
        rejected { s.executeApprovedNative("target", frozen) }
        rejected { s.preflight("source", SessionCommand.Delete("source")) }
        assertTrue(p.effects.isEmpty())
        assertTrue(p.audits.isEmpty())
    }

    @Test fun internalOwnershipCannotBeDeletedOrRenamed() = runBlocking {
        val (p, s) = fixture()
        for (owner in SessionOwner.entries.filter { it != SessionOwner.USER }) {
            p.rows["target"] = row("target", owner)
            rejected { s.preflight("source", SessionCommand.Delete("target")) }
            rejected { s.preflight("source", SessionCommand.Rename("target", "new")) }
        }
        assertTrue(p.effects.isEmpty())
    }

    @Test fun ownershipChangeAndDisappearanceConflict() = runBlocking {
        val (p, s) = fixture()
        val snapshot = s.preflight("source", SessionCommand.Delete("target"))
        p.rows["target"] = row("target", SessionOwner.WORKFLOW)
        assertEquals(SessionExecutionState.CONFLICT, s.executeApprovedNative("source", snapshot).state)
        p.rows.remove("target")
        assertEquals(SessionExecutionState.CONFLICT, s.executeApprovedNative("source", snapshot).state)
        assertTrue(p.effects.isEmpty())
    }

    @Test fun runningDeleteStopsBeforeNativeCleanup() = runBlocking {
        val (p, s) = fixture()
        p.rows["target"] = row("target", running = true)
        val result = s.executeApprovedNative("source", s.preflight("source", SessionCommand.Delete("target")))
        assertEquals(SessionExecutionState.APPLIED, result.state)
        assertTrue(result.stopCompleted)
        assertEquals(listOf("stop:target", "native-delete:target"), p.effects)
        assertNull(s.get("target"))
    }

    @Test fun stopOnlyPartialIsAuditedTruthfully() = runBlocking {
        val (p, s) = fixture()
        p.rows["target"] = row("target", running = true)
        p.failDelete = true
        val result = s.executeApprovedNative("source", s.preflight("source", SessionCommand.Delete("target")))
        assertEquals(SessionExecutionState.PARTIAL, result.state)
        assertTrue(result.stopCompleted)
        assertEquals(listOf("stop:target"), p.effects)
        assertEquals(result, p.audits.last().outcome)
        assertNotNull(s.get("target"))
    }

    @Test fun archiveAndMissingAtomicCapabilityFailClosed() = runBlocking {
        val (p, s) = fixture()
        rejected { s.preflight("source", SessionCommand.Archive("target")) }
        p.capabilities = emptySet()
        rejected { s.preflight("source", SessionCommand.Rename("target", "new")) }
        assertTrue(p.effects.isEmpty())
    }

    @Test fun digestBindsSourceCommandAndNativeRevision() = runBlocking {
        val (p, s) = fixture()
        val command = SessionCommand.Rename("target", "a:b")
        val original = s.preflight("source", command)
        assertEquals(original.digest, s.preflight("source", command).digest)
        assertNotEquals(original.digest, s.preflight("source", command.copy(title = "b:a")).digest)
        assertNotEquals(original.digest, s.preflight("target", command).digest)
        p.rows["target"] = p.rows.getValue("target").copy(revision = "ABA-new-revision")
        assertNotEquals(original.digest, s.preflight("source", command).digest)
    }

    @Test fun portExceptionIsUnknownPartialWithoutLeakingAndNoRetry() = runBlocking {
        val (p, s) = fixture()
        p.throwOnCreate = true
        val result = s.executeApprovedNative("source", s.preflight("source", SessionCommand.Create("new")))
        assertEquals(SessionExecutionState.UNKNOWN_PARTIAL, result.state)
        assertEquals(listOf("create"), p.effects)
        assertEquals(result, p.audits.last().outcome)
        assertFalse(result.toString().contains("secret"))
    }

    @Test fun failedIntentAuditPreventsMutation() = runBlocking {
        val (p, s) = fixture()
        val snapshot = s.preflight("source", SessionCommand.Create("new"))
        p.failIntent = true
        try { s.executeApprovedNative("source", snapshot); fail("expected audit failure") }
        catch (_: IllegalStateException) { }
        assertTrue(p.effects.isEmpty())
    }
}
