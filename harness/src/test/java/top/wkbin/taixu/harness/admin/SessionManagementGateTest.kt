package top.wkbin.taixu.harness.admin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SessionManagementGateTest {
    private class Fake : SessionManagementPort {
        override val capabilities = SessionMutation.entries.toSet()
        var writes = 0
        private val source = ManagedSession("source", "title", 1, 2, "r1", SessionOwner.USER, false)
        override suspend fun metadata(id: String) = source.takeIf { it.id == id }
        override suspend fun listUserMetadata(offset: Int, limit: Int) = listOf(source).drop(offset).take(limit)
        override suspend fun create(title: String): SessionExecution { writes++; return SessionExecution(SessionExecutionState.APPLIED, "new") }
        override suspend fun compareAndRename(expected: ManagedSession, title: String) = error("unused")
        override suspend fun compareAndDelete(expected: ManagedSession, sourceSessionId: String) = error("unused")
        override suspend fun audit(event: SessionAudit) = Unit
    }
    private suspend fun rejects(block: suspend () -> Unit) {
        try { block(); fail("expected rejection") } catch (_: IllegalArgumentException) { }
    }
    @Test fun proposalRequiresPrepareAndMatchingCallAndIsOneShot() = runBlocking {
        val port = Fake(); val gate = SessionManagementProposals(SessionManagementService(port), { 10L })
        val response = gate.propose("source", SessionCommand.Create("title"))
        val id = response.substringAfter("\"proposalId\":\"").substringBefore('"')
        rejects { gate.apply("source", id, "call") }
        gate.prepare("source", id, "call")
        rejects { gate.apply("source", id, "different") }
        rejects { gate.apply("other", id, "call") }
        assertEquals(SessionExecutionState.APPLIED, gate.apply("source", id, "call").state)
        rejects { gate.apply("source", id, "call") }; assertEquals(1, port.writes)
    }
    @Test fun expiryAndRevocationRejectWithoutWrites() = runBlocking {
        var time = 0L; val port = Fake()
        val gate = SessionManagementProposals(SessionManagementService(port), { time }, 5)
        suspend fun proposal() = gate.propose("source", SessionCommand.Create("title"))
            .substringAfter("\"proposalId\":\"").substringBefore('"')
        val expired = proposal(); gate.prepare("source", expired, "call"); time = 5
        rejects { gate.apply("source", expired, "call") }
        val revoked = proposal(); gate.prepare("source", revoked, "call"); gate.invalidate("source")
        rejects { gate.apply("source", revoked, "call") }; assertEquals(0, port.writes)
    }
}
