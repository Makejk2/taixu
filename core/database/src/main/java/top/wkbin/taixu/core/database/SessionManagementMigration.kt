package top.wkbin.taixu.core.database

import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** One coordinated v88 upgrade. Never unlock interrupted management reservations on open. */
val MIGRATION_87_88 = object : Migration(87, 88) {
    override fun migrate(db: SupportSQLiteDatabase) {
        SessionManagementSchema.install(db)
        db.execSQL(UserRuleSchema.CREATE_TABLE)
        db.execSQL(UserRuleSchema.CREATE_RECEIPTS)
        db.execSQL(SkillDiscoveryTombstoneSchema.CREATE)
        db.execSQL(WorkflowSourceAuthorizationSchema.CREATE)
        db.execSQL(WorkflowNativeReviewSchema.CREATE)
    }
}

/** Room creates entities itself; trigger installation is shared with migrated databases. */
object ManagementDatabaseCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) { SessionManagementSchema.install(db) }
}
