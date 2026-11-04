package top.wkbin.taixu.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Audit evidence only. No reusable token, command text, prompt or credentials. */
@Entity(tableName="workflow_native_reviews")
data class WorkflowNativeReviewEvent(@PrimaryKey val reviewId: String,val executionId: String,
    val nodeId: String,val requestDigest: String,val state: String,val updatedAt: Long)
object WorkflowNativeReviewSchema {
    const val CREATE="CREATE TABLE IF NOT EXISTS `workflow_native_reviews` (`reviewId` TEXT NOT NULL, `executionId` TEXT NOT NULL, `nodeId` TEXT NOT NULL, `requestDigest` TEXT NOT NULL, `state` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`reviewId`))"
}
