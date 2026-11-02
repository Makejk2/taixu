package top.wkbin.taixu.harness.config

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface AppConfigPort {
    suspend fun read(key: String): String
    /** Atomic compare-and-set in the same DataStore transaction as the write. */
    suspend fun compareAndSet(key: String, expected: String, value: String): Boolean
}
internal data class AppConfigAuditEntry(
    val sessionId: String, val action: String, val detail: String, val createdAt: Long,
)
internal fun interface AppConfigAuditSink { suspend fun record(entry: AppConfigAuditEntry) }
internal data class AppConfigChange(
    val id: String, val sessionId: String, val key: String, val oldValue: String, val newValue: String,
    val reason: String, val expiresAt: Long, val ttlMillis: Long, val rollbackOf: String? = null,
) {
    fun describe(spec: AppConfigSpec) = "${spec.title} (${spec.key})\n原因：$reason\n影响面：${spec.impact}\n旧值：$oldValue\n新值：$newValue\nchangeId：$id\n授权：本会话 / 本键，TTL=${ttlMillis / 1000}秒；高风险每次审批。"
}

internal data class AppConfigApplyResult(val auditComplete: Boolean, val output: String)

/** Process-local, session-bound proposals, grants and verified rollback snapshots; restart fails closed. */
internal class AppConfigService(
    private val port: AppConfigPort,
    private val grants: AppConfigGrantStore,
    private val audit: AppConfigAuditSink,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val pending = LinkedHashMap<String, AppConfigChange>()
    private val applied = LinkedHashMap<String, AppConfigChange>()

    suspend fun record(session: String, action: String, detail: String) =
        audit.record(AppConfigAuditEntry(session, action, detail, clock()))

    suspend fun list(): String = AppConfigCatalog.All.map { spec ->
        if (spec.selfPrivilege) "${spec.key}: 仅可提议，用户手动设置；${spec.impact}"
        else "${spec.key} = ${port.read(spec.key)}；${spec.impact}"
    }.joinToString("\n")
    suspend fun get(key: String): String {
        val spec = requireNotNull(AppConfigCatalog.find(key)) { "未知配置键。" }
        return if (spec.selfPrivilege) "${spec.key}: 用户手动设置，模型不可写。${spec.impact}" else "${spec.key} = ${port.read(key)}"
    }
    suspend fun propose(session: String, key: String, value: String, reason: String, ttl: Long): String = lock.withLock {
        require(session.isNotBlank()) { "缺少会话。" }
        require(reason.isNotBlank() && reason.length <= 500) { "必须提供 1–500 字的修改原因。" }
        if (AppConfigCatalog.isPrivilegeKey(key)) return@withLock "权限变更提议：$key → ${value.take(100)}\n原因：$reason\n必须由用户手动前往设置；未授权、未写入。"
        val spec = requireNotNull(AppConfigCatalog.find(key)) { "未知配置键。" }
        require(spec.validate(value) == null) { spec.validate(value).orEmpty() }
        val canonical = when (key) {
            "appearance.font_scale" -> value.toFloat().toString()
            "terminal.font_size" -> value.toInt().toString()
            else -> value
        }
        val change = AppConfigChange(UUID.randomUUID().toString(), session, key, port.read(key), canonical,
            reason, clock() + PROPOSAL_TTL, ttl.coerceIn(AppConfigGrantStore.MIN_TTL_MILLIS, AppConfigGrantStore.DEFAULT_MAX_TTL_MILLIS))
        remember(pending, change)
        change.describe(spec) + "\n尚未应用。使用 apply(changeId) 请求原生审批。"
    }
    suspend fun proposeRollback(session: String, changeId: String, reason: String): String = lock.withLock {
        val previous = requireNotNull(applied[changeId]?.takeIf { it.sessionId == session }) { "找不到本会话已验证的成功快照。" }
        require(reason.isNotBlank() && reason.length <= 500) { "回滚必须提供 1–500 字的原因。" }
        require(port.read(previous.key) == previous.newValue) { "配置已改变，拒绝覆盖用户或其他变更。" }
        val change = previous.copy(id = UUID.randomUUID().toString(), oldValue = previous.newValue,
            newValue = previous.oldValue, reason = reason, expiresAt = clock() + PROPOSAL_TTL, rollbackOf = previous.id)
        remember(pending, change)
        change.describe(requireNotNull(AppConfigCatalog.find(change.key))) + "\n回滚提案已创建，使用 apply(changeId) 审批；恢复值来自成功快照。"
    }
    suspend fun approval(session: String, id: String): Pair<Boolean, String> = lock.withLock {
        val c = pendingChange(session, id)
        val spec = requireNotNull(AppConfigCatalog.find(c.key))
        (spec.risk == AppConfigRisk.HIGH || !grants.isActive(session, c.key)) to c.describe(spec)
    }
    /** approvedNative is supplied ONLY by ToolExecutor's trusted native approval replay, never JSON. */
    suspend fun apply(session: String, id: String, approvedNative: Boolean): AppConfigApplyResult = lock.withLock {
        val c = pendingChange(session, id)
        val spec = requireNotNull(AppConfigCatalog.find(c.key))
        check(!spec.selfPrivilege && !AppConfigCatalog.isPrivilegeKey(c.key))
        check(approvedNative || (spec.risk == AppConfigRisk.LOW && grants.isActive(session, c.key))) { "需要可信原生用户审批。" }
        // No mutation without a durable intent. Cancellation before commit remains a zero-write path.
        record(session, "write_intent", c.describe(spec))
        currentCoroutineContext().ensureActive()
        // DataStore may commit before resuming its caller: shield the commit/bookkeeping boundary so
        // cancellation cannot lose the verified rollback snapshot. Cancellation still propagates.
        withContext(NonCancellable) {
            check(port.compareAndSet(c.key, c.oldValue, c.newValue)) { "旧值快照已失效；未写入，请重新提议。" }
            pending.remove(id)
            remember(applied, c)
            try {
                record(session, "write_success", c.describe(spec))
            } catch (e: Exception) {
                grants.revoke(session, c.key)
                if (e is CancellationException) throw e
                return@withContext AppConfigApplyResult(false,
                    "配置已修改，但完成审计失败；不要自动重试。changeId=${c.id}；${c.key}: ${c.oldValue} → ${c.newValue}。" +
                        "快照已保留，可用 rollback(changeId) 生成恢复提案；本键宽限授权已撤销，未签发新授权。")
            }
            if (approvedNative && spec.risk == AppConfigRisk.LOW) {
                check(grants.issue(session, c.key, c.reason, c.ttlMillis) is GrantIssueResult.Issued)
            }
            AppConfigApplyResult(true,
                "已应用并验证快照 changeId=${c.id}；${c.key}: ${c.oldValue} → ${c.newValue}。可用 rollback(changeId) 生成恢复提案。")
        }
    }
    fun activeGrants(session: String) = grants.listActive(session).joinToString("\n") { "${it.key}: ${it.id}, expiresAt=${it.expiresAt}" }.ifEmpty { "无有效授权。" }
    fun revoke(session: String, key: String?): String {
        if (key == null) grants.revokeSession(session) else grants.revoke(session, key)
        return "配置授权已撤销；不更改当前配置值。"
    }
    private fun pendingChange(session: String, id: String): AppConfigChange {
        require(session.isNotBlank()) { "缺少会话。" }
        return requireNotNull(pending[id]?.takeIf { it.sessionId == session && clock() < it.expiresAt }) { "提案不存在、已过期或不属于本会话；请重新提议。" }
    }
    private fun remember(map: LinkedHashMap<String, AppConfigChange>, c: AppConfigChange) {
        map[c.id] = c
        while (map.size > 256) map.remove(map.keys.first())
    }
    private companion object { const val PROPOSAL_TTL = 10 * 60_000L }
}
