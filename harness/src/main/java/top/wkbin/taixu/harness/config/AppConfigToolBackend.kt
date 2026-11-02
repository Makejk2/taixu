package top.wkbin.taixu.harness.config

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Public adapter keeps the configuration service internal and accepts no userConfirmed field. */
class AppConfigToolBackend internal constructor(private val service: AppConfigService) {
    suspend fun audit(session: String, action: String, detail: String) = service.record(session, action, detail)
    suspend fun approval(session: String, args: JsonObject): Pair<Boolean, String> =
        service.approval(session, args["changeId"]?.jsonPrimitive?.content.orEmpty())
    suspend fun execute(session: String, args: JsonObject, approvedNative: Boolean = false): Pair<Boolean, String> {
        fun arg(key: String) = args[key]?.jsonPrimitive?.content.orEmpty()
        return try {
            require(session.isNotBlank()) { "配置工具必须绑定原生会话。" }
            val action = arg("action")
            require("userConfirmed" !in args && "approvedNative" !in args) { "模型参数不能作为用户批准证据。" }
            val result = when (action) {
                "list" -> service.list()
                "get" -> service.get(arg("key"))
                "propose" -> service.propose(session, arg("key"), arg("value"), arg("reason"),
                    arg("ttl_seconds").toLongOrNull()?.coerceIn(60, 1800)?.times(1000) ?: AppConfigGrantStore.DEFAULT_TTL_MILLIS)
                "apply" -> {
                    require(args.keys.all { it in setOf("action", "changeId") }) { "apply 只接受不可变提案 changeId。" }
                    // apply owns intent/completion auditing; never append a fallible generic audit
                    // that would turn an already committed write into a misleading rejection.
                    val applied = service.apply(session, arg("changeId"), approvedNative)
                    return applied.auditComplete to applied.output
                }
                "rollback" -> {
                    require(args.keys.all { it in setOf("action", "changeId", "reason") }) { "回滚不能指定恢复值。" }
                    service.proposeRollback(session, arg("changeId"), arg("reason"))
                }
                "grants" -> service.activeGrants(session)
                "revoke" -> service.revoke(session, arg("key").ifEmpty { null })
                else -> error("未知 app_config action。")
            }
            service.record(session, action, result)
            true to result
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            try { service.record(session, "rejected", e.message.orEmpty()) }
            catch (auditFailure: Exception) { if (auditFailure is CancellationException) throw auditFailure }
            false to (e.message ?: "配置操作失败。")
        }
    }
}
