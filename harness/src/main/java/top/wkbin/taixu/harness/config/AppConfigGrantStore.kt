package top.wkbin.taixu.harness.config

import java.util.UUID

/**
 * 一次「AI 可改某项配置」的授权凭证。
 *
 * 刻意做成**有作用域、有期限、可撤销**，而不是一个开关：
 * - [key] 作用域限定到单个配置项，授权改主题不会顺带授权改 SDK 路径；
 * - [expiresAt] 到点自动失效，不需要用户记得关回去；
 * - [sessionId] 绑定会话，换会话/删会话即失效，没有跨会话继承。
 */
internal data class AppConfigGrant(
    val id: String,
    val sessionId: String,
    val key: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val reason: String,
) {
    fun isActive(now: Long): Boolean = now < expiresAt
}

/** 签发结果：成功带凭证，失败带面向用户的原因。 */
internal sealed interface GrantIssueResult {
    data class Issued(val grant: AppConfigGrant) : GrantIssueResult
    data class Rejected(val reason: String) : GrantIssueResult
}

/**
 * 会话级配置授权表。
 *
 * 与 [top.wkbin.taixu.harness.approval.SessionApprovalGrants] 同源思路（纯内存、per-session、
 * 无永久授权），但多两处收紧：
 * 1. **单项作用域**（配置键），不接受「整类免审」；
 * 2. **强制 TTL 上限**，任何调用方都无法签发不过期的令牌。
 *
 * 纯内存意味着进程重启即清空——这是刻意的失败方向：宁可让用户重新授权，
 * 也不让一个持久化的授权在无人察觉时长期生效。
 */
internal class AppConfigGrantStore(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val grants = HashMap<String, LinkedHashMap<String, AppConfigGrant>>()

    /** 单次授权最长有效期：30 分钟。超过的请求会被裁剪到该上限。 */
    private val maxTtlMillis: Long = DEFAULT_MAX_TTL_MILLIS

    /** 每个会话最多同时持有多少条授权，超出按最早到期淘汰。 */
    private val maxGrantsPerSession: Int = DEFAULT_MAX_GRANTS_PER_SESSION

    /**
     * 签发一条授权。[ttlMillis] 会被裁剪到 `[MIN_TTL, MAX_TTL]`。
     * 提权键在这里就被拒绝——即便上层漏了检查，这一层也不会签发。
     */
    fun issue(sessionId: String, key: String, reason: String, ttlMillis: Long): GrantIssueResult {
        if (sessionId.isBlank()) return GrantIssueResult.Rejected("缺少会话标识，无法签发配置授权。")
        val spec = AppConfigCatalog.find(key)
            ?: return GrantIssueResult.Rejected("配置 `" + key + "` 不在白名单内，不能授权。")
        if (spec.selfPrivilege || spec.risk == AppConfigRisk.HIGH || AppConfigCatalog.isPrivilegeKey(spec.key)) {
            return GrantIssueResult.Rejected(
                "`" + spec.key + "` 属于提权项，AI 不可申请授权；请在设置的权限页面手动修改。",
            )
        }
        val now = clock()
        val ttl = ttlMillis.coerceIn(MIN_TTL_MILLIS, maxTtlMillis)
        val grant = AppConfigGrant(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            key = spec.key,
            issuedAt = now,
            expiresAt = now + ttl,
            reason = reason.trim().take(REASON_MAX_CHARS),
        )
        synchronized(grants) {
            val existing = grants.getOrPut(sessionId) { LinkedHashMap() }
            existing[spec.key] = grant
            while (existing.size > maxGrantsPerSession) {
                val oldest = existing.keys.firstOrNull() ?: break
                existing.remove(oldest)
            }
        }
        return GrantIssueResult.Issued(grant)
    }

    /** 该会话是否持有某配置项的有效授权（顺带清理已过期条目）。 */
    fun isActive(sessionId: String, key: String): Boolean = active(sessionId, key) != null

    /** 返回有效授权；已过期的顺手移除，避免长期堆积。 */
    fun active(sessionId: String, key: String): AppConfigGrant? = synchronized(grants) {
        val session = grants[sessionId] ?: return null
        val grant = session[key] ?: return null
        val now = clock()
        if (!grant.isActive(now)) {
            session.remove(key)
            if (session.isEmpty()) grants.remove(sessionId)
            return null
        }
        grant
    }

    /** 撤销单条授权（按配置键）。返回是否真的撤销了有效条目。 */
    fun revoke(sessionId: String, key: String): Boolean = synchronized(grants) {
        val session = grants[sessionId] ?: return false
        val removed = session.remove(key) ?: return false
        if (session.isEmpty()) grants.remove(sessionId)
        removed.isActive(clock())
    }

    /** 撤销该会话的全部授权（会话删除时调用）。 */
    fun revokeSession(sessionId: String) {
        synchronized(grants) { grants.remove(sessionId) }
    }

    /** 列出该会话当前有效授权，供 UI 展示「AI 现在能改什么」。 */
    fun listActive(sessionId: String): List<AppConfigGrant> = synchronized(grants) {
        val session = grants[sessionId] ?: return emptyList()
        val now = clock()
        val expired = session.filterValues { !it.isActive(now) }.keys
        expired.forEach { session.remove(it) }
        if (session.isEmpty()) {
            grants.remove(sessionId)
            return emptyList()
        }
        session.values.sortedBy { it.expiresAt }
    }

    internal companion object {
        const val MIN_TTL_MILLIS: Long = 60_000L
        const val DEFAULT_MAX_TTL_MILLIS: Long = 30 * 60_000L
        /** 默认 TTL：5 分钟。够完成一次对话内调整，又不会长得离谱。 */
        const val DEFAULT_TTL_MILLIS: Long = 5 * 60_000L
        private const val DEFAULT_MAX_GRANTS_PER_SESSION = 8
        private const val REASON_MAX_CHARS = 300
    }
}
