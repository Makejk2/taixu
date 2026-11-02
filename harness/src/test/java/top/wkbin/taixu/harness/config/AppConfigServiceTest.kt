package top.wkbin.taixu.harness.config

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppConfigServiceTest {
    private var now = 1_000L
    private val values = mutableMapOf("appearance.theme" to "system", "appearance.font_scale" to "1.0", "terminal.font_size" to "13")
    private val entries = mutableListOf<AppConfigAuditEntry>()
    private val grants = AppConfigGrantStore { now }
    private val service = AppConfigService(object : AppConfigPort {
        override suspend fun read(key: String) = values[key] ?: "true"
        override suspend fun compareAndSet(key: String, expected: String, value: String): Boolean {
            if (read(key) != expected) return false
            values[key] = value
            return true
        }
    }, grants, AppConfigAuditSink { entries += it }, { now })
    private suspend fun proposal(key: String = "appearance.theme", value: String = "dark", session: String = "s"): String =
        id(service.propose(session, key, value, "用户需要更易读的显示", 60_000))
    private fun id(text: String) = text.substringAfter("changeId：").substringBefore('\n')
    private suspend fun denied(block: suspend () -> Unit) {
        try { block(); fail("Expected fail closed") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
    }
    @Test fun proposalIsNotApprovalAndJsonCannotApprove() = runBlocking {
        val p = proposal()
        assertTrue(service.approval("s", p).first)
        denied { service.apply("s", p, false) }
        assertFalse(grants.isActive("s", "appearance.theme"))
        assertEquals("system", values["appearance.theme"])
    }
    @Test fun trustedApprovalIssuesBoundedGrantAndRecordsOldNew() = runBlocking {
        val p = proposal()
        service.apply("s", p, true)
        assertEquals("dark", values["appearance.theme"])
        assertTrue(grants.isActive("s", "appearance.theme"))
        assertFalse(grants.isActive("other", "appearance.theme"))
        assertFalse(grants.isActive("s", "terminal.font_size"))
        assertTrue(entries.any { it.action == "write_success" && "旧值：system" in it.detail && "新值：dark" in it.detail })
        val next = proposal(value = "light")
        assertFalse(service.approval("s", next).first)
        service.apply("s", next, false)
        now += 60_001
        assertFalse(grants.isActive("s", "appearance.theme"))
    }
    @Test fun highRiskNeverUsesSessionGrant() = runBlocking {
        val p = proposal("appearance.font_scale", "1.2")
        service.apply("s", p, true)
        assertFalse(grants.isActive("s", "appearance.font_scale"))
        val next = proposal("appearance.font_scale", "1.1")
        assertTrue(service.approval("s", next).first)
        denied { service.apply("s", next, false) }
    }
    @Test fun rejectsOtherSessionExpiredAndReplayedProposals() = runBlocking {
        val p = proposal()
        denied { service.apply("other", p, true) }
        now += 600_001
        denied { service.apply("s", p, true) }
        val next = proposal()
        service.apply("s", next, true)
        denied { service.apply("s", next, true) }
    }
    @Test fun staleSnapshotsDoNotOverwriteManualChanges() = runBlocking {
        val p = proposal()
        values["appearance.theme"] = "light"
        denied { service.apply("s", p, true) }
        assertEquals("light", values["appearance.theme"])
        assertFalse(grants.isActive("s", "appearance.theme"))
    }
    @Test fun rollbackUsesOnlyVerifiedSameSessionSnapshot() = runBlocking {
        val p = proposal()
        denied { service.proposeRollback("s", p, "恢复") }
        service.apply("s", p, true)
        denied { service.proposeRollback("other", p, "恢复") }
        val restore = id(service.proposeRollback("s", p, "恢复之前主题"))
        service.apply("s", restore, false)
        assertEquals("system", values["appearance.theme"])
        denied { service.proposeRollback("s", p, "再次恢复") }
    }
    @Test fun permissionProposalsNeverGrantOrWriteAndUnknownConfigRejected() = runBlocking {
        assertTrue(service.propose("s", "approval.mode", "FULL_ACCESS", "提议", 60_000).contains("用户手动"))
        assertTrue(grants.issue("s", "approval.mode", "提议", 60_000) is GrantIssueResult.Rejected)
        denied { proposal("workshop.android_sdk_path", "/fake") }
        denied { proposal("appearance.font_scale", "NaN") }
        denied { proposal("terminal.font_size", "25") }
        denied { service.apply("s", "fake", true) }
    }
    @Test fun revocationAndRestartInvalidateAuthorization() = runBlocking {
        service.apply("s", proposal(), true)
        service.revoke("s", "appearance.theme")
        assertTrue(service.approval("s", proposal(value = "light")).first)
        assertFalse(AppConfigGrantStore { now }.isActive("s", "appearance.theme"))
    }
    @Test fun auditFailurePreventsWriteAndGrant() = runBlocking {
        val broken = AppConfigService(object : AppConfigPort {
            override suspend fun read(key: String) = "system"
            override suspend fun compareAndSet(key: String, expected: String, value: String): Boolean { fail("must not write without audit"); return false }
        }, grants, AppConfigAuditSink { error("database unavailable") }, { now })
        val p = id(broken.propose("s", "appearance.theme", "dark", "test", 60_000))
        denied { broken.apply("s", p, true) }
        assertFalse(grants.isActive("s", "appearance.theme"))
    }
}
