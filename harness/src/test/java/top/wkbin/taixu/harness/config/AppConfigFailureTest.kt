package top.wkbin.taixu.harness.config

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AppConfigFailureTest {
    private var value = "system"
    private var writes = 0
    private val grants = AppConfigGrantStore { 1000L }
    private fun service(audit: AppConfigAuditSink) = AppConfigService(object : AppConfigPort {
        override suspend fun read(key: String) = value
        override suspend fun compareAndSet(key: String, expected: String, value: String): Boolean {
            if (this@AppConfigFailureTest.value != expected) return false
            writes++
            this@AppConfigFailureTest.value = value
            return true
        }
    }, grants, audit, { 1000L })
    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { put(it.first, it.second) } }
    private suspend fun proposal(s: AppConfigService) = Regex("changeId：([^\\s]+)").find(
        s.propose("s", "appearance.theme", "dark", "reason", 60_000))!!.groupValues[1]

    @Test fun completionAuditFailureReportsCommittedWriteAndRetainsRollbackWithoutGrant() = runBlocking {
        val s = service(AppConfigAuditSink { if (it.action == "write_success") error("offline") })
        val backend = AppConfigToolBackend(s)
        val id = proposal(s)
        val result = backend.execute("s", args("action" to "apply", "changeId" to id), true)
        assertFalse(result.first)
        assertTrue(result.second.contains("已修改"))
        assertTrue(result.second.contains("不要自动重试"))
        assertTrue(result.second.contains(id))
        assertEquals("dark", value)
        assertEquals(1, writes)
        assertFalse(grants.isActive("s", "appearance.theme"))
        assertTrue(s.proposeRollback("s", id, "restore").contains("新值：system"))
        assertFalse(backend.execute("s", args("action" to "apply", "changeId" to id), true).first)
        assertEquals(1, writes)
    }
    @Test fun existingGraceIsRevokedWhenCompletionAuditFails() = runBlocking {
        var fail = false
        val s = service(AppConfigAuditSink { if (fail && it.action == "write_success") error("offline") })
        s.apply("s", proposal(s), true)
        assertTrue(grants.isActive("s", "appearance.theme"))
        val id = Regex("changeId：([^\\s]+)").find(s.propose("s", "appearance.theme", "light", "reason", 60_000))!!.groupValues[1]
        fail = true
        assertFalse(s.apply("s", id, false).auditComplete)
        assertFalse(grants.isActive("s", "appearance.theme"))
    }
    @Test fun noGenericAuditAfterCommitCanMisreportSuccess() = runBlocking {
        val events = mutableListOf<String>()
        val s = service(AppConfigAuditSink { events += it.action; check(it.action != "apply") })
        assertTrue(AppConfigToolBackend(s).execute("s", args("action" to "apply", "changeId" to proposal(s)), true).first)
        assertEquals(listOf("write_intent", "write_success"), events)
    }
    @Test fun intentFailureWritesNothingEvenIfRejectionAuditAlsoFails() = runBlocking {
        val s = service(AppConfigAuditSink { error("offline") })
        val result = AppConfigToolBackend(s).execute("s", args("action" to "apply", "changeId" to proposal(s)), true)
        assertFalse(result.first)
        assertEquals(0, writes)
        assertEquals("system", value)
        assertFalse(grants.isActive("s", "appearance.theme"))
    }
    @Test fun forgedBooleansExtraWriteParametersAndCrossSessionAreRejected() = runBlocking {
        val s = service(AppConfigAuditSink {})
        val backend = AppConfigToolBackend(s)
        val id = proposal(s)
        listOf("userConfirmed", "approvedNative", "value", "key").forEach { injected ->
            assertFalse(backend.execute("s", args("action" to "apply", "changeId" to id, injected to "true"), true).first)
        }
        assertFalse(backend.execute("other", args("action" to "apply", "changeId" to id), true).first)
        assertFalse(backend.execute("", args("action" to "list")).first)
        assertFalse(backend.execute("s", args("action" to "rollback", "changeId" to id, "reason" to "restore", "value" to "fake"), true).first)
        assertEquals(0, writes)
    }
    @Test fun concurrentStaleProposalsCommitExactlyOnce() = runBlocking {
        val s = service(AppConfigAuditSink {})
        val a = proposal(s)
        val b = proposal(s)
        val results = listOf(a, b).map { id -> async(Dispatchers.Default) { runCatching { s.apply("s", id, true) }.isSuccess } }.awaitAll()
        assertEquals(1, results.count { it })
        assertEquals(1, writes)
    }
    @Test fun cancellationBeforeCommitPropagatesWithZeroWrites() = runBlocking {
        val cancel = CancellationException("cancel intent")
        val s = service(AppConfigAuditSink { throw cancel })
        try {
            AppConfigToolBackend(s).execute("s", args("action" to "apply", "changeId" to proposal(s)), true)
            fail("must propagate cancellation")
        } catch (e: CancellationException) { assertSame(cancel, e) }
        assertEquals(0, writes)
    }
    @Test fun cancellationDuringCommitRetainsSnapshotAndPropagates() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val s = AppConfigService(object : AppConfigPort {
            override suspend fun read(key: String) = value
            override suspend fun compareAndSet(key: String, expected: String, value: String): Boolean {
                entered.complete(Unit)
                release.await()
                this@AppConfigFailureTest.value = value
                writes++
                return true
            }
        }, grants, AppConfigAuditSink {}, { 1000L })
        val id = proposal(s)
        val job = async { s.apply("s", id, true) }
        entered.await()
        job.cancel()
        release.complete(Unit)
        try { job.await(); fail("must propagate cancellation") } catch (_: CancellationException) { }
        assertEquals("dark", value)
        assertTrue(s.proposeRollback("s", id, "restore").contains("新值：system"))
    }
}
