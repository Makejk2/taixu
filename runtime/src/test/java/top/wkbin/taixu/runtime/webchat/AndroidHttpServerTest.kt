package top.wkbin.taixu.runtime.webchat

import java.io.File
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidHttpServerTest {

    @Test
    fun `serves a fixed length response over a socket`() {
        val port = reservePort()
        val server = AndroidHttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.createContext("/api/test") { exchange ->
            val body = "{\"ok\":true}".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
            exchange.close()
        }

        try {
            startWithDiagnostics(server, port)
            val response = Socket("127.0.0.1", port).use { socket ->
                    clientDiagnostic(socket)
                socket.soTimeout = 5_000
                socket.getOutputStream().apply {
                    write("GET /api/test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                socket.getInputStream().bufferedReader().readText()
            }

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("Content-Length: 11"))
            assertTrue(response.endsWith("{\"ok\":true}"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `stop closes open ended connections and releases the listening socket`() {
        val port = reservePort()
        val server = AndroidHttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        val streaming = CountDownLatch(1)
        server.createContext("/api/events") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write("event: ping\ndata: {}\n\n".toByteArray())
            exchange.responseBody.flush()
            streaming.countDown()
            // 故意不关闭：模拟 WebChat 的 SSE 长连接，回收责任落在 stop() 上。
        }

        val client = Socket()
        try {
            startWithDiagnostics(server, port)
            client.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            clientDiagnostic(client)
            client.soTimeout = 5_000
            client.getOutputStream().apply {
                write("GET /api/events HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                flush()
            }
            val input = client.getInputStream()
            assertTrue("SSE 首字节应可读", input.read() >= 0)
            assertTrue("handler 应已接管连接", streaming.await(5, TimeUnit.SECONDS))

            server.stop(0)

            // 连接被服务端关闭时读到 EOF 或 RST；若仍挂着，soTimeout 会抛超时 —— 那就是泄漏。
            val outcome = runCatching {
                var value = input.read()
                while (value >= 0) value = input.read()
            }
            assertFalse(
                "stop() 必须关闭已 accept 的长连接",
                outcome.exceptionOrNull() is SocketTimeoutException,
            )

            // 监听 socket 已释放：同一端口可以立刻重新绑定。
            ServerSocket().use { probe ->
                probe.reuseAddress = true
                diagnoseBind("post-stop-probe", port, "${probe.reuseAddress} (getter)") {
                    probe.bind(InetSocketAddress("127.0.0.1", port))
                    println("HTTP_PROBE local=${probe.localSocketAddress}")
                }
            }
        } finally {
            runCatching { client.close() }
            server.stop(0)
        }
    }

    @Test
    fun `sendFileResponse streams large files with an exact content length`() {
        val port = reservePort()
        val server = AndroidHttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        // 3MB：远超单次 read 缓冲，验证分块写入与 Content-Length 一致。
        val source = File.createTempFile("taixu-download", ".bin")
        val payload = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        Files.write(source.toPath(), payload)
        server.createContext("/api/download") { exchange ->
            sendFileResponse(exchange, 200, "application/octet-stream", source, fileName = "sample.bin")
        }

        try {
            startWithDiagnostics(server, port)
            val raw = Socket("127.0.0.1", port).use { socket ->
                    clientDiagnostic(socket)
                socket.soTimeout = 10_000
                socket.getOutputStream().apply {
                    write("GET /api/download HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                socket.getInputStream().readBytes()
            }
            // ISO-8859-1 与字节一一对应，切分头部时不会破坏二进制正文。
            val text = String(raw, Charsets.ISO_8859_1)
            val headerEnd = text.indexOf("\r\n\r\n") + 4
            val headers = text.substring(0, headerEnd)
            val body = text.substring(headerEnd).toByteArray(Charsets.ISO_8859_1)

            assertTrue(headers.startsWith("HTTP/1.1 200 OK"))
            assertTrue(headers.contains("Content-Length: ${payload.size}"))
            assertTrue(headers.contains("Content-Disposition: attachment; filename=\"sample.bin\""))
            assertEquals(payload.size, body.size)
            assertTrue(payload.contentEquals(body))
        } finally {
            source.delete()
            server.stop(0)
        }
    }

    @Test
    fun `server can be restarted after stop`() {
        val port = reservePort()
        val server = AndroidHttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.createContext("/api/ping") { exchange ->
            val body = "pong".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
            exchange.close()
        }

        try {
            repeat(2) {
                startWithDiagnostics(server, port, it + 1)
                val response = Socket("127.0.0.1", port).use { socket ->
                    clientDiagnostic(socket)
                    socket.soTimeout = 5_000
                    socket.getOutputStream().apply {
                        write("GET /api/ping HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                        flush()
                    }
                    socket.getInputStream().bufferedReader().readText()
                }
                assertTrue("第 ${it + 1} 次启动应正常响应", response.endsWith("pong"))
                server.stop(0)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `injected executor is owned by the caller and survives stop`() {
        val port = reservePort()
        val server = AndroidHttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        val injected = Executors.newSingleThreadExecutor()
        server.executor = injected

        try {
            startWithDiagnostics(server, port)
            server.stop(0)
            assertFalse("外部注入的线程池不应被 stop() 关闭", injected.isShutdown)
        } finally {
            injected.shutdownNow()
            server.stop(0)
        }
    }

    private fun reservePort(): Int = ServerSocket().use { probe ->
        diagnoseBind("reservation", 0, "${probe.reuseAddress} (getter)") {
            probe.bind(InetSocketAddress(0))
            println("HTTP_RESERVATION local=${probe.localSocketAddress}")
            probe.localPort
        }
    }

    private fun startWithDiagnostics(server: AndroidHttpServer, port: Int, ordinal: Int = 1) {
        // The server has no public listener getter; configured=true is not an observed socket value.
        diagnoseBind("start-$ordinal", port, "unavailable(no-public-getter) configured=true") { server.start() }
    }

    private fun clientDiagnostic(client: Socket) {
        println("HTTP_CLIENT local=${client.localSocketAddress} remote=${client.remoteSocketAddress}")
    }

    private fun <T> diagnoseBind(phase: String, port: Int, reuse: String, block: () -> T): T {
        val context = "phase=$phase port=$port reuse=$reuse " +
            "requestedLocal=${if (port == 0) "*:0" else "127.0.0.1:$port"} " +
            "jdk=${System.getProperty("java.runtime.version")} " +
            "java.version=${System.getProperty("java.version")} java.home=${System.getProperty("java.home")} " +
            "preferIPv4Stack=${System.getProperty("java.net.preferIPv4Stack", "<unset>")} " +
            "preferIPv6Addresses=${System.getProperty("java.net.preferIPv6Addresses", "<unset>")} " +
            "vendor=${System.getProperty("java.vendor")} os=${System.getProperty("os.version")}"
        println("HTTP_BIND $context")
        return try { block() } catch (failure: BindException) {
            // Diagnostics only, not a fix: inspect immediately, before caller cleanup, without retries.
            // Even stderr/proc failures must never replace the original BindException.
            runCatching {
                System.err.println("HTTP_BIND_FAILURE $context cause=$failure")
                dumpTargetPort(port)
            }
            throw failure // No retry, delay, alternate port, or weakened assertion.
        }
    }

    private fun dumpTargetPort(port: Int) {
        // At most 49 evidence lines plus the failure line. Never dump unrelated connections.
        var remaining = 49
        fun emit(message: String) {
            if (remaining > 0) {
                remaining--
                System.err.println(message)
            }
        }
        if (port !in 1..65535) {
            emit("HTTP_BIND_PROC skipped=no-concrete-target-port")
            return
        }
        val target = port.toString(16).padStart(4, '0')
        for (path in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            if (remaining == 0) break
            var matches = 0
            try {
                File(path).bufferedReader().use { reader ->
                    reader.readLine() // Header, not connection evidence.
                    while (remaining > 1) {
                        val line = reader.readLine() ?: break
                        val fields = line.trim().split(Regex("\\s+"), limit = 4)
                        // Include either endpoint to retain target-port TIME_WAIT evidence too.
                        if (fields.size >= 3 && listOf(fields[1], fields[2]).any {
                                it.substringAfterLast(':').equals(target, ignoreCase = true)
                            }) {
                            emit("HTTP_BIND_PROC source=$path targetPort=$port row=${line.trim()}")
                            matches++
                        }
                    }
                }
                emit("HTTP_BIND_PROC source=$path targetPort=$port matches=$matches " +
                    "scan=${if (remaining <= 1) "capped" else "complete"}")
            } catch (e: Exception) {
                // Exception type only: do not dump arbitrary command output or exception contents.
                emit("HTTP_BIND_PROC source=$path unavailable=${e.javaClass.simpleName}")
            }
        }
    }

}
