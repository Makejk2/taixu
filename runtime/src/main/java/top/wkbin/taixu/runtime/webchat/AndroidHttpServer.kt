package top.wkbin.taixu.runtime.webchat

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small HTTP/1.1 server backed only by Android-supported java.net APIs.
 *
 * Android does not ship the desktop-JDK `com.sun.net.httpserver` module. This
 * adapter intentionally exposes only the subset WebChat needs: prefix routes,
 * fixed-length responses, and an open-ended response body for SSE.
 *
 * 资源归属：ServerSocket、缺省线程池与所有已 accept 的连接都由本类持有，
 * [stop] 必须把它们全部释放 —— 手机上服务会被反复启停，任何一项残留都会
 * 累积成常驻线程或悬挂的文件描述符。外部注入的线程池归注入方所有，不在此关闭。
 */
internal class AndroidHttpServer private constructor(
    private val address: InetSocketAddress,
    private val backlog: Int,
) {
    private val contexts = ConcurrentHashMap<String, AndroidHttpHandler>()

    /** 已 accept 且尚未关闭的连接，用于 [stop] 时回收 SSE 这类长连接。 */
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    /** accept 线程引用；[stop] 需要 join 它，确保返回时端口已真正释放。 */
    private var acceptThread: Thread? = null

    private var externalExecutor: Executor? = null
    private var ownedExecutor: ExecutorService? = null

    /**
     * 工作线程池。默认由本类懒创建并在 [stop] 中回收；一旦外部注入，
     * 关闭责任随之转移给注入方（本类只会释放自己创建的那个）。
     */
    var executor: Executor
        get() = currentExecutor()
        set(value) = synchronized(this) {
            ownedExecutor?.shutdownNow()
            ownedExecutor = null
            externalExecutor = value
        }

    fun createContext(path: String, handler: AndroidHttpHandler) {
        require(path.startsWith('/')) { "HTTP context path must start with /" }
        contexts[path] = handler
    }

    @Synchronized
    fun start() {
        if (running) return
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(address, backlog.coerceAtLeast(DEFAULT_BACKLOG))
        }
        serverSocket = socket
        running = true
        val thread = Thread({ acceptConnections(socket) }, "taixu-webchat-accept").apply {
            isDaemon = true
            start()
        }
        acceptThread = thread
    }

    fun stop(delaySeconds: Int) {
        var thread: Thread? = null
        synchronized(this) {
            running = false
            runCatching { serverSocket?.close() }
            serverSocket = null
            thread = acceptThread
            acceptThread = null
            // 已 accept 的连接不会随监听 socket 一起关闭：SSE 流可以挂住工作线程与 fd。
            activeSockets.forEach { runCatching { it.close() } }
            activeSockets.clear()
            ownedExecutor?.shutdownNow()
            ownedExecutor = null
        }
        // join 必须在监视器之外：accept 线程可能正卡在 currentExecutor() 的 synchronized 上，
        // 持锁等待会互锁。返回前必须等它退出，否则调用方立刻重绑同一端口仍会撞
        // ESTABLISHED 残留连接（SO_REUSEADDR 无法绕过）而抛 BindException。
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(ACCEPT_JOIN_TIMEOUT_MS) }
        }
        // 二次清扫：accept 线程可能在首轮 forEach 之后才登记连接，这里兜住。
        synchronized(this) {
            activeSockets.forEach { runCatching { it.close() } }
            activeSockets.clear()
        }
        if (delaySeconds > 0) {
            // The WebChat caller always requests an immediate stop. The argument
            // is kept to mirror the old API and make that intent explicit.
        }
    }

    private fun currentExecutor(): Executor = synchronized(this) {
        externalExecutor
            ?: ownedExecutor?.takeIf { !it.isShutdown }
            ?: createOwnedExecutor().also { ownedExecutor = it }
    }

    /**
     * 缺省线程池：并发上限与旧的 `newFixedThreadPool(8)` 一致，但核心线程允许超时回收
     * 且全部为 daemon —— 服务停止后不留常驻线程，也不会阻止进程退出。
     */
    private fun createOwnedExecutor(): ExecutorService = ThreadPoolExecutor(
        MAX_WORKER_THREADS,
        MAX_WORKER_THREADS,
        WORKER_KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
    ) { runnable -> Thread(runnable, "taixu-webchat-worker").apply { isDaemon = true } }
        .apply { allowCoreThreadTimeOut(true) }

    private fun acceptConnections(socket: ServerSocket) {
        while (running) {
            try {
                val client = socket.accept()
                // 先登记再检查：若 stop() 恰好在这两步之间跑完清扫，登记后的连接
                // 仍会被下面的 !running 分支关闭，不会泄漏成占端口的 ESTABLISHED。
                activeSockets.add(client)
                if (!running) {
                    activeSockets.remove(client)
                    runCatching { client.close() }
                    break
                }
                try {
                    currentExecutor().execute { handleClient(client) }
                } catch (_: RejectedExecutionException) {
                    activeSockets.remove(client)
                    runCatching { client.close() }
                }
            } catch (_: SocketException) {
                if (running) continue
                break
            } catch (_: Exception) {
                if (!running) break
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readHttpLine(input) ?: run {
                socket.close()
                return
            }
            val requestParts = requestLine.split(' ', limit = 3)
            if (requestParts.size < 2) {
                writeSimpleError(socket, 400, "Bad Request")
                return
            }

            val headers = AndroidHttpHeaders()
            var headerBytes = requestLine.length
            while (true) {
                val line = readHttpLine(input) ?: break
                headerBytes += line.length
                if (headerBytes > MAX_HEADER_BYTES) {
                    writeSimpleError(socket, 431, "Request Header Fields Too Large")
                    return
                }
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers.add(line.substring(0, separator).trim(), line.substring(separator + 1).trim())
                }
            }

            val contentLength = headers.getFirst("Content-Length")?.toIntOrNull() ?: 0
            if (contentLength !in 0..MAX_BODY_BYTES) {
                writeSimpleError(socket, 413, "Payload Too Large")
                return
            }
            val body = ByteArray(contentLength)
            var offset = 0
            while (offset < body.size) {
                val count = input.read(body, offset, body.size - offset)
                if (count < 0) break
                offset += count
            }

            val target = requestParts[1]
            val path = target.substringBefore('?').ifBlank { "/" }
            val query = target.substringAfter('?', "").ifBlank { null }
            val handler = contexts.entries
                .asSequence()
                .filter { path.startsWith(it.key) }
                .maxByOrNull { it.key.length }
                ?.value

            if (handler == null) {
                writeSimpleError(socket, 404, "Not Found")
                return
            }

            val exchange = AndroidHttpExchange(
                socket = socket,
                requestMethod = requestParts[0],
                requestURI = AndroidHttpUri(path, query),
                requestHeaders = headers,
                requestBody = ByteArrayInputStream(body, 0, offset),
                // 异步 handler 在协程里晚于本方法关闭连接，靠回调而不是轮询摘除。
                onClose = { activeSockets.remove(socket) },
            )
            handler.handle(exchange)
        } catch (_: Exception) {
            runCatching { socket.close() }
        } finally {
            // 覆盖未走到 exchange 的早退路径（400/404/431/413 与读取异常）。
            if (socket.isClosed) activeSockets.remove(socket)
        }
    }

    private fun writeSimpleError(socket: Socket, code: Int, message: String) {
        runCatching {
            val body = message.toByteArray(Charsets.UTF_8)
            val output = BufferedOutputStream(socket.getOutputStream())
            output.write("HTTP/1.1 $code $message\r\n".toByteArray(Charsets.US_ASCII))
            output.write("Content-Type: text/plain; charset=utf-8\r\n".toByteArray(Charsets.US_ASCII))
            output.write("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.write(body)
            output.flush()
        }
        runCatching { socket.close() }
    }

    /**
     * 逐字节读取一行。使用 [ByteArrayOutputStream] 而不是 `ArrayList<Byte>`：
     * 后者会为每个请求头字节分配一个装箱引用槽，8 KB 的行头即 8 K 个引用。
     */
    private fun readHttpLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(INITIAL_LINE_BYTES)
        while (buffer.size() <= MAX_LINE_BYTES) {
            val value = input.read()
            if (value < 0) {
                return if (buffer.size() == 0) null else buffer.toString(Charsets.US_ASCII.name())
            }
            if (value == '\n'.code) break
            if (value != '\r'.code) buffer.write(value)
        }
        if (buffer.size() > MAX_LINE_BYTES) throw IllegalArgumentException("HTTP line too long")
        return buffer.toString(Charsets.US_ASCII.name())
    }

    companion object {
        private const val DEFAULT_BACKLOG = 16
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val MAX_HEADER_BYTES = 32 * 1024
        // 32MB：容纳 WebChat 工作区上传（WorkspaceFileService.MAX_UPLOAD_BYTES 同值）。
        // 请求体按 Content-Length 一次性预读进内存，只有实际达到该大小的请求才会分配。
        private const val MAX_BODY_BYTES = 32 * 1024 * 1024
        private const val INITIAL_LINE_BYTES = 128
        private const val MAX_WORKER_THREADS = 8
        private const val WORKER_KEEP_ALIVE_SECONDS = 30L
        /** [stop] 等待 accept 线程退出的上限；超时只记录残留，绝不无限阻塞调用方。 */
        private const val ACCEPT_JOIN_TIMEOUT_MS = 2_000L

        fun create(address: InetSocketAddress, backlog: Int): AndroidHttpServer =
            AndroidHttpServer(address, backlog)
    }
}

internal fun interface AndroidHttpHandler {
    fun handle(exchange: AndroidHttpExchange)
}

internal data class AndroidHttpUri(
    val path: String,
    val query: String?,
)

internal class AndroidHttpHeaders {
    private val values = LinkedHashMap<String, MutableList<String>>()

    @Synchronized
    fun add(name: String, value: String) {
        val existingName = values.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
        values.getOrPut(existingName) { mutableListOf() }.add(value)
    }

    @Synchronized
    fun getFirst(name: String): String? =
        values.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    @Synchronized
    internal fun entries(): List<Pair<String, String>> =
        values.flatMap { (name, entries) -> entries.map { name to it } }
}

internal class AndroidHttpExchange(
    private val socket: Socket,
    val requestMethod: String,
    val requestURI: AndroidHttpUri,
    val requestHeaders: AndroidHttpHeaders,
    val requestBody: InputStream,
    private val onClose: () -> Unit = {},
) {
    val responseHeaders = AndroidHttpHeaders()
    private val output = BufferedOutputStream(socket.getOutputStream())
    val responseBody: OutputStream = output

    @Volatile
    private var responseStarted = false
    private val closed = AtomicBoolean(false)

    /** 响应是否已经开始：异常兜底路径据此决定还能不能再写一个错误响应。 */
    val isResponseStarted: Boolean
        get() = responseStarted

    @Synchronized
    fun sendResponseHeaders(code: Int, responseLength: Long) {
        check(!responseStarted) { "Response headers already sent" }
        responseStarted = true
        output.write("HTTP/1.1 $code ${statusText(code)}\r\n".toByteArray(Charsets.US_ASCII))
        responseHeaders.entries().forEach { (name, value) ->
            output.write("$name: $value\r\n".toByteArray(Charsets.US_ASCII))
        }
        if (responseLength > 0 && responseHeaders.getFirst("Content-Length") == null) {
            output.write("Content-Length: $responseLength\r\n".toByteArray(Charsets.US_ASCII))
        }
        if (responseLength > 0 && responseHeaders.getFirst("Connection") == null) {
            output.write("Connection: close\r\n".toByteArray(Charsets.US_ASCII))
        }
        output.write("\r\n".toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    /** 幂等：重复调用只会触发一次 [onClose]，SSE 广播失败与协程兜底可以放心各调一次。 */
    fun close() {
        runCatching { output.close() }
        runCatching { socket.close() }
        if (closed.compareAndSet(false, true)) runCatching { onClose() }
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        else -> "HTTP Response"
    }
}
