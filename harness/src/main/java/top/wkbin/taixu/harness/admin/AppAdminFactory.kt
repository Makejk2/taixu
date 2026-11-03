package top.wkbin.taixu.harness.admin

import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import top.wkbin.taixu.core.database.*
import top.wkbin.taixu.core.database.repository.BotAssignmentRepository
import top.wkbin.taixu.core.database.repository.PersonaBotRepository
import top.wkbin.taixu.core.model.RunMode
import top.wkbin.taixu.core.model.workflow.*
import top.wkbin.taixu.harness.workflow.WorkflowRunManager

/** Production adapter. Lazy runtime callback avoids ToolExecutor -> workflows -> Harness cycle.
 * nativeSourceState must resolve effective model + target/environment using native app services.
 * Default is fail-closed for run, while catalog reads/save/delete/stop remain real operations.
 * It must fingerprint every mutable provider/profile/default/runtime input; never include secrets.
 */
fun createAppAdminBackend(
    workflows: WorkflowRepository,
    sessions: HarnessSessionRepository,
    bots: PersonaBotRepository,
    assignments: BotAssignmentRepository,
    audit: AgentApprovalAuditRepository,
    runManager: () -> WorkflowRunManager,
    nativeSourceState: suspend (sessionId: String) -> AppAdminNativeSourceState = {
        AppAdminNativeSourceState("unresolved", "unresolved", false)
    },
): AppAdminBackend = AppAdminBackend(AppAdminService(object : AppAdminPort {
    override suspend fun source(sessionId: String): AppAdminSource {
        require(!sessionId.startsWith("workflow:") && !sessionId.startsWith("subagent:")) { "工作流派生会话不得管理应用" }
        val session = requireNotNull(sessions.findById(sessionId)) { "原生会话不存在" }
        require(assignments.assignmentForSession(sessionId) == null &&
            bots.getBotBySessionId(sessionId) == null &&
            sessionId !in bots.observeGroupSessionRefs().first()) { "成员/群/派生会话不可管理应用" }
        // Unknown modes fail closed rather than RunMode.fromId's permissive legacy fallback.
        require(session.runMode in RunMode.entries.map { it.id }) { "未知原生运行模式" }
        val native = nativeSourceState(sessionId)
        require(native.modelFingerprint.isNotBlank() && native.environmentFingerprint.isNotBlank()) { "缺少原生来源指纹" }
        val fingerprint = buildJsonObject {
            put("sessionId", session.id); put("workspace", session.workspace)
            put("modelId", session.modelId); put("modelVariant", session.modelVariant)
            put("projectType", session.projectType); put("approvalMode", session.approvalMode)
            put("runMode", session.runMode); put("executionTarget", session.executionTargetJson)
            put("effectiveModel", native.modelFingerprint); put("environment", native.environmentFingerprint)
            put("resolvedModelId", native.resolvedModelId); put("resolvedModelVariant", native.resolvedModelVariant)
            put("localDistributionId", native.localDistributionId); put("supportsRun", native.supportsRun)
        }.toString()
        return AppAdminSource(session.workspace, adminHash(fingerprint), session.runMode == RunMode.BUILD.id,
            appAdminLocalRunSupported(session.workspace, session.executionTargetJson, native),
            native.resolvedModelId, native.resolvedModelVariant)
    }
    override suspend fun definitions() = workflows.observeDefinitions().first()
    override suspend fun definition(id: String) = workflows.findById(id)
    override suspend fun save(definition: WorkflowDefinition, expectedHash: String) {
        AppAdminSafety.definition(definition)
        check(workflows.upsertCustomIfUnchanged(definition, expectedHash)) {
            "工作流定义已变化或原生仓库不支持事务 CAS；请重新提案"
        }
    }
    override suspend fun delete(id: String, expectedHash: String): Boolean {
        require(BuiltinWorkflows.all.none { it.id == id }) { "禁止删除内置工作流" }
        return workflows.deleteCustomIfUnchanged(id, expectedHash)
    }
    override suspend fun validateRun(definition: WorkflowDefinition) {
        appAdminValidateNativeRun(definition)
    }
    override suspend fun status(executionId: String): AppAdminRunStatus? {
        val manager = runManager()
        val handle = manager.handle(executionId)
        val state = handle?.state?.value ?: manager.activeRuns.value[executionId]
        if (state != null) return AppAdminRunStatus(executionId, state.definition.id,
            definitionHash(state.definition), state.status.name,
            handle != null && state.status !in WorkflowRunManager.TERMINAL, state.startedAt, state.finishedAt)
        // Read only log metadata. NEVER decode or return finalContextJson (logs, secrets, review tokens).
        val log = workflows.findExecutionById(executionId) ?: return null
        return AppAdminRunStatus(executionId, log.workflowId, "history-definition-not-exported", log.status,
            false, log.startTime, log.endTime)
    }
    override suspend fun start(definition: WorkflowDefinition, variables: Map<String, String>, workspace: String): AppAdminStart {
        // Final defense: preflight does not confer authority to bypass start-time restrictions.
        appAdminValidateNativeRun(definition)
        val result = runManager().start(definition = definition, variables = variables, workspacePath = workspace)
        return AppAdminStart(result.executionId, result.accepted)
    }
    override suspend fun stop(executionId: String) {
        val manager = runManager()
        val handle = requireNotNull(manager.handle(executionId)) { "活动运行已不存在" }
        require(handle.state.value.status !in WorkflowRunManager.TERMINAL) { "运行已结束" }
        manager.cancel(executionId) // Native manager retains history and cleans managed processes.
    }
}, AppAdminAudit { session, action, detail ->
    audit.record(AgentApprovalAuditEntity(
        id = UUID.randomUUID().toString(), sessionId = session,
        kind = "app_admin_$action", toolName = AppAdminToolSpec.name,
        detail = detail, createdAt = System.currentTimeMillis(),
    ))
}))
