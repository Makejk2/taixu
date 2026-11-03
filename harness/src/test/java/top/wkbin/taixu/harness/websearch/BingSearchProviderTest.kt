package top.wkbin.taixu.harness.websearch

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bing 免 Key 搜索源：HTML 解析与请求构造的契约测试（MockWebServer，不出网）。 */
class BingSearchProviderTest {

    private fun fixtureHtml(): String = """
        <html><body><ol id="b_results">
        <li class="b_algo"><h2><a href="https://kotlinlang.org/docs/home.html">Kotlin Documentation</a></h2>
        <div class="b_caption"><p>Kotlin &amp; Coroutines 官方文档&#65292;包含 A &lt; B 示例。</p></div></li>
        <li class="b_algo"><h2><a href="/relative/only">Relative should be skipped</a></h2><p>bad</p></li>
        <li class="b_algo"><h2><a href="https://kotlinlang.org/docs/coroutines-guide.html">Coroutines Guide</a></h2>
        <p>结构化并发官方指南。</p></li>
        </ol></body></html>
    """.trimIndent()

    @Test
    fun `parses algo blocks with entity decoding and skips relative urls`() = runTest {
        val hits = parseBingResults(fixtureHtml(), count = 8)
        assertEquals(2, hits.size)
        assertEquals("Kotlin Documentation", hits[0].title)
        assertEquals("https://kotlinlang.org/docs/home.html", hits[0].url)
        assertEquals("Kotlin & Coroutines 官方文档，包含 A < B 示例。", hits[0].snippet)
        assertEquals("https://kotlinlang.org/docs/coroutines-guide.html", hits[1].url)
    }

    @Test
    fun `respects count limit`() {
        val html = (1..10).joinToString("") { i ->
            """<li class="b_algo"><h2><a href="https://example.com/$i">结果 $i</a></h2><p>摘要 $i</p></li>"""
        }
        assertEquals(3, parseBingResults(html, count = 3).size)
    }

    @Test
    fun `requests bing with encoded query and parses served html`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(fixtureHtml()).setHeader("Content-Type", "text/html; charset=utf-8"))
        server.start()
        val provider = BingSearchProvider(
            client = OkHttpClient.Builder().build(),
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            endpoint = server.url("/search").toString().trimEnd('/'),
        )
        try {
            val hits = provider.search("kotlin 协程", count = 5)
            assertEquals(2, hits.size)
            val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
            val path = recorded.path.orEmpty()
            assertTrue(path.contains("q=kotlin+%E5%8D%8F%E7%A8%8B"))
            assertTrue(path.contains("count=5"))
            assertTrue(recorded.getHeader("User-Agent").orEmpty().contains("Mozilla/5.0"))
        } finally {
            server.shutdown()
        }
    }
}
