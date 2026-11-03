package top.wkbin.taixu.runtime.webchat

import java.util.concurrent.TimeUnit

/** Tracks only native accept calls, never executors/handlers or another server generation. */
internal class HttpAcceptBarrier {
    private val monitor = java.lang.Object()
    private var retired = false
    private var inFlight = 0

    fun <T> accept(block: () -> T): T? {
        synchronized(monitor) {
            if (retired) return null
            inFlight++
        }
        try { return block() }
        finally { synchronized(monitor) { inFlight--; monitor.notifyAll() } }
    }

    fun retire() { synchronized(monitor) { retired = true } }

    /** close() can defer native descriptor release until the blocking accept has unwound. */
    fun awaitReleased(timeoutMillis: Long = 2_000) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        synchronized(monitor) {
            check(retired) { "Listener must be retired before awaiting native accept release" }
            while (inFlight != 0) {
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "Native accept did not release listener within ${timeoutMillis}ms" }
                try { TimeUnit.NANOSECONDS.timedWait(monitor, remaining) }
                catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("Interrupted waiting for native listener release", interrupted)
                }
            }
        }
    }
}
