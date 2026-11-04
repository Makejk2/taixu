package top.wkbin.taixu.harness.admin.session

import java.util.UUID
import top.wkbin.taixu.core.database.SessionManagementPersistence
import top.wkbin.taixu.harness.session.InteractiveSessionControl
import top.wkbin.taixu.harness.session.SessionManagementRuntime

/** Native UI only. Never register claim/recovery as AI-callable approval bypasses.
 * authorize must check the current native user confirmation, epoch, and authoritative non-user
 * registries (workflow/subagent/bot/group/assignment); absence of a record alone is not ownership.
 * The application's lifecycle boundary must serialize recovery against ALL active runtime users.
 */
class NativeSessionRecovery(
    private val persistence: SessionManagementPersistence,
    private val control: () -> InteractiveSessionControl,
    private val authorize: suspend (String) -> Boolean,
) {
    suspend fun claimFromNative(id: String, expectedRevision: Long): Boolean {
        require(authorize(id)) { "SESSION_NATIVE_CONFIRMATION_REQUIRED" }
        val runtime = control() as? SessionManagementRuntime ?: error("SESSION_RUNTIME_REQUIRED")
        return runtime.reserveSessionManagement(id) { persistence.claimNative(id, expectedRevision) }
    }
    suspend fun isExplicitlyClaimed(id: String): Boolean = persistence.explicitlyClaimed(id)
    suspend fun pendingCleanupFromNative(): List<String> = persistence.pendingCleanupIds()

    /** Repeated cleanup is explicit, never automatic replay. Existing mode-5 intent stays fenced
     * on cancellation/failure. Each cleanup implementation must itself be idempotent. */
    suspend fun retryCleanupFromNative(id: String, protectedSourceSessionId: String) {
        require(id != protectedSourceSessionId && authorize(id)) { "SESSION_NATIVE_CONFIRMATION_REQUIRED" }
        val runtime = control() as? SessionManagementRuntime ?: error("SESSION_RUNTIME_REQUIRED")
        runtime.reserveSessionManagement(id) {
            persistence.recoverCleanup(id) {
                control().deleteSession(id)
            }
        }
    }

    suspend fun loadFromNative(id: String) {
        require(authorize(id)) { "SESSION_NATIVE_CONFIRMATION_REQUIRED" }
        val runtime = control() as? SessionManagementRuntime ?: error("SESSION_RUNTIME_REQUIRED")
        runtime.reserveSessionManagement(id) {
            check(persistence.isOpen(id)) { "SESSION_RESERVED" }
            control().loadSession(id)
        }
    }
}
