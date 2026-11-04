package top.wkbin.taixu.harness.workflow

import kotlinx.coroutines.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Charges the wall-clock union of active work, never the sum of parallel lanes. */
internal class WorkflowExecutionClock : AbstractCoroutineContextElement(Key) {
    private val monitor = Any()
    private var active = 0
    private var activeSince = 0L
    private var elapsed = 0L

    internal fun activate() = synchronized(monitor) {
        if (active++ == 0) activeSince = System.nanoTime()
    }
    internal fun deactivate() = synchronized(monitor) {
        check(active > 0)
        if (--active == 0) elapsed += System.nanoTime() - activeSince
    }
    fun remaining(limitMillis: Long): Long = synchronized(monitor) {
        val charged = elapsed + if (active > 0) System.nanoTime() - activeSince else 0L
        limitMillis - charged / 1_000_000
    }
    companion object Key : CoroutineContext.Key<WorkflowExecutionClock>
}

/** One independently suspendable work item; inherited clocks retain their own limits. */
internal class WorkflowWorkToken internal constructor(
    internal val clocks: List<WorkflowExecutionClock>,
) : AbstractCoroutineContextElement(Key) {
    private var pauses = 0
    private var closed = false
    init { clocks.forEach { it.activate() } }
    @Synchronized fun pause() {
        check(!closed)
        if (pauses++ == 0) clocks.forEach { it.deactivate() }
    }
    @Synchronized fun resume() {
        check(!closed && pauses > 0)
        if (--pauses == 0) clocks.forEach { it.activate() }
    }
    @Synchronized fun close() {
        if (!closed) {
            if (pauses == 0) clocks.forEach { it.deactivate() }
            closed = true
        }
    }
    companion object Key : CoroutineContext.Key<WorkflowWorkToken>
}

internal suspend fun <T> withWorkflowWorkToken(block: suspend () -> T): T {
    val parent = currentCoroutineContext()[WorkflowWorkToken] ?: return block()
    val token = WorkflowWorkToken(parent.clocks)
    return try { withContext(token) { block() } } finally { token.close() }
}

internal class WorkflowExecutionBudgetExceeded : IllegalStateException("工作流节点执行预算耗尽")

internal suspend fun <T> withWorkflowExecutionBudget(limitMillis: Long?, block: suspend () -> T): T = coroutineScope {
    if (limitMillis == null) return@coroutineScope block()
    require(limitMillis > 0)
    val parent = currentCoroutineContext()[WorkflowWorkToken]
    val clock = WorkflowExecutionClock()
    parent?.pause()
    val token = WorkflowWorkToken(parent?.clocks.orEmpty() + clock)
    try {
        val work = async(token) { block() }
        val watcher = launch {
            while (work.isActive) {
                val remaining = clock.remaining(limitMillis)
                if (remaining <= 0) { work.cancel(CancellationException("NODE_EXECUTION_BUDGET")); break }
                delay(minOf(remaining, 100L).coerceAtLeast(1))
            }
        }
        try { work.await() }
        catch (cancelled: CancellationException) {
            if (currentCoroutineContext().isActive && clock.remaining(limitMillis) <= 0) throw WorkflowExecutionBudgetExceeded()
            throw cancelled
        } finally { watcher.cancelAndJoin() }
    } finally {
        token.close()
        parent?.resume()
    }
}
