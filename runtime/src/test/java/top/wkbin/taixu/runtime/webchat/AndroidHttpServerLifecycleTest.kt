package top.wkbin.taixu.runtime.webchat

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Real sockets; monitor/latch barriers fix the interleaving without sleep or retries. */
class AndroidHttpServerLifecycleTest {
    @Test
    fun `closed old listener exits even while replacement is running`() {
        val server = newServer()
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 1)
            exchange.responseBody.write('x'.code)
            exchange.close()
        }
        try {
            server.start()
            val retired = state(server)
            val oldListener = listener(retired)
            val accept = acceptThread(oldListener)
            synchronized(server) {
                server.stop(0)
                server.start()
            }
            join(accept)
            assertTrue(oldListener.isClosed)
            assertNull(field(retired, "ownedExecutor"))
            request(server).use { assertEquals("replacement still serves requests", 'x'.code, it.getInputStream().read()) }
        } finally { server.stop(0) }
    }

    @Test
    fun `accepted old client cannot register or create a pool after stop and restart`() {
        val server = newServer()
        val entered = CountDownLatch(1)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            entered.countDown() // Keep the new connection open until stop.
        }
        var oldClient: Socket? = null
        var newClient: Socket? = null
        try {
            server.start()
            val retired = state(server)
            val oldListener = listener(retired)
            val accept = acceptThread(oldListener)
            synchronized(server) {
                oldClient = Socket("127.0.0.1", oldListener.localPort)
                awaitCondition("old accept must block at the registration monitor") {
                    accept.state == Thread.State.BLOCKED &&
                        accept.stackTrace.any { it.methodName == "acceptConnections" }
                }
                server.stop(0)
                server.start()
                assertTrue(oldListener.isClosed)
                assertNotSame(retired, state(server))
            }
            join(accept)
            assertNull("stale accept must not recreate a worker pool", field(retired, "ownedExecutor"))
            assertTrue("retired sockets must stay empty", sockets(retired).isEmpty())
            oldClient!!.soTimeout = 5_000
            assertEquals("unregistered old connection is closed", -1, oldClient!!.getInputStream().read())

            newClient = request(server)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(1, sockets(state(server)).size)
            assertFalse(sockets(state(server)).single().isClosed)
            server.stop(0)
            assertEquals(-1, newClient.getInputStream().read())
        } finally {
            oldClient?.close()
            newClient?.close()
            server.stop(0)
        }
    }

    @Test
    fun `retired stop cleanup cannot close new generation connections or pool`() {
        val server = newServer()
        val firstEntered = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val cleanup = CountDownLatch(1)
        val release = CountDownLatch(1)
        val errors = AtomicReference<Throwable?>()
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            if (firstEntered.count > 0) firstEntered.countDown() else secondEntered.countDown()
        }
        var oldClient: Socket? = null
        var newClient: Socket? = null
        var stopping: Thread? = null
        try {
            server.start()
            oldClient = request(server)
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            val retired = state(server)
            val owned = field(retired, "ownedExecutor") as ExecutorService
            val barrier = CleanupBarrier(owned, cleanup, release)
            setField(retired, "ownedExecutor", barrier)
            stopping = Thread {
                try { server.stop(0) } catch (failure: Throwable) { errors.set(failure) }
            }.apply { isDaemon = true; start() }
            assertTrue("old stop reached shutdown outside the monitor", cleanup.await(5, TimeUnit.SECONDS))
            server.start()
            newClient = request(server)
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
            val current = state(server)
            val newPool = field(current, "ownedExecutor") as ExecutorService
            release.countDown()
            join(stopping)
            assertNull(errors.get())
            assertTrue(owned.isShutdown)
            assertFalse("retired cleanup must not shut down new pool", newPool.isShutdown)
            assertSame(current, state(server))
            assertEquals(1, sockets(current).size)
            assertFalse("new connection survives old stop", sockets(current).single().isClosed)
            server.stop(0)
            assertEquals(-1, newClient.getInputStream().read())
        } finally {
            release.countDown()
            stopping?.join(5_000)
            oldClient?.close()
            newClient?.close()
            server.stop(0)
        }
    }

    @Test
    fun `direct executor handler can stop from another thread without monitor deadlock`() {
        val server = newServer()
        val done = CountDownLatch(1)
        val errors = AtomicReference<Throwable?>()
        server.executor = Executor { it.run() }
        server.createContext("/") { exchange ->
            try {
                val stopping = Thread { server.stop(0) }.apply { isDaemon = true; start() }
                join(stopping) // Would time out if execute/handler held the server monitor.
                exchange.close()
            } catch (failure: Throwable) { errors.set(failure) }
            finally { done.countDown() }
        }
        try {
            server.start()
            request(server, readHeaders = false).use { client ->
                assertTrue("direct handler completed", done.await(6, TimeUnit.SECONDS))
                errors.get()?.let { throw AssertionError("direct executor deadlock", it) }
                assertEquals(-1, client.getInputStream().read())
            }
        } finally { server.stop(0) }
    }

    @Test
    fun `queued external work from retired generation does not enter a handler`() {
        val server = newServer()
        val submitted = CountDownLatch(1)
        val task = AtomicReference<Runnable>()
        var calls = 0
        server.executor = Executor { task.set(it); submitted.countDown() }
        server.createContext("/") { calls++; it.close() }
        try {
            server.start()
            request(server, readHeaders = false).use {
                assertTrue(submitted.await(5, TimeUnit.SECONDS))
                server.stop(0)
                server.start()
                task.get().run()
                assertEquals(0, calls)
                assertTrue(sockets(state(server)).isEmpty())
                assertNull(field(state(server), "ownedExecutor"))
            }
        } finally { server.stop(0) }
    }

    private fun newServer() = AndroidHttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private fun state(server: AndroidHttpServer): Any = field(server, "state")!!
    private fun listener(state: Any) = field(state, "listener") as ServerSocket
    @Suppress("UNCHECKED_CAST")
    private fun sockets(state: Any) = field(state, "sockets") as Set<Socket>
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
    private fun setField(owner: Any, name: String, value: Any) = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(owner, value)

    private fun acceptThread(listener: ServerSocket): Thread {
        // At this point only this test's generation exists; stack confirms native accept.
        var found: Thread? = null
        awaitCondition("listener ${listener.localPort} entered accept") {
            found = Thread.getAllStackTraces().entries.firstOrNull { (thread, stack) ->
                thread.name == "taixu-webchat-accept" &&
                    stack.any { it.methodName == "acceptConnections" } &&
                    stack.any { it.methodName == "accept" }
            }?.key
            found != null
        }
        return found!!
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            if (System.nanoTime() >= deadline) fail(message)
            Thread.yield()
        }
    }

    private fun join(thread: Thread) {
        thread.join(5_000)
        assertFalse("thread must terminate: ${thread.name} ${thread.stackTrace.toList()}", thread.isAlive)
    }

    private fun request(server: AndroidHttpServer, readHeaders: Boolean = true): Socket {
        val client = Socket("127.0.0.1", listener(state(server)).localPort)
        client.soTimeout = 5_000
        client.getOutputStream().apply {
            write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            flush()
        }
        if (readHeaders) {
            var suffix = ""
            while (!suffix.endsWith("\r\n\r\n")) {
                val byte = client.getInputStream().read()
                assertTrue("response header must arrive", byte >= 0)
                suffix = (suffix + byte.toChar()).takeLast(4)
            }
        }
        return client
    }

    private class CleanupBarrier(
        private val delegate: ExecutorService,
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : AbstractExecutorService() {
        override fun shutdownNow(): MutableList<Runnable> {
            val pending = delegate.shutdownNow()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "cleanup release timed out" }
            return pending
        }
        override fun shutdown() = delegate.shutdown()
        override fun isShutdown() = delegate.isShutdown
        override fun isTerminated() = delegate.isTerminated
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = delegate.awaitTermination(timeout, unit)
        override fun execute(command: Runnable) = delegate.execute(command)
    }
}
