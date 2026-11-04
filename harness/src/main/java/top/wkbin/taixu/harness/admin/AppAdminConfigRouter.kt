package top.wkbin.taixu.harness.admin

import kotlinx.serialization.json.*
import top.wkbin.taixu.core.model.RunMode
import top.wkbin.taixu.harness.*
import top.wkbin.taixu.harness.config.*
import top.wkbin.taixu.harness.team.*
import top.wkbin.taixu.harness.admin.model.*

/** One native management boundary; JSON can select a module, never provide approval.
 * Modules are explicit; an unknown module can never leak into generic config logging. */
internal suspend fun routeAdminConfiguration(
    config: AppConfigToolBackend?, admin: AppAdminBackend?, policy: ApprovalPolicyEngine,
    mode: suspend (String) -> RunMode?, call: ToolCall, session: String,
    approved: Boolean, now: Long, model: ModelAdminBackend?, operationId: String?, sessions: SessionManagementBackend?, rules: top.wkbin.taixu.harness.admin.rules.UserRulesBackend?, runtime: top.wkbin.taixu.harness.admin.runtime.RuntimeAdminComponents?, packages: top.wkbin.taixu.harness.admin.packages.PackageAdminBackend?, nativeModelForms: ModelNativeFormBridge?,
    request: suspend (ApprovalDecision) -> ToolResult,
): ToolResult = when (val module = (call.args["module"] as? JsonPrimitive)?.content) {
    "plugin", "process_plugin", "web_plugin", "skill" -> top.wkbin.taixu.harness.admin.packages.executePackageManagement(
        packages, call, session, operationId, mode(session), approved, now, request)
    "distro", "terminal" -> top.wkbin.taixu.harness.admin.runtime.executeRuntimeManagement(
        runtime, call, session, operationId, mode(session), approved, now, request)
    "workflow" -> {
        // HEAD 的已验证行为：工作流管理必须有本次可信原生调用身份，不能借父会话身份。
        val actor = kotlinx.coroutines.currentCoroutineContext()[NativeManagementActor]
        require(actor?.sessionId == session && actor.operationId == operationId && actor.callId == call.id) {
            "NATIVE_MANAGEMENT_ACTOR_REQUIRED"
        }
        executeAppAdmin(admin, mode, call, session, approved, now, request)
    }
    "rules" -> if (operationId != null) top.wkbin.taixu.harness.admin.rules.executeUserRules(rules,
        top.wkbin.taixu.harness.admin.rules.RuleManagementActor(session, operationId), call, mode(session), approved, now, request)
        else ToolResult(java.util.UUID.randomUUID().toString(), now, call.id, false, "可信原生operation缺失；未执行")
    "session" -> executeSessionManagement(sessions, mode, call, session, approved, now, request)
    "model" -> if ((call.args["action"] as? JsonPrimitive)?.content in setOf("native_form", "form_status"))
        executeNativeModelForm(nativeModelForms, call, session, now)
        else if (operationId != null) executeBoundModelManagement(model,
            ModelAdminActor(session, operationId, call.id), call, approved, now, request)
        else ToolResult(java.util.UUID.randomUUID().toString(), now, call.id, false, "可信原生operation缺失；未执行")
    null -> executeAppConfig(config, policy, mode, call, session, approved, now, request)
    else -> ToolResult(java.util.UUID.randomUUID().toString(), now, call.id, false,
        "管理模块尚在装配或不存在：$module；未转发普通配置、未执行")
}
