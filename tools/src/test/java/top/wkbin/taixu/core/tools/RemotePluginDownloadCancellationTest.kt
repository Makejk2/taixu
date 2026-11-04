package top.wkbin.taixu.core.tools

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class RemotePluginDownloadCancellationTest {
    @Test fun cancellationAfterEnteringBodyReadPreservesVerifiedParts(): Unit = runBlocking {
        val directory = Files.createTempDirectory("plugin-blocked-read-test").toFile()
        val enteredRead = CountDownLatch(1)
        val socketClosed = CountDownLatch(1)
        val socketError = IOException("Socket closed")
        val observed = CompletableDeferred<Throwable>()
        val client = responseClient(failingBody {
            enteredRead.countDown()
            check(socketClosed.await(15, TimeUnit.SECONDS)) { "Call.cancel did not close the socket" }
            throw socketError
        }, object : EventListener() {
            override fun canceled(call: Call) { socketClosed.countDown() }
        })
        val verifiedBytes = "verified-part".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(verifiedBytes).joinToString("") { "%02x".format(it) }
        val verified = RemotePluginPart("https://example.test/verified", verifiedBytes.size.toLong(), hash)
        val blocked = RemotePluginPart("https://example.test/blocked", 4, "0".repeat(64))
        val release = release(listOf(verified, blocked))
        val part = File(directory, "${release.sha256}/part-0").apply {
            parentFile!!.mkdirs()
            writeBytes(verifiedBytes)
        }
        try {
            val task = async(Dispatchers.IO) {
                try {
                    RemotePluginDownloader(client, directory).download(release) { _, _ -> }
                    observed.complete(AssertionError("Download unexpectedly succeeded"))
                } catch (failure: Throwable) {
                    observed.complete(failure)
                }
            }
            try {
                assertTrue("Body read was never entered", enteredRead.await(5, TimeUnit.SECONDS))
                task.cancel()
                withTimeout(5000) { task.join() }
                val failure = withTimeout(5000) { observed.await() }
                assertTrue("Expected cancellation, got $failure", failure is CancellationException)
                assertTrue(task.isCancelled)
                assertArrayEquals(verifiedBytes, part.readBytes())
                assertFalse(directory.walkTopDown().any { it.name == "package.txplugin" || it.name.endsWith(".tmp") })
            } finally {
                task.cancel()
                socketClosed.countDown()
                withTimeout(5000) { task.join() }
            }
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            directory.deleteRecursively()
        }
    }

    @Test fun bodyReadNetworkFailureRemainsOriginalIOException(): Unit = runBlocking {
        val networkError = IOException("connection reset by peer")
        assertNetworkFailurePreserved(responseClient(failingBody { throw networkError }), networkError)
    }

    @Test fun executeNetworkFailureRemainsOriginalIOException(): Unit = runBlocking {
        val networkError = IOException("connection refused")
        val client = OkHttpClient.Builder().addInterceptor { throw networkError }.build()
        assertNetworkFailurePreserved(client, networkError)
    }

    private suspend fun assertNetworkFailurePreserved(client: OkHttpClient, expected: IOException) {
        val directory = Files.createTempDirectory("plugin-network-error-test").toFile()
        try {
            try {
                RemotePluginDownloader(client, directory).download(release(listOf(
                    RemotePluginPart("https://example.test/plugin", 4, "0".repeat(64))
                ))) { _, _ -> }
                fail("Expected network IOException")
            } catch (actual: IOException) {
                // Coroutine debug stacktrace recovery may copy the exception across withContext.
                // Preserve exact IO type/message and original identity in its cause chain, not
                // necessarily at the outer boundary. This never accepts cancellation/success.
                assertEquals(expected.javaClass, actual.javaClass)
                assertEquals(expected.message, actual.message)
                var cursor: Throwable? = actual
                val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
                while (cursor != null && cursor !== expected && seen.add(cursor)) cursor = cursor.cause
                assertSame("Original network exception must survive coroutine recovery", expected, cursor)
            }
            assertFalse(directory.walkTopDown().any { it.name == "package.txplugin" || it.name.endsWith(".tmp") })
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            directory.deleteRecursively()
        }
    }

    private fun release(parts: List<RemotePluginPart>) = RemotePluginRelease(
        "example.plugin", "example", "Example", "", "PROCESS", "1.0.0",
        1, 1, parts.sumOf { it.size }, "0".repeat(64), parts
    )

    private fun failingBody(read: () -> Long): ResponseBody = object : ResponseBody() {
        private val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = read()
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = -1
        override fun source(): BufferedSource = source
    }

    private fun responseClient(body: ResponseBody, listener: EventListener = EventListener.NONE): OkHttpClient =
        OkHttpClient.Builder().eventListener(listener).addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body).build()
        }.build()

    @Test fun stoppingDownloadCancelsBlockedSocketWithoutPublishingPartialPackage(): Unit = runBlocking {
        val directory = Files.createTempDirectory("plugin-stop-test").toFile()
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("x".repeat(2 * 1024 * 1024)).throttleBody(1024, 1, TimeUnit.SECONDS))
        server.start()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url("/plugin")).build())
                .newBuilder().request(chain.request()).build()
        }.build()
        try {
            val size = 2L * 1024 * 1024
            val part = RemotePluginPart("https://example.test/plugin", size, "0".repeat(64))
            val release = RemotePluginRelease("example.plugin", "example", "Example", "", "PROCESS", "1.0.0",
                1, 1, size, "0".repeat(64), listOf(part))
            val task = async(Dispatchers.IO) { RemotePluginDownloader(client, directory).download(release) { _, _ -> } }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            task.cancel()
            withTimeout(5000) { task.join() }
            assertTrue(task.isCancelled)
            assertFalse(directory.walkTopDown().any { it.name == "package.txplugin" || it.name.endsWith(".tmp") })
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.shutdown()
            directory.deleteRecursively()
        }
    }
}
