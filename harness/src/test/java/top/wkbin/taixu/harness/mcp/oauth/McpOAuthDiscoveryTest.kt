package top.wkbin.taixu.harness.mcp.oauth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** RFC 9728 / RFC 8414 发现与 RFC 7591 DCR 的 HTTP 契约测试（MockWebServer，不出网）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class McpOAuthDiscoveryTest {

    @Test
    fun `well-known candidates insert path before root`() {
        val discovery = McpOAuthDiscovery(OkHttpClient())
        assertEquals(
            listOf("https://mcp.example.com/.well-known/oauth-protected-resource/mcp", "https://mcp.example.com/.well-known/oauth-protected-resource"),
            discovery.wellKnownCandidates("https://mcp.example.com/mcp", ".well-known/oauth-protected-resource"),
        )
        assertEquals(
            listOf("https://mcp.example.com/.well-known/oauth-authorization-server"),
            discovery.wellKnownCandidates("https://mcp.example.com/", ".well-known/oauth-authorization-server"),
        )
        assertTrue(discovery.wellKnownCandidates("ftp://x/y", ".well-known/x").isEmpty())
    }

    @Test
    fun `discovers protected resource with path-aware well-known`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            """{"resource":"https://mcp.example.com/mcp","authorization_servers":["https://auth.example.com"],"scopes_supported":["mcp:read"]}""",
        ))
        server.start()
        try {
            val result = McpOAuthDiscovery(OkHttpClient()).discoverProtectedResource(server.url("/mcp").toString())
            assertEquals(listOf("https://auth.example.com"), result?.authorizationServers)
            assertEquals("https://mcp.example.com/mcp", result?.resource)
            assertTrue(server.takeRequest().path.orEmpty().endsWith("/.well-known/oauth-protected-resource/mcp"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `falls back to root well-known and then openid configuration`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody(
            """{"authorization_endpoint":"https://auth.example.com/authorize","token_endpoint":"https://auth.example.com/token","registration_endpoint":"https://auth.example.com/register","scopes_supported":["mcp:read","mcp:write"]}""",
        ))
        server.start()
        try {
            val discovery = McpOAuthDiscovery(OkHttpClient())
            // 前三个候选（AS 的 path-aware/根 + OIDC path-aware）404，第四个（OIDC 根）命中
            val metadata = discovery.discoverAuthorizationServer(server.url("/auth/v2").toString())
            assertEquals("https://auth.example.com/authorize", metadata?.authorizationEndpoint)
            assertEquals("https://auth.example.com/register", metadata?.registrationEndpoint)
            assertEquals(listOf("mcp:read", "mcp:write"), metadata?.scopesSupported)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `discovery failures return null instead of throwing`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("not-json"))
        server.start()
        try {
            assertNull(McpOAuthDiscovery(OkHttpClient()).discoverProtectedResource(server.url("/mcp").toString()))
            assertNull(McpOAuthDiscovery(OkHttpClient()).discoverAuthorizationServer(server.url("/").toString()))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `registers public client with pkce metadata`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            """{"client_id":"dc-123","client_secret":"s3cret","scope":"mcp:read"}""",
        ))
        server.start()
        try {
            val client = McpOAuthDiscovery(OkHttpClient())
                .registerClient(server.url("/register").toString(), "taixu://oauth/mcp", "太墟 (TaiXu)", "mcp:read")
            assertEquals("dc-123", client?.clientId)
            assertEquals("s3cret", client?.clientSecret)
            val recorded = server.takeRequest()
            val body = org.json.JSONObject(recorded.body.readUtf8())
            assertEquals("none", body.optString("token_endpoint_auth_method"))
            assertEquals("taixu://oauth/mcp", body.optJSONArray("redirect_uris")?.optString(0))
            assertEquals("authorization_code", body.optJSONArray("grant_types")?.optString(0))
            assertEquals("refresh_token", body.optJSONArray("grant_types")?.optString(1))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `dcr failure returns null`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_client_metadata"}"""))
        server.start()
        try {
            assertNull(
                McpOAuthDiscovery(OkHttpClient()).registerClient(server.url("/register").toString(), "taixu://oauth/mcp", "太墟", null),
            )
        } finally {
            server.shutdown()
        }
    }
}
