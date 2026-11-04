package top.wkbin.taixu.core.tools

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Profile resolution materializes keys before leaving this shared native lifetime boundary.
 * A cleanup holder can then remove an unreferenced encrypted value without racing stale rows. */
object ModelCredentialLifetime {
    private val owner=Mutex()
    suspend fun <T> serialized(block: suspend () -> T): T = owner.withLock { block() }
}
