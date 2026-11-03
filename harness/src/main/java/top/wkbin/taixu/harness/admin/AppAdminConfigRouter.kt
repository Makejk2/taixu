package top.wkbin.taixu.harness.admin

import kotlinx.serialization.json.*
import top.wkbin.taixu.core.model.RunMode
import top.wkbin.taixu.harness.*
import top.wkbin.taixu.harness.config.*
import top.wkbin.taixu.harness.team.*

/** One native management boundary; JSON can select a module, never provide approval. */
internal suspend fun routeAdminConfiguration(
    config: AppConfigToolBackend?, admin: AppAdminBackend?, policy: ApprovalPolicyEngine,
    mode: suspend (String) -> RunMode?, call: ToolCall, session: String,
    approved: Boolean, now: Long, request: suspend (ApprovalDecision) -> ToolResult,
): ToolResult = if ((call.args["module"] as? JsonPrimitive)?.content == "workflow")
    executeAppAdmin(admin, mode, call, session, approved, now, request)
else executeAppConfig(config, policy, mode, call, session, approved, now, request)
