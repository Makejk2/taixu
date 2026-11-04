package top.wkbin.taixu.runtime.admin

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Reentry belongs to the exact coroutine, not children inheriting its context. Owner services
 * use the same mutex for native UI and approved admin lifecycle operations. */
internal class RuntimeOwnerFence(private val mutex: Mutex) {
    private class Entry(val fence: RuntimeOwnerFence, var job: Job? = null) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Entry>
    }
    suspend fun <T> use(block: suspend () -> T): T {
        val context = currentCoroutineContext()
        val entry = context[Entry]
        if (entry?.fence === this && entry.job === context[Job]) return block()
        return mutex.withLock {
            val owned = Entry(this)
            withContext(owned) {
                // withContext has its own Job: record it INSIDE the scope. A launched child
                // inherits Entry but has a different Job and cannot bypass the owner's mutex.
                owned.job = currentCoroutineContext()[Job]
                block()
            }
        }
    }
}
