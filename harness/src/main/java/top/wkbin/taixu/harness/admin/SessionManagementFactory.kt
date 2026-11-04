package top.wkbin.taixu.harness.admin

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import top.wkbin.taixu.core.database.*
import top.wkbin.taixu.core.database.repository.BotAssignmentRepository
import top.wkbin.taixu.core.database.repository.PersonaBotRepository
import top.wkbin.taixu.core.model.SessionRunState
import top.wkbin.taixu.harness.session.InteractiveSessionControl
import top.wkbin.taixu.harness.session.SessionManagementRuntime

/** Lazy control avoids ToolExecutor -> HarnessLoop construction cycles. No DAO deletion fallback.
 * nativeOwnership is mandatory: app must resolve workflow/subagent/internal provenance from its
 * authoritative runtime registries, not infer USER from a missing bot record or a name prefix.
 * Returning false/throwing rejects the session. This is deliberately not a permissive default. */
fun createSessionManagementBackend(
    sessions: HarnessSessionRepository,
    bots: PersonaBotRepository,
    assignments: BotAssignmentRepository,
    audit: AgentApprovalAuditRepository,
    control: () -> InteractiveSessionControl,
    nativeOwnership: suspend (String) -> Boolean,
    persistence: SessionManagementPersistence,
): SessionManagementBackend = SessionManagementBackend(SessionManagementService(object : SessionManagementPort {
    override val capabilities: Set<SessionMutation>
        get() = SessionMutation.entries.toSet()
    private suspend fun owner(id: String): SessionOwner = when {
        assignments.assignmentForSession(id) != null -> SessionOwner.ASSIGNMENT
        bots.getBotBySessionId(id) != null || id in bots.observeGroupSessionRefs().first() -> SessionOwner.BOT
        !nativeOwnership(id) -> SessionOwner.UNKNOWN
        else -> SessionOwner.USER
    }
    override suspend fun metadata(id: String): ManagedSession? {
        val snapshot = persistence.snapshot(id) ?: return null
        val entity = snapshot.session
        val owner = owner(id)
        val revision = snapshot.revision.toString()
        val state = control().sessionRunStates.value[id]
        return ManagedSession(id, entity.title, entity.createdAt, entity.updatedAt, revision, owner,
            state == SessionRunState.RUNNING || state == SessionRunState.WAITING_APPROVAL)
    }
    override suspend fun listUserMetadata(offset: Int, limit: Int): List<ManagedSession> = sessions.listAll()
        .sortedWith(compareBy({ it.createdAt }, { it.id })).mapNotNull { metadata(it.id) }
        .filter { it.owner == SessionOwner.USER }.drop(offset).take(limit)
    override suspend fun create(title: String): SessionExecution {
        val id = control().newSession(title)
        return if (metadata(id)?.owner == SessionOwner.USER) SessionExecution(SessionExecutionState.APPLIED, id)
        else SessionExecution(SessionExecutionState.UNKNOWN_PARTIAL, id)
    }
    private suspend fun mutate(expected: ManagedSession, body: suspend (String) -> SessionExecution): SessionExecution {
        val runtime = control() as? SessionManagementRuntime ?: return SessionExecution(SessionExecutionState.UNSUPPORTED)
        return runtime.reserveSessionManagement(expected.id) {
            if (metadata(expected.id) != expected || expected.owner != SessionOwner.USER) SessionExecution(SessionExecutionState.CONFLICT)
            else {
                val token = UUID.randomUUID().toString()
                val revision = expected.revision.toLongOrNull()
                if (revision == null || !persistence.acquire(expected.id, revision, token))
                    SessionExecution(SessionExecutionState.CONFLICT)
                else try { body(token) } finally { persistence.release(expected.id, token) }
            }
        }
    }
    override suspend fun compareAndRename(expected: ManagedSession, title: String) = mutate(expected) { token ->
        if (!persistence.rename(expected.id, token, title, System.currentTimeMillis()))
            return@mutate SessionExecution(SessionExecutionState.CONFLICT)
        val after = metadata(expected.id)
        if (after?.title == title && after.owner == SessionOwner.USER) SessionExecution(SessionExecutionState.APPLIED, expected.id)
        else SessionExecution(SessionExecutionState.UNKNOWN_PARTIAL, expected.id)
    }
    override suspend fun compareAndDelete(expected: ManagedSession, sourceSessionId: String): SessionExecution {
        if (expected.id == sourceSessionId) return SessionExecution(SessionExecutionState.CONFLICT)
        return mutate(expected) { token ->
            try {
                // HarnessLoop owns stop, join, approval/grant/environment/queue cleanup and row deletion.
                persistence.withDeleteLease(expected.id, token) { control().deleteSession(expected.id) }
                if (sessions.findById(expected.id) == null) SessionExecution(SessionExecutionState.APPLIED, expected.id, true)
                else SessionExecution(SessionExecutionState.UNKNOWN_PARTIAL, expected.id)
            } catch (e: CancellationException) { throw e }
            catch (failure: top.wkbin.taixu.harness.session.SessionManagementCleanupFailure) {
                SessionExecution(if (failure.stopCompleted) SessionExecutionState.PARTIAL else SessionExecutionState.UNKNOWN_PARTIAL,
                    expected.id, failure.stopCompleted)
            }
            catch (_: Exception) { SessionExecution(SessionExecutionState.UNKNOWN_PARTIAL, expected.id) }
        }
    }
    override suspend fun audit(event: SessionAudit) {
        audit.record(AgentApprovalAuditEntity(
            id = UUID.randomUUID().toString(), sessionId = event.sourceSessionId,
            kind = "session_management", toolName = "app_config",
            detail = "digest=${event.snapshotDigest};state=${event.outcome?.state?.name ?: "intent"}",
            createdAt = System.currentTimeMillis(),
        ))
    }
}))
