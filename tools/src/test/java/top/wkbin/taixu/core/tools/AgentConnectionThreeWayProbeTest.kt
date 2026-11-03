package top.wkbin.taixu.core.tools

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 三项连接探针：非流式/流式/工具调用分项判定的契约测试（MockWebServer，不出网）。 */
class AgentConnectionThreeWayProbeTest {

    private fun openAiDispatcher() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            // readUtf8 会消费 body，只能读一次存变量
            val body = request.body.readUtf8()
            return when {
                request.path.orEmpty().endsWith("/chat/completions") && body.contains("\"stream\":true") ->
                    MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\ndata: [DONE]\n\n")
                request.path.orEmpty().endsWith("/chat/completions") && body.contains("get_weather") ->
                    MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"c1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"北京\"}"}}]}}]}""")
                request.path.orEmpty().endsWith("/chat/completions") ->
                    MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"OK"}}]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @Test
    fun `all three probes pass on a healthy openai-compatible endpoint`() = runTest {
        val server = MockWebServer()
        server.dispatcher = openAiDispatcher()
        server.start()
        try {
            val result = AgentConnectionThreeWayProbe(OkHttpClient())
                .test(server.url("/v1").toString(), "gpt-x", "key")
            assertEquals("plain: ${result.plain.detail}", true, result.plain.success)
            assertEquals("stream: ${result.stream.detail}", true, result.stream.success)
            assertEquals("tool: ${result.tool.detail}", true, result.tool.success)
            assertTrue(server.takeRequest().body.readUtf8().contains("\"stream\":true").not())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `half broken gateway surfaces per-probe failures`() = runTest {
        val server = MockWebServer()
        // 流式探针返回非 SSE 纯 JSON（网关不支持流式的典型形态）；工具探针 400
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                return when {
                    body.contains("\"stream\":true") ->
                        MockResponse().setBody("""{"choices":[{"message":{"content":"OK"}}]}""")
                    body.contains("get_weather") -> MockResponse().setResponseCode(400)
                    request.path.orEmpty().endsWith("/chat/completions") ->
                        MockResponse().setBody("""{"choices":[{"message":{"content":"OK"}}]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val result = AgentConnectionThreeWayProbe(OkHttpClient())
                .test(server.url("/v1").toString(), "gpt-x", "key")
            assertEquals("plain: ${result.plain.detail}", true, result.plain.success)
            assertEquals("stream: ${result.stream.detail}", false, result.stream.success)
            assertEquals("stream detail: ${result.stream.detail}", true, result.stream.detail.contains("SSE"))
            assertEquals("tool: ${result.tool.detail}", false, result.tool.success)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `anthropic tool probe accepts tool_use block`() = runTest {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // readUtf8 会消费 body，只能读一次存变量
                val body = request.body.readUtf8()
                return when {
                    request.path.orEmpty().endsWith("/messages") && body.contains("get_weather") ->
                        MockResponse().setBody("""{"content":[{"type":"tool_use","id":"tu1","name":"get_weather","input":{"city":"北京"}}]}""")
                    request.path.orEmpty().endsWith("/messages") && body.contains("\"stream\":true") ->
                        MockResponse().setBody("event: message_start\ndata: {\"type\":\"message_start\"}\n\n")
                    request.path.orEmpty().endsWith("/messages") ->
                        MockResponse().setBody("""{"content":[{"type":"text","text":"OK"}]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val result = AgentConnectionThreeWayProbe(OkHttpClient())
                .test(server.url("/v1").toString(), "claude-x", "key", protocol = ProviderProtocol.ANTHROPIC)
            assertEquals("plain: ${result.plain.detail}", true, result.plain.success)
            assertEquals("stream: ${result.stream.detail}", true, result.stream.success)
            assertEquals("tool: ${result.tool.detail}", true, result.tool.success)
        } finally {
            server.shutdown()
        }
    }
}
