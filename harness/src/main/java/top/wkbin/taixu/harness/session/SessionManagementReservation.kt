package top.wkbin.taixu.harness.session

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Admission fence. Acquired under the runtime's session mutex, but never held as a mutex while
 * cancelAndJoin drains a job. The coroutine-local lease permits cleanup's nested native calls. */
class SessionManagementReservation {
    private val owners = ConcurrentHashMap<String, Any>()
    private class Lease(val id: String, val token: Any) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Lease>
    }
    fun blocked(id: String): Boolean = owners.containsKey(id)
    suspend fun <T> reserve(id: String, acquire: suspend (suspend () -> Unit) -> Unit, block: suspend () -> T): T {
        val inherited = currentCoroutineContext()[Lease]
        if (inherited?.id == id && owners[id] === inherited.token) return block()
        val token = Any()
        acquire { check(owners.putIfAbsent(id, token) == null) { "SESSION_RESERVED" } }
        try { return withContext(Lease(id, token)) { block() } }
        finally { owners.remove(id, token) }
    }
    suspend fun checkEdit(id: String) {
        val owner = owners[id] ?: return
        val lease = currentCoroutineContext()[Lease]
        check(lease?.id == id && lease.token === owner) { "SESSION_RESERVED" }
    }
}

/** Implemented by the actual runtime, not by a repository adapter. */
interface SessionManagementRuntime {
    suspend fun <T> reserveSessionManagement(id: String, block: suspend () -> T): T
}

/** Cleanup failed after stop completed; durable delete intent must remain fenced for recovery. */
class SessionManagementCleanupFailure(val stopCompleted: Boolean, cause: Throwable) :
    RuntimeException("SESSION_CLEANUP_FAILED", cause)
