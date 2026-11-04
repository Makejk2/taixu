package top.wkbin.taixu.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Only custom low-authority rows; builtins are shipped read-only in code. */
@Entity(tableName = "user_management_rules")
data class UserManagementRule(
    @PrimaryKey val id: String,
    val enabled: Boolean,
    val scope: String,
    val scopeId: String?,
    val priority: Int,
    val content: String,
    val revision: Long = 1,
)

/** Persistence boundary: harness consumes this, never the Room DAO. */
interface UserRuleRepository {
    suspend fun list(): List<UserManagementRule>
    suspend fun get(id: String): UserManagementRule?
    suspend fun create(rule: UserManagementRule): Boolean
    suspend fun replace(rule: UserManagementRule, expectedRevision: Long): Boolean
    suspend fun delete(id: String, expectedRevision: Long): Boolean
    suspend fun consumed(owner: String): Long = error("RULE_BUDGET_UNAVAILABLE")
    suspend fun hasReceipt(owner: String, callKey: String): Boolean = error("RULE_BUDGET_UNAVAILABLE")
    suspend fun reserveReviewed(owner: String, callKey: String, maximum: Long?, revisions: Map<String, Long>): Boolean =
        error("RULE_REVISION_CAS_UNAVAILABLE")
    suspend fun reserve(owner: String, callKey: String, maximum: Long?): Boolean = error("RULE_BUDGET_UNAVAILABLE")
}

object UserRuleSchema {
    const val CREATE_RECEIPTS = "CREATE TABLE IF NOT EXISTS `user_rule_call_receipts` (`budgetOwner` TEXT NOT NULL, `callKey` TEXT NOT NULL, PRIMARY KEY(`budgetOwner`,`callKey`))"
    const val CREATE_TABLE = "CREATE TABLE IF NOT EXISTS `user_management_rules` (`id` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `scope` TEXT NOT NULL, `scopeId` TEXT, `priority` INTEGER NOT NULL, `content` TEXT NOT NULL, `revision` INTEGER NOT NULL, PRIMARY KEY(`id`))"
}

/** A stable operation/call identity is counted at most once, including native approval replay. */
@Entity(tableName = "user_rule_call_receipts", primaryKeys = ["budgetOwner", "callKey"])
data class UserRuleCallReceipt(val budgetOwner: String, val callKey: String)
