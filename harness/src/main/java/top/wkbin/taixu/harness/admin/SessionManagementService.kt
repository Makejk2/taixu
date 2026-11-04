package top.wkbin.taixu.harness.admin

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Immutable approval payload, not an authorization token. No deserialization/copy entry point. */
internal class SessionSnapshot private constructor(
    val sourceSessionId: String,
    val command: SessionCommand,
    val expected: ManagedSession?,
    val digest: String,
) {
    companion object {
        internal fun freeze(source: String, command: SessionCommand, expected: ManagedSession?): SessionSnapshot {
            val fields = listOf("session-management-v1", source) + when (command) {
                is SessionCommand.Create -> listOf("create", command.title, "foreground-switch")
                is SessionCommand.Rename -> listOf("rename", command.id, command.title)
                is SessionCommand.Delete -> listOf("delete", command.id, "stop-if-running")
                is SessionCommand.Archive -> listOf("archive", command.id)
            } + listOf(expected?.id, expected?.title, expected?.createdAt?.toString(),
                expected?.updatedAt?.toString(), expected?.revision, expected?.owner?.name,
                expected?.running?.toString())
            val bytes = fields.joinToString("") { it?.let { s -> "${s.length}:$s" } ?: "-1:" }
                .toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            return SessionSnapshot(source, command, expected, digest)
        }
    }
}

/** Domain core only, NOT a tool route or approval gate.
 * Trusted upper gate owns operation token, digest/call/source binding, expiry, one-shot consume,
 * main-lane/PLAN checks and real native approval before invoking executeApprovedNative.
 * Kotlin internal visibility is not an authorization boundary; never route this method directly.
 * This core intentionally does not mint tokens or accept model-supplied approval booleans.
 */
internal class SessionManagementService(private val port: SessionManagementPort) {
    suspend fun list(offset: Int = 0, limit: Int = 50): List<ManagedSession> {
        require(offset >= 0 && limit in 1..100) { "INVALID_PAGE" }
        val rows = port.listUserMetadata(offset, limit)
        check(rows.size <= limit && rows.all { it.owner == SessionOwner.USER }) { "INVALID_METADATA_PAGE" }
        return rows.toList()
    }

    suspend fun get(id: String): ManagedSession? {
        require(id.isNotBlank()) { "INVALID_SESSION_ID" }
        return port.metadata(id)?.takeIf { it.id == id && it.owner == SessionOwner.USER }
    }

    suspend fun preflight(sourceSessionId: String, command: SessionCommand): SessionSnapshot {
        require(get(sourceSessionId) != null) { "SOURCE_NOT_USER_SESSION" }
        val mutation = mutation(command)
        require(mutation in port.capabilities) { "UNSUPPORTED" }
        val target = when (command) {
            is SessionCommand.Create -> { title(command.title); null }
            is SessionCommand.Rename -> { title(command.title); target(command.id) }
            is SessionCommand.Delete -> {
                require(command.id != sourceSessionId) { "CURRENT_SOURCE_PROTECTED" }
                target(command.id)
            }
            is SessionCommand.Archive -> error("UNSUPPORTED")
        }
        return SessionSnapshot.freeze(sourceSessionId, command, target)
    }

    /** ONLY called by existing native gate after atomic one-shot token consumption.
     * Native UI must show create's foreground switch and delete's possible stop-only partial result.
     * CAS lives inside the port transaction/reservation, never in read-then-write service logic. */
    internal suspend fun executeApprovedNative(sourceSessionId: String, snapshot: SessionSnapshot): SessionExecution {
        require(sourceSessionId == snapshot.sourceSessionId) { "SOURCE_MISMATCH" }
        require(get(sourceSessionId) != null) { "SOURCE_NOT_USER_SESSION" }
        require(mutation(snapshot.command) in port.capabilities) { "UNSUPPORTED" }
        val intent = SessionAudit(sourceSessionId, snapshot.digest, null)
        port.audit(intent) // fail closed before any mutation
        var outcome = SessionExecution(SessionExecutionState.UNKNOWN_PARTIAL)
        try {
            outcome = when (val command = snapshot.command) {
                is SessionCommand.Create -> port.create(command.title)
                is SessionCommand.Rename -> port.compareAndRename(requireNotNull(snapshot.expected), command.title)
                is SessionCommand.Delete -> {
                    require(command.id != sourceSessionId) { "CURRENT_SOURCE_PROTECTED" }
                    port.compareAndDelete(requireNotNull(snapshot.expected), sourceSessionId)
                }
                is SessionCommand.Archive -> error("UNSUPPORTED")
            }
            // Treat adapter contract violations as unknown partial, never fabricate successful IDs.
            if (outcome.state == SessionExecutionState.APPLIED &&
                (outcome.sessionId.isNullOrBlank() ||
                    (snapshot.expected != null && outcome.sessionId != snapshot.expected.id) ||
                    (snapshot.command is SessionCommand.Delete && snapshot.expected?.running == true && !outcome.stopCompleted))) {
                outcome = SessionExecution(SessionExecutionState.UNKNOWN_PARTIAL)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            outcome = SessionExecution(SessionExecutionState.UNKNOWN_PARTIAL)
        } finally {
            // If completion audit fails, durable intent remains unresolved; upper gate must not retry.
            withContext(NonCancellable) { port.audit(intent.copy(outcome = outcome)) }
        }
        return outcome
    }

    private suspend fun target(id: String): ManagedSession = requireNotNull(get(id)) { "TARGET_NOT_USER_SESSION" }
    private fun title(value: String) {
        require(value.isNotBlank() && value.length <= 200 && value.none { it.isISOControl() }) { "INVALID_TITLE" }
    }
    private fun mutation(command: SessionCommand): SessionMutation = when (command) {
        is SessionCommand.Create -> SessionMutation.CREATE
        is SessionCommand.Rename -> SessionMutation.RENAME
        is SessionCommand.Delete -> SessionMutation.DELETE
        is SessionCommand.Archive -> throw IllegalArgumentException("UNSUPPORTED_ARCHIVE") // no repository API
    }
}

/** Process-local, short-lived, one-shot approval journal. Restart invalidates every proposal. */
internal class SessionManagementProposals(
    private val service: SessionManagementService,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttl: Long = 300_000,
) {
    private data class Proposal(val snapshot: SessionSnapshot, val expires: Long, var callId: String? = null)
    private val lock = Mutex()
    private val pending = linkedMapOf<String, Proposal>()
    suspend fun propose(source: String, command: SessionCommand): String {
        val snapshot = service.preflight(source, command)
        return lock.withLock {
            purge(); require(pending.size < 128) { "PROPOSAL_CAPACITY" }
            val id = UUID.randomUUID().toString()
            pending[id] = Proposal(snapshot, clock() + ttl)
            buildJsonObject { put("proposalId", id); put("digest", snapshot.digest); put("expiresAt", clock() + ttl) }.toString()
        }
    }
    suspend fun prepare(source: String, id: String, callId: String): String = lock.withLock {
        purge()
        val p = requireNotNull(pending[id]); require(p.snapshot.sourceSessionId == source && callId.isNotBlank())
        require(p.callId == null || p.callId == callId); p.callId = callId
        val command = p.snapshot.command
        "会话变更 ${command.javaClass.simpleName}；快照 ${p.snapshot.digest}。" +
            "创建将切换前台；删除会停止目标并清理全部运行资源，清理失败可能仅停止。仅本次原生审批有效。" +
            "目标：${p.snapshot.expected?.id ?: "新会话"}；标题：${when(command) { is SessionCommand.Create -> command.title; is SessionCommand.Rename -> command.title; else -> p.snapshot.expected?.title.orEmpty() }}"
    }
    suspend fun apply(source: String, id: String, callId: String): SessionExecution {
        val snapshot = lock.withLock {
            purge(); val p = requireNotNull(pending[id])
            require(p.snapshot.sourceSessionId == source && p.callId == callId)
            pending.remove(id); p.snapshot
        }
        return service.executeApprovedNative(source, snapshot)
    }
    suspend fun invalidate(source: String) = lock.withLock { pending.entries.removeAll { it.value.snapshot.sourceSessionId == source }; Unit }
    private fun purge() { pending.entries.removeAll { it.value.expires <= clock() } }
}

