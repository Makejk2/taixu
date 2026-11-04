package top.wkbin.taixu.harness.admin.runtime

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import top.wkbin.taixu.harness.admin.model.ModelAdminActor
import top.wkbin.taixu.harness.admin.model.ModelAdminAuthority

/** Native-only authority mirrors model admin. Never expose nativeReplay/epoch as JSON fields. */
class RuntimeAdminGate(
    private val authority: ModelAdminAuthority,
    private val epoch: () -> Long,
    private val enabled: () -> Boolean,
) {
    data class Preview(val id: String, val module: String, val action: String, val impact: String, val expiresAt: Long)
    private data class Pending(val actor: ModelAdminActor, val epoch: Long, val preview: Preview,
        val execute: suspend () -> Any?, val applyCall: String? = null, val applyOperation: String? = null)
    private val pending = ConcurrentHashMap<String, Pending>()

    private suspend fun guard(actor: ModelAdminActor) {
        require(enabled() && authority.isControlledUserSession(actor) && !authority.isPlan(actor))
    }
    suspend fun propose(actor: ModelAdminActor, module: String, action: String, impact: String,
        executeFrozen: suspend () -> Any?): Preview {
        guard(actor)
        require(module in setOf("distro", "terminal"))
        pending.entries.removeIf { it.value.preview.expiresAt < System.currentTimeMillis() }
        require(pending.size < 256)
        val view = Preview(UUID.randomUUID().toString(), module, action, impact, System.currentTimeMillis() + 120000)
        pending[view.id] = Pending(actor, epoch(), view, executeFrozen)
        return view
    }
    /** Invoke before generic grant/FULL_ACCESS handling and display returned impact natively. */
    suspend fun prepare(actor: ModelAdminActor, id: String): Preview {
        guard(actor)
        val p = requireNotNull(pending[id])
        require(p.actor.sessionId == actor.sessionId)
        require(p.epoch == epoch() && p.preview.expiresAt > System.currentTimeMillis())
        require(p.applyCall == null || (p.applyCall == actor.callId && p.applyOperation == actor.operationId))
        require(pending.replace(id, p, p.copy(applyCall = actor.callId, applyOperation = actor.operationId)))
        return p.preview
    }
    /** Only native approval continuation calls this, preserving original apply call identity. */
    suspend fun applyNative(actor: ModelAdminActor, id: String): Any? {
        guard(actor)
        val p = requireNotNull(pending[id])
        require(p.actor.sessionId == actor.sessionId && p.applyOperation == actor.operationId && p.applyCall == actor.callId)
        require(p.epoch == epoch() && p.preview.expiresAt > System.currentTimeMillis())
        require(pending.remove(id, p))
        return p.execute()
    }
    fun invalidate() { pending.clear() }
}

/** Dedicated typed native-UI gate: a native source session is NOT a main-lane model actor.
 * Internal entry points are not router actions; only the native controller can mint/consume them.
 * Confirmation consumes once under CAS; the executor still rechecks authorization + owner CAS. */
internal class NativeRuntimeImportGate {
    private data class Pending(val owner: String, val epoch: Long, val expiresAt: Long,
        val execute: () -> String, val release: () -> Unit)
    private val pending = ConcurrentHashMap<String, Pending>()

    fun propose(id: String, owner: String, epoch: Long, expiresAt: Long,
        execute: () -> String, release: () -> Unit) {
        check(pending.size < 32)
        check(pending.putIfAbsent(id, Pending(owner, epoch, expiresAt, execute, release)) == null)
    }
    fun confirmFromNative(id: String, owner: String, epoch: Long): String {
        val item = requireNotNull(pending[id])
        require(item.owner == owner && item.epoch == epoch && item.expiresAt > System.currentTimeMillis())
        check(pending.remove(id, item))
        return try { item.execute() } catch (failure: Throwable) {
            // Execution never took ownership; retain an explicit cleanup owner on failure.
            pending.putIfAbsent(id, item)
            throw failure
        }
    }
    fun discard(id: String) {
        val item = pending[id] ?: return
        // Keep cleanup ownership on deletion failure; expiration/native cancellation can retry.
        item.release()
        pending.remove(id, item)
    }
}
