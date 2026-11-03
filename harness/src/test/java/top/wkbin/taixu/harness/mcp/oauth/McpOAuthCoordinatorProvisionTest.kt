package top.wkbin.taixu.harness.mcp.oauth

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.wkbin.taixu.core.database.AppDatabase
import top.wkbin.taixu.core.database.McpOAuthCredentialRepository
import top.wkbin.taixu.core.model.McpAuthMode
import top.wkbin.taixu.core.model.McpServerConfig
import top.wkbin.taixu.core.model.McpTransportType
import top.wkbin.taixu.core.security.SecretManager

/**
 * 自动预配管线（RFC 9728 → 8414 → 7591）的契约：provision() 只填空白字段、
 * 手动配置优先、DCR 签发的 client_secret 随事务/凭据链路传递、
 * 无法预配时报可行动错误。begin() 的落库由 Room 层既有测试模式覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class McpOAuthCoordinatorProvisionTest {

    private lateinit var coordinator: McpOAuthCoordinator
    private lateinit var server: MockWebServer
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // provision() 只触达发现端点，不读写凭据存储；begin() 才消费凭据表（由 Room 层覆盖）。
        coordinator = McpOAuthCoordinator(
            credentials = McpOAuthCredentialRepository(
                database.mcpOAuthCredentialDao(),
                database.mcpOAuthTransactionDao(),
                SecretManager(),
            ),
            client = OkHttpClient(),
            discovery = McpOAuthDiscovery(OkHttpClient()),
        )
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    private fun mcpConfig() = McpServerConfig(
        id = "srv",
        name = "三方 MCP",
        transportType = McpTransportType.SSE,
        serverUrl = "http://127.0.0.1:${server.port}/mcp",
        authMode = McpAuthMode.OAUTH,
    )

    private fun provisionFlow() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.path.orEmpty().startsWith("/.well-known/oauth-protected-resource") ->
                MockResponse().setBody(
                    """{"resource":"${server.url("/mcp")}","authorization_servers":["${server.url("/")}"]}""",
                )
            request.path.orEmpty().startsWith("/.well-known/oauth-authorization-server") ->
                MockResponse().setBody(
                    """{"authorization_endpoint":"${server.url("/authorize")}",""" +
                        """"token_endpoint":"${server.url("/token")}",""" +
                        """"registration_endpoint":"${server.url("/register")}",""" +
                        """"scopes_supported":["mcp:read","mcp:write"]}""",
                )
            request.path.orEmpty().startsWith("/register") ->
                MockResponse().setBody("""{"client_id":"dc-auto","client_secret":"s3cret","scope":"mcp:read mcp:write"}""")
            else -> MockResponse().setResponseCode(404)
        }
    }

    @Test
    fun `provisions endpoints client and secret via discovery and dcr`() = runTest {
        server.dispatcher = provisionFlow()
        server.start()
        val provisioned = coordinator.provision(mcpConfig())
        assertEquals("dc-auto", provisioned.config.oauthClientId)
        assertEquals(server.url("/authorize").toString(), provisioned.config.oauthAuthorizationEndpoint)
        assertEquals(server.url("/token").toString(), provisioned.config.oauthTokenEndpoint)
        assertEquals(server.url("/mcp").toString(), provisioned.config.oauthResource)
        assertEquals("mcp:read mcp:write", provisioned.config.oauthScope)
        assertEquals("s3cret", provisioned.clientSecret)
    }

    @Test
    fun `manual fields win and dcr is skipped`() = runTest {
        server.dispatcher = provisionFlow()
        server.start()
        val provisioned = coordinator.provision(
            mcpConfig().copy(
                oauthClientId = "manual-client",
                oauthAuthorizationEndpoint = "${server.url("/")}manual-authorize",
                oauthTokenEndpoint = "${server.url("/")}manual-token",
            ),
        )
        assertEquals("manual-client", provisioned.config.oauthClientId)
        assertNull(provisioned.clientSecret)
        assertEquals("${server.url("/")}manual-authorize", provisioned.config.oauthAuthorizationEndpoint)
        // 手动三件套齐全时 provision 直接短路：500ms 内不得有任何请求（无超时 takeRequest 会永久阻塞）
        assertNull(server.takeRequest(500, java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    @Test
    fun `fails with actionable error when discovery yields nothing`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(404)
        }
        server.start()
        val failure = runCatching { coordinator.provision(mcpConfig()) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("手动"))
    }
}
