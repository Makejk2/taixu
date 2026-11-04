package top.wkbin.taixu.harness.admin

/** Metadata only: never messages, executionTarget, model configuration, or credentials. */
internal data class ManagedSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: String,
    val owner: SessionOwner,
    val running: Boolean,
)
internal enum class SessionOwner { USER, BOT, ASSIGNMENT, WORKFLOW, SUBAGENT, UNKNOWN }
internal enum class SessionMutation { CREATE, RENAME, DELETE }
internal sealed interface SessionCommand {
    data class Create(val title: String) : SessionCommand
    data class Rename(val id: String, val title: String) : SessionCommand
    data class Delete(val id: String) : SessionCommand
    data class Archive(val id: String) : SessionCommand
}
internal enum class SessionExecutionState { APPLIED, CONFLICT, UNSUPPORTED, PARTIAL, UNKNOWN_PARTIAL }
internal data class SessionExecution(
    val state: SessionExecutionState,
    val sessionId: String? = null,
    val stopCompleted: Boolean = false,
)
internal data class SessionAudit(
    val sourceSessionId: String,
    val snapshotDigest: String,
    val outcome: SessionExecution?, // null = durable intent; record before any side effect
)

/** No default write implementation: unsupported adapters must advertise an empty capability set.
 * All metadata/revisions come from authoritative persistence + runtime ownership, not ID guesses.
 * revision changes on native edits/ownership/run changes (including ABA); never use updatedAt alone.
 * Reads filter internal sessions BEFORE pagination and use stable (createdAt,id) order.
 */
internal interface SessionManagementPort {
    val capabilities: Set<SessionMutation>
    suspend fun listUserMetadata(offset: Int, limit: Int): List<ManagedSession>
    suspend fun metadata(id: String): ManagedSession?
    /** Persist first, return the REAL id. InteractiveSessionControl.newSession switches foreground;
     * native approval must disclose that side effect. Never infer id from currentSessionId afterward. */
    suspend fun create(title: String): SessionExecution
    /** Atomic compare of entire expected snapshot + USER ownership, then update, not read/upsert.
     * Serialize with ALL native writers; no fallback to repository.rename without CAS. */
    suspend fun compareAndRename(expected: ManagedSession, title: String): SessionExecution
    /** Compare + reserve target against starts/edits/ownership changes before ANY stop/delete effects.
     * Reject target == source again under reservation. If running: real cancel(target), await stop,
     * then SessionControl.deleteSession(target), preserving full runtime/approval/grant cleanup.
     * Hold runtime admission fence across stop and cleanup; Room transaction alone is insufficient.
     * Never delete after failed stop. Return PARTIAL with stopCompleted=true if stop succeeded but
     * cleanup failed. CONFLICT/UNSUPPORTED guarantee NO side effects; exceptions mean unknown partial.
     * Plain repository.deleteSession is forbidden. No auto retries of any mutation.
     */
    suspend fun compareAndDelete(expected: ManagedSession, sourceSessionId: String): SessionExecution
    /** Durable audit; do not log titles/exception text. Failure of intent must prevent execution. */
    suspend fun audit(event: SessionAudit)
}
