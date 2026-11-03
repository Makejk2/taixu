package top.wkbin.taixu.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** MIGRATION_83_84：MCP OAuth 凭据与事务表新增 clientSecretCiphertext 列（可空 TEXT）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class McpOAuthSecretMigration83To84Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        // Room 2.8 默认 JVM driver 与 Robolectric 的 databasePath 解析不一致，须显式 framework factory
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val dbPath: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(TEST_DB).absolutePath

    @Test
    fun `legacy rows survive and new columns accept writes`() {
        helper.createDatabase(dbPath, 83).use { db ->
            db.execSQL(
                """INSERT INTO mcp_oauth_credentials (serverId, accessTokenCiphertext, refreshTokenCiphertext,
                   tokenType, clientId, credentialRevision, updatedAt)
                   VALUES ('srv', 'enc-at', 'enc-rt', 'Bearer', 'client-1', 1, 100)""",
            )
            db.execSQL(
                """INSERT INTO mcp_oauth_transactions (state, serverId, codeVerifierCiphertext, redirectUri,
                   clientId, authorizationEndpoint, tokenEndpoint, createdAt, expiresAt)
                   VALUES ('st1', 'srv', 'enc-ver', 'taixu://oauth/mcp', 'client-1', 'https://a/authorize', 'https://a/token', 1, 200)""",
            )
        }
        helper.runMigrationsAndValidate(dbPath, 84, true, MIGRATION_83_84).use { db ->
            db.execSQL(
                """UPDATE mcp_oauth_credentials SET clientSecretCiphertext = 'enc-secret' WHERE serverId = 'srv'""",
            )
            db.execSQL(
                """UPDATE mcp_oauth_transactions SET clientSecretCiphertext = 'enc-secret' WHERE state = 'st1'""",
            )
            db.query("SELECT clientSecretCiphertext FROM mcp_oauth_credentials WHERE serverId = 'srv'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("enc-secret", cursor.getString(0))
            }
            db.query("SELECT clientSecretCiphertext FROM mcp_oauth_transactions WHERE state = 'st1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("enc-secret", cursor.getString(0))
            }
        }
    }

    private companion object {
        const val TEST_DB = "mcp-oauth-secret-migration-test.db"
    }
}
