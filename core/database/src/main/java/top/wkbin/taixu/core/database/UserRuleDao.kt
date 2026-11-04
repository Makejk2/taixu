package top.wkbin.taixu.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UserRuleDao {
    @Query("SELECT COUNT(*) FROM user_rule_call_receipts WHERE budgetOwner=:owner")
    suspend fun consumed(owner: String): Long
    @Query("SELECT EXISTS(SELECT 1 FROM user_rule_call_receipts WHERE budgetOwner=:owner AND callKey=:callKey)")
    suspend fun hasReceipt(owner: String, callKey: String): Boolean
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReceipt(receipt: UserRuleCallReceipt): Long
    @androidx.room.Transaction
    suspend fun reserve(owner: String, callKey: String, maximum: Long?): Boolean {
        if (hasReceipt(owner, callKey)) return true
        if (maximum != null && consumed(owner) >= maximum) return false
        return insertReceipt(UserRuleCallReceipt(owner, callKey)) != -1L
    }
    @androidx.room.Transaction
    suspend fun reserveReviewed(owner: String, callKey: String, maximum: Long?, revisions: Map<String, Long>): Boolean {
        // Every enabled row participates, including currently nonmatching rules which may be edited.
        if (list().filter { it.enabled }.associate { it.id to it.revision } != revisions) return false
        return reserve(owner, callKey, maximum)
    }
    @Query("SELECT * FROM user_management_rules ORDER BY priority DESC, id ASC")
    suspend fun list(): List<UserManagementRule>
    @Query("SELECT * FROM user_management_rules WHERE id = :id")
    suspend fun get(id: String): UserManagementRule?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(rule: UserManagementRule): Long
    @Query("UPDATE user_management_rules SET enabled=:enabled, scope=:scope, scopeId=:scopeId, priority=:priority, content=:content, revision=revision+1 WHERE id=:id AND revision=:expected AND revision < 9223372036854775807")
    suspend fun replace(id: String, expected: Long, enabled: Boolean, scope: String, scopeId: String?, priority: Int, content: String): Int
    @Query("DELETE FROM user_management_rules WHERE id=:id AND revision=:expected")
    suspend fun delete(id: String, expected: Long): Int
}

class RoomUserRuleRepository(private val dao: UserRuleDao) : UserRuleRepository {
    override suspend fun consumed(owner: String) = dao.consumed(owner)
    override suspend fun hasReceipt(owner: String, callKey: String) = dao.hasReceipt(owner, callKey)
    override suspend fun reserveReviewed(owner: String, callKey: String, maximum: Long?, revisions: Map<String, Long>) =
        dao.reserveReviewed(owner, callKey, maximum, revisions)
    override suspend fun reserve(owner: String, callKey: String, maximum: Long?) = dao.reserve(owner, callKey, maximum)
    override suspend fun list() = dao.list()
    override suspend fun get(id: String) = dao.get(id)
    override suspend fun create(rule: UserManagementRule): Boolean {
        require(rule.revision == 1L)
        return dao.insert(rule) != -1L
    }
    override suspend fun replace(rule: UserManagementRule, expectedRevision: Long): Boolean =
        dao.replace(rule.id, expectedRevision, rule.enabled, rule.scope, rule.scopeId, rule.priority, rule.content) == 1
    override suspend fun delete(id: String, expectedRevision: Long) = dao.delete(id, expectedRevision) == 1
}
