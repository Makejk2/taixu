package top.wkbin.taixu.harness.admin.packages

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import top.wkbin.taixu.core.model.RunMode
import top.wkbin.taixu.harness.*
import top.wkbin.taixu.core.tools.admin.*

suspend fun executePackageManagement(backend: PackageAdminBackend?, call: ToolCall, session: String,
    operation: String?, mode: RunMode?, approved: Boolean, now: Long,
    request: suspend (ApprovalDecision) -> ToolResult): ToolResult {
    fun result(ok: Boolean, value: String) = ToolResult(UUID.randomUUID().toString(),now,call.id,ok,value)
    val service=backend ?: return result(false,"PACKAGE_MANAGEMENT_UNAVAILABLE")
    return try {
        require(operation != null && mode != null)
        val actor=PackageAdminActor(session,operation)
        fun text(key: String) = call.args.getValue(key).jsonPrimitive.also { require(it.isString) }.content
        val module=text("module");val action=text("action")
        require(module in setOf("plugin","process_plugin","web_plugin","skill"))
        if(mode == RunMode.PLAN && action !in setOf("list","status")) return result(false,"PLAN仅允许查询")
        when(action) {
            "list" -> {
                require(call.args.keys == setOf("module","action"))
                result(true,JsonArray(service.list(module).map { row -> buildJsonObject {
                    put("id",row.id);put("module",row.module);put("version",row.version?.let(::JsonPrimitive) ?: JsonNull)
                    put("enabled",row.enabled);put("builtin",row.builtin);put("fingerprint",row.fingerprint)
                } }).toString())
            }
            "status", "cancel" -> {
                require(call.args.keys == setOf("module","action","operationId"))
                if(action=="cancel") {
                    val proposal=service.proposeCancel(actor,text("operationId"))
                    result(true,buildJsonObject { put("proposalId",proposal.id);put("state","pending_native_review") }.toString())
                } else result(true,service.status(actor,text("operationId"))?.let(::operationJson)?.toString() ?: "null")
            }
            "propose" -> {
                require(call.args.keys.all { it in setOf("module","action","operation","id","target","deleteData","reason") })
                require(text("reason").isNotBlank())
                val proposal=service.propose(actor,text("operation"),module,text("id"),text("target"),
                    deleteData=call.args["deleteData"]?.jsonPrimitive?.booleanOrNull ?: false)
                result(true,buildJsonObject { put("proposalId",proposal.id);put("expiresAt",proposal.expiresAt)
                    put("state","pending_native_review") }.toString())
            }
            "apply" -> {
                require(call.args.keys == setOf("module","action","proposalId"))
                val id=text("proposalId")
                if(!approved) {
                    val p=service.prepare(actor,id,call.id)
                    require(p.cancelOperationId != null || p.change.expected.metadata.module == module)
                    request(ApprovalDecision(true,"critical","包操作${p.change.action}：${p.change.expected.metadata.id}，目标${p.change.expected.target}。" +
                        "删除数据=${p.change.deleteData}；新增权限=${p.change.addedPermissions().sorted()}。仅批准本快照，未知部分执行不自动重放。","插件/技能管理"))
                } else result(true,operationJson(service.approveNative(actor,id,call.id)).toString())
            }
            else -> error("PACKAGE_ACTION_UNKNOWN")
        }
    } catch(cancelled: CancellationException) { throw cancelled }
    catch(_: Exception) { result(false,"PACKAGE_MANAGEMENT_REJECTED；请核对状态，不自动重放可能已发生的操作") }
}
private fun operationJson(row: PackageOperation) = buildJsonObject {
    put("operationId",row.id);put("state",row.state);put("durable",row.durable);put("partial",row.partial)
}
