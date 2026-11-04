package top.wkbin.taixu.harness.admin.runtime

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import top.wkbin.taixu.core.model.RunMode
import top.wkbin.taixu.harness.*
import top.wkbin.taixu.harness.admin.model.ModelAdminActor

/** Raw owner errors/outcomes are never returned to the model. */
suspend fun executeRuntimeManagement(components: RuntimeAdminComponents?, call: ToolCall,
    session: String, operation: String?, mode: RunMode?, approved: Boolean, now: Long,
    request: suspend (ApprovalDecision) -> ToolResult): ToolResult {
    fun result(ok: Boolean, text: String) = ToolResult(UUID.randomUUID().toString(), now, call.id, ok, text)
    val c = components ?: return result(false, "RUNTIME_MANAGEMENT_UNAVAILABLE")
    return try {
        require(operation != null && mode != null)
        val actor = ModelAdminActor(session, operation, call.id)
        val action = call.args["action"]?.jsonPrimitive?.content ?: error("missing_action")
        val readonly = action in setOf("list", "health", "updatecheck", "status", "recovery")
        if (mode == RunMode.PLAN && !readonly) return result(false, "PLAN仅允许查询")
        if (action == "apply") {
            require(call.args.keys == setOf("module", "action", "proposalId"))
            val id = call.args.getValue("proposalId").jsonPrimitive.content
            if (!approved) {
                val view = c.gate.prepare(actor, id)
                request(ApprovalDecision(true, "critical", view.impact, "发行版/终端管理（仅此快照）"))
            } else {
                val outcome = c.gate.applyNative(actor, id)
                result(true, buildJsonObject {
                    put("state", if (outcome is String) "accepted" else "applied")
                    if (outcome is String) put("operationId", outcome)
                    put("notice", "受理不等于长任务完成；请按operationId查询，未知部分执行不会自动重放")
                }.toString())
            }
        } else {
            require(!approved)
            val output = c.backend.execute(actor, call.args)
            result(true, when (output) {
                is RuntimeAdminGate.Preview -> buildJsonObject { put("proposalId", output.id); put("module", output.module); put("action", output.action); put("impact", output.impact) }.toString()
                is RuntimeOperation -> buildJsonObject { put("operationId", output.id); put("state", output.state); put("cleanupPending", output.cleanupPending) }.toString()
                null -> "null"
                else -> safeRuntimeMetadata(output).toString()
            })
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { result(false, "RUNTIME_MANAGEMENT_REJECTED；请查询状态，不自动重放可能已执行的操作") }
}

/** Explicit safe DTOs only: never serialize arbitrary owner results or exception text. */
private fun safeRuntimeMetadata(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is top.wkbin.taixu.core.common.result.AppResult.Success<*> -> safeRuntimeMetadata(value.data)
    is top.wkbin.taixu.core.common.result.AppResult.Failure -> buildJsonObject { put("state", "query_failed"); put("code", "OWNER_QUERY_FAILED") }
    is top.wkbin.taixu.runtime.RootfsUpdateInfo -> buildJsonObject {
        put("target", value.distroId); put("hasUpdate", value.hasUpdate)
        put("currentDigest", value.currentDigest?.let(::JsonPrimitive) ?: JsonNull)
        put("latestDigest", value.latestDigest)
    }
    is top.wkbin.taixu.runtime.RuntimeHealth -> buildJsonObject {
        put("healthy", value.isHealthy); put("workspaceWritable", value.workspaceWritable)
        // osRelease/detail are unrestricted guest text, not management metadata.
    }
    is top.wkbin.taixu.core.model.InstalledDistro -> buildJsonObject {
        put("id", value.id); put("active", value.isActive); put("sizeBytes", value.sizeBytes)
    }
    is TerminalAdminAdapter.Tab -> buildJsonObject {
        put("id", value.id); put("label", value.label.take(120)); put("distribution", value.distribution)
        put("alive", value.alive); put("foreground", value.foreground)
    }
    is top.wkbin.taixu.runtime.terminal.TerminalRestoreFailure -> buildJsonObject {
        put("id", value.id); put("label", value.label.take(120)); put("state", "restore_failed")
    }
    is List<*> -> JsonArray(value.map(::safeRuntimeMetadata))
    else -> buildJsonObject { put("state", "metadata_unavailable") }
}
