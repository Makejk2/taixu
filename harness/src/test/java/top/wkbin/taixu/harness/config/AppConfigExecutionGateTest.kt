package top.wkbin.taixu.harness.config

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import top.wkbin.taixu.core.model.RunMode
import top.wkbin.taixu.harness.*

class AppConfigExecutionGateTest {
    private val policy = ApprovalPolicyEngine(HarnessPathResolver())
    private var writes = 0
    private fun backend() = AppConfigToolBackend(AppConfigService(object : AppConfigPort {
        override suspend fun read(key: String) = "system"
        override suspend fun compareAndSet(key: String, expected: String, value: String): Boolean { writes++; return true }
    }, AppConfigGrantStore(), AppConfigAuditSink {}))
    private fun call(action: String, changeId: String? = null) = ToolCall("call", 1L, HarnessTool.APP_CONFIG, buildJsonObject {
        put("action", action)
        if (changeId != null) put("changeId", changeId)
    })
    @Test fun missingSessionsFailClosed() = runBlocking {
        var prompted = false
        val result = executeAppConfig(backend(), policy, { null }, call("list"), "missing", false, 1L) {
            prompted = true; error("Must not prompt")
        }
        assertFalse(result.success); assertFalse(prompted); assertEquals(0, writes)
    }
    @Test fun planCannotExecuteEvenNativeReplay() = runBlocking {
        var prompted = false
        val result = executeAppConfig(backend(), policy, { RunMode.PLAN }, call("apply", "id"), "s", true, 1L) {
            prompted = true; error("PLAN must not prompt")
        }
        assertFalse(result.success); assertFalse(prompted); assertEquals(0, writes)
    }
    @Test fun nativeApprovalIsRequiredBeforeFirstWrite() = runBlocking {
        val b = backend()
        val proposal = b.execute("s", buildJsonObject {
            put("action", "propose"); put("key", "appearance.theme"); put("value", "dark"); put("reason", "测试深色外观")
        })
        assertTrue(proposal.first)
        val id = Regex("changeId：([^\n]+)").find(proposal.second)!!.groupValues[1]
        var prompted = false
        val c = call("apply", id)
        val awaiting = executeAppConfig(b, policy, { RunMode.BUILD }, c, "s", false, 1L) { decision ->
            prompted = true
            assertEquals("critical", decision.riskLevel)
            assertTrue(decision.reason.contains("旧值：system"))
            assertTrue(decision.reason.contains("新值：dark"))
            ToolResult("result", 1L, c.id, false, "waiting", awaitingApproval = true)
        }
        assertTrue(awaiting.awaitingApproval); assertTrue(prompted); assertEquals(0, writes)
        val applied = executeAppConfig(b, policy, { RunMode.BUILD }, c, "s", true, 1L) { error("Already approved") }
        assertTrue(applied.success); assertEquals(1, writes)
    }
}
