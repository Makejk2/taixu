package top.wkbin.taixu.harness.admin

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Public surface deliberately exposes no apply/approve method. Native replay uses the gate. */
class SessionManagementBackend internal constructor(internal val service: SessionManagementService) {
    internal val proposals = SessionManagementProposals(service)
    suspend fun execute(sessionId: String, args: JsonObject): Pair<Boolean, String> = try {
        require(sessionText(args, "module") == "session")
        require(service.get(sessionId) != null) { "SOURCE_NOT_USER_SESSION" }
        true to when (sessionText(args, "action")) {
            "list" -> {
                sessionKeys(args, setOf("module", "action", "offset", "limit"))
                val offset = args["offset"]?.let { require(it is JsonPrimitive && !it.isString); requireNotNull(it.intOrNull) } ?: 0
                val limit = args["limit"]?.let { require(it is JsonPrimitive && !it.isString); requireNotNull(it.intOrNull) } ?: 50
                JsonArray(service.list(offset, limit).map(::sessionMetadata)).toString()
            }
            "get" -> {
                sessionKeys(args, setOf("module", "action", "sessionId"))
                service.get(sessionText(args, "sessionId"))?.let(::sessionMetadata)?.toString() ?: "null"
            }
            "propose" -> {
                sessionKeys(args, setOf("module", "action", "operation"))
                val op = args["operation"] as? JsonObject ?: error("OPERATION_REQUIRED")
                val command = when (sessionText(op, "operation")) {
                    "create" -> { sessionKeys(op, setOf("operation", "title")); SessionCommand.Create(sessionText(op, "title")) }
                    "rename" -> { sessionKeys(op, setOf("operation", "sessionId", "title")); SessionCommand.Rename(sessionText(op, "sessionId"), sessionText(op, "title")) }
                    "delete" -> { sessionKeys(op, setOf("operation", "sessionId")); SessionCommand.Delete(sessionText(op, "sessionId")) }
                    else -> error("UNSUPPORTED")
                }
                proposals.propose(sessionId, command)
            }
            else -> error("NATIVE_GATE_REQUIRED")
        }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { false to "session 未完成：身份、参数或快照不可验证；未执行或可能部分执行，请读取元数据核对，禁止自动重放。" }
    suspend fun invalidateSession(sessionId: String) = proposals.invalidate(sessionId)
}

internal fun sessionKeys(args: JsonObject, allowed: Set<String>) { require(args.keys.all { it in allowed }) { "UNKNOWN_FIELD" } }
internal fun sessionText(args: JsonObject, key: String): String {
    val p = args[key] as? JsonPrimitive ?: error("STRING_REQUIRED")
    require(p.isString && p.content.isNotBlank()); return p.content
}
internal fun sessionMetadata(s: ManagedSession): JsonObject = buildJsonObject {
    put("id", s.id); put("title", s.title); put("createdAt", s.createdAt); put("updatedAt", s.updatedAt)
    put("revision", s.revision); put("running", s.running)
}
