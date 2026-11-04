package top.wkbin.taixu.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Consumption records only, not replayable approvals or credentials. */
@Entity(tableName="workflow_source_authorizations")
data class WorkflowSourceAuthorization(@PrimaryKey val authorizationId: String,
    val sourceSessionId: String,val sourceOperationId: String,val definitionHash: String,
    val targetHash: String,val consumedAt: Long,
    @androidx.room.ColumnInfo(defaultValue = "NULL") val executionId: String? = null)
object WorkflowSourceAuthorizationSchema {
    const val CREATE="CREATE TABLE IF NOT EXISTS `workflow_source_authorizations` (`authorizationId` TEXT NOT NULL, `sourceSessionId` TEXT NOT NULL, `sourceOperationId` TEXT NOT NULL, `definitionHash` TEXT NOT NULL, `targetHash` TEXT NOT NULL, `consumedAt` INTEGER NOT NULL, `executionId` TEXT DEFAULT NULL, PRIMARY KEY(`authorizationId`))"
}
