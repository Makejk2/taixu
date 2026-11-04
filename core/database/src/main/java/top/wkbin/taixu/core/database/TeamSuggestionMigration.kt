package top.wkbin.taixu.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v86 has no trustworthy owner provenance. Leave legacy rows unowned (fail closed). */
val MIGRATION_86_87 = object : Migration(86, 87) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `team_group_owners` (`groupId` TEXT NOT NULL, `ownerSessionId` TEXT NOT NULL, PRIMARY KEY(`groupId`), FOREIGN KEY(`groupId`) REFERENCES `group_chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `team_root_owners` (`rootWorkId` TEXT NOT NULL, `groupId` TEXT NOT NULL, `ownerSessionId` TEXT NOT NULL, PRIMARY KEY(`rootWorkId`), FOREIGN KEY(`groupId`) REFERENCES `team_group_owners`(`groupId`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_team_root_owners_groupId` ON `team_root_owners` (`groupId`)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `team_suggestions` (`id` TEXT NOT NULL, `groupId` TEXT NOT NULL, `sourceAssignmentId` TEXT NOT NULL, `sourceSessionId` TEXT NOT NULL, `idempotencyKey` TEXT NOT NULL, `reason` TEXT NOT NULL, `operationJson` TEXT NOT NULL, `beforeSnapshot` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `sourceRevision` INTEGER NOT NULL, `sourceTeamBinding` TEXT NOT NULL, `sourceMembershipStamp` TEXT NOT NULL, `status` TEXT NOT NULL, `proposalId` TEXT, `decidedBy` TEXT, `decidedAt` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`groupId`) REFERENCES `team_group_owners`(`groupId`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_team_suggestions_groupId_createdAt` ON `team_suggestions` (`groupId`, `createdAt`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_team_suggestions_sourceSessionId_idempotencyKey` ON `team_suggestions` (`sourceSessionId`, `idempotencyKey`)")
    }
}
