package top.wkbin.taixu.harness.team

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AiAdminGateTest {
    private fun args(s: String) = Json.parseToJsonElement(s).jsonObject
    @Test fun disabledAllowsInspectionRevocationAndEmergencyStopButNotNewManagement() {
        listOf("list", "get", "grants", "revoke").forEach {
            assertFalse(aiAdminRequiresEnabled("app_config", args("""{"action":"$it"}""")))
        }
        listOf("propose", "apply", "rollback").forEach {
            assertTrue(aiAdminRequiresEnabled("app_config", args("""{"action":"$it"}""")))
        }
        assertTrue(aiAdminRequiresEnabled("team", args("""{"action":"propose","operation":{"op":"create"}}""")))
        assertFalse(aiAdminRequiresEnabled("team", args("""{"action":"propose","operation":{"op":"stop"}}""")))
        // apply is allowed only after disableManagement has erased all proposals other than stop.
        assertFalse(aiAdminRequiresEnabled("team", args("""{"action":"apply","proposalId":"old"}""")))
    }
    @Test fun offOnWithoutIntermediateCallStillInvalidatesOldAuthority() = runBlocking {
        val gate = AiAdminEpochGate()
        var invalidated = 0
        assertTrue(gate.check(true to 1L) { invalidated++ })
        assertEquals(1, invalidated)
        assertTrue(gate.check(true to 1L) { invalidated++ })
        assertEquals(1, invalidated)
        // User switched off (revision 2) then on (3), with no calls between.
        assertTrue(gate.check(true to 3L) { invalidated++ })
        assertEquals(2, invalidated)
        assertFalse(gate.check(false to 4L) { invalidated++ })
        assertEquals(3, invalidated)
    }
}
