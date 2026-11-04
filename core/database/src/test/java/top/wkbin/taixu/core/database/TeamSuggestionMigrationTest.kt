package top.wkbin.taixu.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** CI must generate 87.json through KSP; do not hand-author the Room identity hash. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TeamSuggestionMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory())
    @Test fun migrationDoesNotInventOwnersForLegacyGroups() {
        val path = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath("suggestions-86-87").absolutePath
        helper.createDatabase(path, 86).close()
        helper.runMigrationsAndValidate(path, 87, true, MIGRATION_86_87).use { db ->
            listOf("team_group_owners", "team_root_owners", "team_suggestions").forEach { table ->
                db.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            }
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
    }
}
