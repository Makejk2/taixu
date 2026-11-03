package top.wkbin.taixu.runtime

/** Commands may run concurrently; cleanup needs an idle runtime and prevents new launches. */
class StorageActivityGate {
    private val monitor = Any()
    private var active = 0
    private var cleaning = false

    /** Explicit pin survives its caller's coroutine; close is idempotent. */
    fun acquire(): AutoCloseable = synchronized(monitor) {
        check(!cleaning) { "正在清理存储，请稍后再启动任务" }
        active++
        var released = false
        AutoCloseable { synchronized(monitor) {
            if (!released) { released = true; active-- }
        } }
    }

    fun acquireTarget(distribution: String, installed: () -> Boolean): AutoCloseable {
        val pin = acquire()
        try {
            check(installed()) { "批准的发行版已不存在：$distribution" }
            return pin
        } catch (failure: Throwable) { pin.close(); throw failure }
    }

    suspend fun <T> activity(block: suspend () -> T): T {
        val pin = acquire()
        try { return block() } finally { pin.close() }
    }

    suspend fun <T> cleanup(block: suspend () -> T): T {
        synchronized(monitor) {
            check(!cleaning && active == 0) { "有命令或构建正在运行，请结束后再清理" }
            cleaning = true
        }
        try { return block() } finally { synchronized(monitor) { cleaning = false } }
    }
}
