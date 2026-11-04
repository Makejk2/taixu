package top.wkbin.taixu.core.database

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** No FK: deleted IDs remain tombstoned, so delayed REPLACE/upsert cannot resurrect them. */
@Entity(tableName = "session_management_fences")
data class SessionManagementFenceEntity(
    @PrimaryKey val sessionId: String,
    val revision: Long = 0,
    val ownerToken: String? = null,
    // 0=open, 1=reserved, 2=transaction-local rename, 3=transaction-local delete, 4=deleted, 5=delete cleanup
    val mode: Int = 0,
)

@Entity(tableName = "session_management_claims")
data class SessionManagementClaimEntity(@PrimaryKey val sessionId: String, val claimedAt: Long)

data class SessionManagementSnapshot(val session: HarnessSessionEntity, val revision: Long)

/** Expose this repository contract to Harness; do not inject a Room DAO there. */
interface SessionManagementPersistence {
    suspend fun snapshot(id: String): SessionManagementSnapshot?
    suspend fun acquire(id: String, revision: Long, token: String): Boolean
    suspend fun release(id: String, token: String)
    suspend fun rename(id: String, token: String, title: String, now: Long): Boolean
    suspend fun claimNative(id: String, revision: Long): Boolean = error("SESSION_NATIVE_RECOVERY_UNAVAILABLE")
    suspend fun explicitlyClaimed(id: String): Boolean = error("SESSION_NATIVE_RECOVERY_UNAVAILABLE")
    suspend fun pendingCleanupIds(): List<String> = error("SESSION_NATIVE_RECOVERY_UNAVAILABLE")
    suspend fun isOpen(id: String): Boolean = error("SESSION_NATIVE_RECOVERY_UNAVAILABLE")
    suspend fun <T> recoverCleanup(id: String, block: suspend () -> T): T = error("SESSION_NATIVE_RECOVERY_UNAVAILABLE")
    suspend fun <T> withDeleteLease(id: String, token: String, block: suspend () -> T): T
}

/** Native-only coroutine authority. Never populated from tool JSON. */
internal class SessionDeletionLease(val id: String, val token: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SessionDeletionLease>
}

@Dao
interface SessionManagementDao {
    // INSERT ... SELECT may insert no row; last_insert_rowid is not a success flag.
    @Query("SELECT changes()")
    suspend fun claimInsertChanges(): Int

    @Query("INSERT OR IGNORE INTO session_management_claims(sessionId,claimedAt) SELECT id,:now FROM harness_sessions WHERE id=:id AND EXISTS(SELECT 1 FROM session_management_fences WHERE sessionId=:id AND mode=0)")
    suspend fun registerNativeUserSessionInsert(id: String, now: Long)
    @Transaction
    suspend fun registerNativeUserSession(id: String, now: Long): Int {
        registerNativeUserSessionInsert(id, now)
        return claimInsertChanges()
    }
    @Query("SELECT EXISTS(SELECT 1 FROM session_management_claims WHERE sessionId=:id)")
    suspend fun isExplicitlyClaimed(id: String): Boolean
    @Query("INSERT OR IGNORE INTO session_management_claims(sessionId,claimedAt) SELECT id,:now FROM harness_sessions WHERE id=:id AND EXISTS(SELECT 1 FROM session_management_fences WHERE sessionId=:id AND revision=:revision AND mode=0) AND id NOT LIKE 'workflow:%' AND id NOT LIKE 'subagent:%' AND NOT EXISTS(SELECT 1 FROM persona_bots WHERE sessionId=:id) AND NOT EXISTS(SELECT 1 FROM bot_assignments WHERE sessionId=:id) AND NOT EXISTS(SELECT 1 FROM bot_group_sessions WHERE sessionId=:id)")
    suspend fun claimFromNativeInsert(id: String, revision: Long, now: Long)
    @Transaction
    suspend fun claimFromNative(id: String, revision: Long, now: Long): Int {
        claimFromNativeInsert(id, revision, now)
        return claimInsertChanges()
    }
    @Query("SELECT * FROM session_management_fences WHERE mode=5 ORDER BY sessionId")
    suspend fun pendingCleanup(): List<SessionManagementFenceEntity>
    @Query("UPDATE session_management_fences SET ownerToken=:newToken WHERE sessionId=:id AND ownerToken=:oldToken AND mode=5")
    suspend fun adoptCleanupFromNative(id: String, oldToken: String, newToken: String): Int

    @Query("SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'session_fence_%'")
    suspend fun triggerCount(): Int
    @Query("SELECT * FROM harness_sessions WHERE id=:id")
    suspend fun managementSession(id: String): HarnessSessionEntity?
    @Query("SELECT * FROM session_management_fences WHERE sessionId=:id")
    suspend fun managementFence(id: String): SessionManagementFenceEntity?
    @Transaction
    suspend fun managementSnapshot(id: String): SessionManagementSnapshot? {
        check(triggerCount() == SessionManagementSchema.triggerCount) { "SESSION_FENCE_SCHEMA_MISSING" }
        val session = managementSession(id) ?: return null
        val fence = managementFence(id) ?: error("SESSION_FENCE_ROW_MISSING")
        return SessionManagementSnapshot(session, fence.revision)
    }
    @Query("UPDATE session_management_fences SET ownerToken=:token, mode=1 WHERE sessionId=:id AND revision=:revision AND mode=0")
    suspend fun reserveManagement(id: String, revision: Long, token: String): Int
    @Query("UPDATE session_management_fences SET ownerToken=NULL, mode=0 WHERE sessionId=:id AND ownerToken=:token AND mode=1")
    suspend fun releaseManagement(id: String, token: String)
    @Query("UPDATE session_management_fences SET mode=:mode WHERE sessionId=:id AND ownerToken=:token AND ((:mode=2 AND mode=1) OR (:mode=3 AND mode IN (1,5)))")
    suspend fun authorizeManagement(id: String, token: String, mode: Int): Int
    @Query("UPDATE session_management_fences SET mode=5 WHERE sessionId=:id AND ownerToken=:token AND mode=1")
    suspend fun beginManagementDelete(id: String, token: String): Int
    @Query("UPDATE session_management_fences SET mode=1 WHERE sessionId=:id AND ownerToken=:token AND mode=2")
    suspend fun endManagementRename(id: String, token: String)
    @Query("UPDATE harness_sessions SET title=:title, updatedAt=:now WHERE id=:id")
    suspend fun renameManagementRow(id: String, title: String, now: Long): Int
    @Transaction
    suspend fun renameManagement(id: String, token: String, title: String, now: Long): Boolean {
        if (authorizeManagement(id, token, 2) != 1) return false
        val changed = renameManagementRow(id, title, now) == 1
        endManagementRename(id, token)
        return changed
    }
}

class RoomSessionManagementPersistence(private val dao: SessionManagementDao) : SessionManagementPersistence {
    override suspend fun claimNative(id: String, revision: Long) = dao.claimFromNative(id,revision,System.currentTimeMillis()) == 1
    override suspend fun explicitlyClaimed(id: String) = dao.isExplicitlyClaimed(id)
    override suspend fun pendingCleanupIds() = dao.pendingCleanup().map { it.sessionId }
    override suspend fun isOpen(id: String) = dao.managementFence(id)?.mode == 0
    override suspend fun <T> recoverCleanup(id: String, block: suspend () -> T): T {
        val fence = requireNotNull(dao.managementFence(id))
        check(fence.mode == 5)
        val token = java.util.UUID.randomUUID().toString()
        check(dao.adoptCleanupFromNative(id,requireNotNull(fence.ownerToken),token) == 1)
        return resumeCleanupFromNative(id,token,block)
    }

    /** Native recovery only; caller holds runtime reservation and adopted the durable mode-5 token. */
    suspend fun <T> resumeCleanupFromNative(id: String, token: String, block: suspend () -> T): T {
        val fence = requireNotNull(dao.managementFence(id))
        check(fence.mode == 5 && fence.ownerToken == token) { "SESSION_RECOVERY_CONFLICT" }
        return withContext(SessionDeletionLease(id, token)) { block() }
    }

    override suspend fun snapshot(id: String) = dao.managementSnapshot(id)
    override suspend fun acquire(id: String, revision: Long, token: String): Boolean {
        check(dao.triggerCount() == SessionManagementSchema.triggerCount) { "SESSION_FENCE_SCHEMA_MISSING" }
        return dao.reserveManagement(id, revision, token) == 1
    }
    override suspend fun release(id: String, token: String) = withContext(NonCancellable) {
        dao.releaseManagement(id, token)
    }
    override suspend fun rename(id: String, token: String, title: String, now: Long) =
        dao.renameManagement(id, token, title, now)
    override suspend fun <T> withDeleteLease(id: String, token: String, block: suspend () -> T): T {
        check(dao.beginManagementDelete(id, token) == 1) { "SESSION_RESERVED" }
        return withContext(SessionDeletionLease(id, token)) { block() }
    }
}

/** Install in the schema migration AND Room onCreate callback. No process-open unlock: interrupted
 * reservations are fail-closed until native recovery reconciles cleanup. All trigger authorization
 * windows are inside a Room transaction; another connection cannot write while mode 2/3 is visible.
 * Register SessionManagementFenceEntity and SessionManagementDao in AppDatabase (version owned by app).
 */
object SessionManagementSchema {
    val statements: List<String> = buildList {
        add("CREATE TABLE IF NOT EXISTS session_management_claims (sessionId TEXT NOT NULL PRIMARY KEY, claimedAt INTEGER NOT NULL)")
        add("CREATE TABLE IF NOT EXISTS session_management_fences (sessionId TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, ownerToken TEXT, mode INTEGER NOT NULL)")
        add("INSERT OR IGNORE INTO session_management_fences SELECT id,0,NULL,0 FROM harness_sessions")
        fun trigger(name: String, timing: String, event: String, table: String, body: String) {
            add("CREATE TRIGGER IF NOT EXISTS session_fence_$name $timing $event ON $table BEGIN $body END")
        }
        fun blocked(ref: String, allowed: String = "0") =
            "EXISTS(SELECT 1 FROM session_management_fences WHERE sessionId=$ref AND mode NOT IN ($allowed))"
        fun reject(condition: String) = "SELECT CASE WHEN $condition THEN RAISE(ABORT,'SESSION_RESERVED') END;"
        fun bump(ref: String) = "UPDATE session_management_fences SET revision=revision+1 WHERE sessionId=$ref;"
        trigger("session_insert_guard", "BEFORE", "INSERT", "harness_sessions", reject(blocked("NEW.id")))
        trigger("session_update_guard", "BEFORE", "UPDATE", "harness_sessions",
            reject(blocked("OLD.id", "0,2")) + reject("NEW.id != OLD.id"))
        trigger("session_delete_guard", "BEFORE", "DELETE", "harness_sessions", reject(blocked("OLD.id", "0,3")))
        trigger("session_insert_revision", "AFTER", "INSERT", "harness_sessions",
            "INSERT OR IGNORE INTO session_management_fences VALUES(NEW.id,0,NULL,0);" + bump("NEW.id"))
        trigger("session_update_revision", "AFTER", "UPDATE", "harness_sessions", bump("NEW.id"))
        // Even native direct deletes retain the tombstone. REPLACE is guarded BEFORE INSERT.
        trigger("session_delete_revision", "AFTER", "DELETE", "harness_sessions",
            "UPDATE session_management_fences SET revision=revision+1,mode=4,ownerToken=NULL WHERE sessionId=OLD.id;")
        for (table in listOf("persona_bots", "bot_assignments", "bot_group_sessions")) {
            val key = if (table == "bot_group_sessions") "existing.botId=NEW.botId AND existing.groupId=NEW.groupId"
                else "existing.id=NEW.id"
            val replacedSession = "(SELECT existing.sessionId FROM $table existing WHERE $key)"
            trigger("${table}_insert", "BEFORE", "INSERT", table,
                reject(blocked("NEW.sessionId")) + reject(blocked(replacedSession)) +
                    bump(replacedSession) + bump("NEW.sessionId"))
            trigger("${table}_update", "BEFORE", "UPDATE", table,
                reject(blocked("OLD.sessionId")) + reject(blocked("NEW.sessionId")) + bump("OLD.sessionId") + bump("NEW.sessionId"))
            trigger("${table}_delete", "BEFORE", "DELETE", table, reject(blocked("OLD.sessionId", "0,3")) + bump("OLD.sessionId"))
        }
        // Running tasks may drain their final entries while reserved. Their writes still advance
        // revision, invalidating proposals prepared before the reservation. Tombstones reject late work.
        for (table in listOf("harness_entries", "harness_lanes")) {
            for ((event, ref) in listOf("INSERT" to "NEW", "UPDATE" to "NEW", "DELETE" to "OLD")) {
                trigger("${table}_${event.lowercase()}", "BEFORE", event, table,
                    (if (event == "DELETE") "" else reject(blocked("$ref.sessionId", "0,1,2,3,5"))) +
                        (if (event == "UPDATE") reject("OLD.sessionId != NEW.sessionId") else "") + bump("$ref.sessionId"))
            }
        }
    }
    val triggerCount: Int get() = statements.count { it.startsWith("CREATE TRIGGER") }
    fun install(db: SupportSQLiteDatabase) { statements.forEach { db.execSQL(it) } }
}
