package top.wkbin.taixu.harness.admin

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

class AppAdminBackend internal constructor(internal val service: AppAdminService) {
    suspend fun disableManagement() = service.disableManagement()
    /** Queries/proposals only. There is intentionally no public native approval or decide API. */
    suspend fun execute(sessionId: String, args: JsonObject): Pair<Boolean, String> = try {
        true to service.execute(sessionId, args)
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { false to "app_admin 未完成：参数、身份或定义不可验证；未通过原生变更门。请核对原生界面。" }
}

object AppAdminToolSpec {
    const val name = "app_admin"
    const val description = "原生应用管理。workflow: list/get/status 只读脱敏摘要；propose 创建不可变提案；apply 逐次原生审批。FULL_ACCESS 不豁免，PLAN 只读。运行不自动审批节点。"
    val schema = """{"type":"object","additionalProperties":false,"properties":{
      "module":{"type":"string","enum":["workflow"]},
      "action":{"type":"string","enum":["list","get","status","propose","apply"]},
      "workflowId":{"type":"string"},"executionId":{"type":"string"},
      "proposalId":{"type":"string"},"reason":{"type":"string","maxLength":1000},
      "operation":{"type":"object","description":"Exact variants: {operation:save,definition:<existing WorkflowDefinition JSON schema>}; {operation:delete,workflowId}; {operation:run,workflowId,workspace,variables:<string map, explicit even if empty>}; {operation:stop,executionId}. No additional fields. Builtin save/delete, credentials, plugin/expansion nodes are refused. Variables/config/log values are not returned."}
    },"required":["module","action"]}"""
}
