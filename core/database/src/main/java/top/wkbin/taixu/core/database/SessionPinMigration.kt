package top.wkbin.taixu.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 84→85：会话置顶时间戳列（0 = 未置顶；会话抽屉置顶分区排序键）。 */
val MIGRATION_84_85 = object : Migration(84, 85) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE harness_sessions ADD COLUMN pinnedAt INTEGER NOT NULL DEFAULT 0")
    }
}
