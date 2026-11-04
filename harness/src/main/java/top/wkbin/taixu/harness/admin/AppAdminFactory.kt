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
    // backup 新增：插件目录参与来源指纹与插件节点内容身份冻结；缺省 null 时相关节点 fail closed。
    plugins: (() -> top.wkbin.taixu.harness.plugins.HarnessPluginRegistry)? = null,
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
        val pluginRegistry = plugins?.invoke()
        val fingerprint = buildJsonObject {
            put("sessionId", session.id); put("workspace", session.workspace)
            put("modelId", session.modelId); put("modelVariant", session.modelVariant)
            put("projectType", session.projectType); put("approvalMode", session.approvalMode)
            put("runMode", session.runMode); put("executionTarget", session.executionTargetJson)
            put("sourceModelFingerprint", native.executionSource?.modelBinding?.fingerprint)
            put("pluginCatalog", pluginRegistry?.descriptors?.sortedBy { it.id }?.joinToString { descriptor ->
                descriptor.id + ":" + descriptor.version + ":" + descriptor.extensions
                    .filter { it.point == top.wkbin.taixu.plugin.api.PluginExtensionPoint.WORKFLOW }
                    .sortedBy { it.id }.joinToString { ext -> pluginRegistry.nodeIdentity(descriptor.id, ext.id).orEmpty() }
            })
            put("effectiveModel", native.modelFingerprint); put("environment", native.environmentFingerprint)
            put("resolvedModelId", native.resolvedModelId); put("resolvedModelVariant", native.resolvedModelVariant)
            put("localDistributionId", native.localDistributionId); put("supportsRun", native.supportsRun)
        }.toString()
        return AppAdminSource(session.workspace, adminHash(fingerprint), session.runMode == RunMode.BUILD.id,
            appAdminLocalRunSupported(session.workspace, session.executionTargetJson, native),
            native.resolvedModelId, native.resolvedModelVariant,
            session.executionTargetJson?.takeIf { appAdminLocalRunSupported(session.workspace, it, native) }?.let {
                top.wkbin.taixu.harness.workflow.WorkflowExecutionBinding(
                    it, session.workspace, session.approvalMode, session.runMode, "", native.executionSource)
            })
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
        appAdminValidateNativeRun(definition, runManager().supportsBoundExecution)
        require(definition.nodes.none { WorkflowExpansions.SOURCE_KEY in it.config }) { "动态扩图需重新批准完整定义" }
        // 插件节点必须有可信安装内容身份，否则无法冻结 pluginStamps。
        definition.nodes.filter { it.type == top.wkbin.taixu.core.model.workflow.WorkflowNodeType.PLUGIN_ACTION }.forEach {
            requireNotNull(plugins?.invoke()?.nodeIdentity(it.config["plugin"].orEmpty(), it.config["extension"].orEmpty())) {
                "插件尚无可信内容身份"
            }
        }
    }
    override suspend fun status(executionId: String): AppAdminRunStatus? {
        val manager = runManager()
        val handle = manager.handle(executionId)
        val state = handle?.state?.value ?: manager.activeRuns.value[executionId]
        if (state != null) return AppAdminRunStatus(executionId, state.definition.id,
            definitionHash(state.definition), state.status.name,
            manager.hasActiveResources(executionId), state.startedAt, state.finishedAt, manager.pendingCleanup.value.containsKey(executionId))
        // Read only log metadata. NEVER decode or return finalContextJson (logs, secrets, review tokens).
        val log = workflows.findExecutionById(executionId) ?: return null
        return AppAdminRunStatus(executionId, log.workflowId, "history-definition-not-exported", log.status,
            manager.hasActiveResources(executionId), log.startTime, log.endTime, manager.pendingCleanup.value.containsKey(executionId))
    }
    override suspend fun start(definition: WorkflowDefinition, variables: Map<String, String>, workspace: String): AppAdminStart =
        error("必须使用原生来源冻结执行租约")
    override suspend fun startBound(definition: WorkflowDefinition, variables: Map<String, String>, source: AppAdminSource): AppAdminStart {
        val manager = runManager()
        appAdminValidateNativeRun(definition, manager.supportsBoundExecution)
        val binding = requireNotNull(source.executionBinding) { "缺少原生目标和权限快照" }
            .let { frozen ->
                val hash = definitionHash(definition)
                val origin = requireNotNull(frozen.source) { "缺少原生主会话操作来源；请重新批准" }
                val stamps = definition.nodes.filter { it.type == top.wkbin.taixu.core.model.workflow.WorkflowNodeType.PLUGIN_ACTION }
                    .associate { it.id to requireNotNull(plugins?.invoke()?.nodeIdentity(it.config["plugin"].orEmpty(), it.config["extension"].orEmpty())) }
                frozen.copy(definitionHash = hash, source = origin.copy(definitionHash = hash, pluginStamps = stamps), pluginStamps = stamps)
            }
        binding.verify(definition)
        val approved = requireNotNull(binding.source) { "缺少原生主会话操作来源；请重新批准" }
        val executionId = "wf_${UUID.randomUUID()}"
        check(workflows.consumeSourceAuthorization(top.wkbin.taixu.core.database.WorkflowSourceAuthorization(
            approved.authorizationId, approved.sourceSessionId, approved.sourceOperationId,
            binding.definitionHash, approved.authorizationDigest(), System.currentTimeMillis(), executionId = executionId))) {
            "来源批准已消费或持久存储不支持；不会重复启动"
        }
        val result = manager.start(definition = definition, variables = variables,
            workspacePath = source.workspace, binding = binding, executionId = executionId)
        return AppAdminStart(result.executionId, result.accepted)
    }
    override suspend fun stop(executionId: String) {
        val manager = runManager()
        require(manager.hasActiveResources(executionId)) { "无活动运行或待清理资源" }
        manager.stop(executionId) // Wait for disposal; on failure keep pin and expose pending cleanup.
        check(!manager.hasActiveResources(executionId)) { "资源仍未释放，请查看状态并重试清理；不会重跑业务" }
    }
}, AppAdminAudit { session, action, detail ->
    audit.record(AgentApprovalAuditEntity(
        id = UUID.randomUUID().toString(), sessionId = session,
        kind = "app_admin_$action", toolName = AppAdminToolSpec.name,
        detail = detail, createdAt = System.currentTimeMillis(),
    ))
}))
