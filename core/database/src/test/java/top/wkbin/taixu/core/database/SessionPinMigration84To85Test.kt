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

/** MIGRATION_84_85：harness_sessions 新增 pinnedAt 列（NOT NULL DEFAULT 0）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionPinMigration84To85Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val dbPath: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(TEST_DB).absolutePath

    @Test
    fun `legacy rows default to unpinned and pin timestamp round-trips`() {
        helper.createDatabase(dbPath, 84).use { db ->
            db.execSQL(
                """INSERT INTO harness_sessions (id, title, createdAt, updatedAt, modelId, modelVariant, workspace, projectType, approvalMode, runMode, executionTargetJson)
                   VALUES ('s1', '会话一', 1, 1, NULL, NULL, '', '', 'full_access', 'build', 'unbound')""",
            )
        }
        helper.runMigrationsAndValidate(dbPath, 85, true, MIGRATION_84_85).use { db ->
            db.query("SELECT pinnedAt FROM harness_sessions WHERE id = 's1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0L, cursor.getLong(0))
            }
            db.execSQL("UPDATE harness_sessions SET pinnedAt = 42 WHERE id = 's1'")
            db.query("SELECT pinnedAt FROM harness_sessions WHERE id = 's1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals(42L, cursor.getLong(0))
            }
        }
    }

    private companion object {
        const val TEST_DB = "session-pin-migration-test.db"
    }
}
