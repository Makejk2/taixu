package top.wkbin.taixu.harness.directory

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Delegate only app_config(action=modules|inspect_module) here. No new HarnessTool enum/schema.
 * The caller must extend the existing app_config action contract and route BEFORE config mutation
 * validation/approval. Existing session/visibility/authorization gates remain the caller's job.
 */
class AppModulesToolBackend(private val directory: AppModuleDirectory) {
    fun handles(action: String): Boolean = action == "modules" || action == "inspect_module"

    suspend fun execute(args: JsonObject): Pair<Boolean, String> {
        val action = (args["action"] as? JsonPrimitive)?.contentOrNull
        if (action == null || !handles(action)) return failure("unsupported_action")
        val allowed = if (action == "modules") setOf("action") else setOf("action", "module")
        if (args.keys.any { it !in allowed }) return failure("unsupported_argument")
        val module = (args["module"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (action == "inspect_module" && module.isNullOrBlank()) return failure("module_required")
        return try {
            val output = if (action == "modules") directory.modules() else directory.inspectModule(module!!)
            true to output.toString()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            failure("unknown_module")
        }
    }

    private fun failure(code: String): Pair<Boolean, String> = false to buildJsonObject {
        put("error", code)
        put("readOnly", true)
    }.toString()
}

/** Native-only adapter, with no configuration writes, discovery or navigation side effects. */
internal suspend fun executeAppModules(
    sessionId: String, call: top.wkbin.taixu.harness.ToolCall, now: Long,
    validSession: suspend (String) -> Boolean,
    factory: (suspend (String) -> AppModulesToolBackend)?,
): top.wkbin.taixu.harness.ToolResult {
    fun result(ok: Boolean, text: String) = top.wkbin.taixu.harness.ToolResult(
        id = java.util.UUID.randomUUID().toString(), createdAt = now, toolCallId = call.id,
        success = ok, output = text)
    if (sessionId.isBlank() || !validSession(sessionId)) return result(false, "模块目录必须绑定有效原生会话。")
    return try {
        val backend = factory?.invoke(sessionId) ?: return result(false, "模块目录未绑定。")
        val response = backend.execute(call.args)
        result(response.first, response.second)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { result(false, "模块目录当前不可用，未执行发现或修改操作。") }
}
