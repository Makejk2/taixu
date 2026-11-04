package top.wkbin.taixu.harness.admin

import top.wkbin.taixu.core.model.workflow.WorkflowDefinition
import top.wkbin.taixu.core.model.SessionExecutionTarget
import kotlinx.serialization.json.Json

/** Native infrastructure only. None of these fields is supplied by tool JSON. */
internal data class AppAdminSource(
    val workspace: String,
    val fingerprint: String,
    val build: Boolean,
    val runSupported: Boolean,
    val modelId: String? = null,
    val modelVariant: String? = null,
    val executionBinding: top.wkbin.taixu.harness.workflow.WorkflowExecutionBinding? = null,
)
internal data class AppAdminRunStatus(
    val executionId: String,
    val workflowId: String,
    val definitionHash: String,
    val status: String,
    val active: Boolean,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val cleanupPending: Boolean = false,
)
internal data class AppAdminStart(val executionId: String, val accepted: Boolean)
internal interface AppAdminPort {
    /** Reject missing sessions, member/group/assignment sessions, and unsupported identities. */
    suspend fun source(sessionId: String): AppAdminSource
    suspend fun definitions(): List<WorkflowDefinition>
    suspend fun definition(id: String): WorkflowDefinition?
    /** Implementations must compare and mutate atomically; no legacy read/write fallback. */
    suspend fun save(definition: WorkflowDefinition, expectedHash: String): Unit =
        error("原生仓库不支持工作流事务 CAS")
    suspend fun delete(id: String, expectedHash: String): Boolean =
        error("原生仓库不支持工作流事务 CAS")
    /** Read-only production capability check, before a proposal or any applying audit. */
    suspend fun validateRun(definition: WorkflowDefinition): Unit =
        error("原生适配器不支持运行预检")
    suspend fun status(executionId: String): AppAdminRunStatus?
    suspend fun start(definition: WorkflowDefinition, variables: Map<String, String>, workspace: String): AppAdminStart
    suspend fun startBound(definition: WorkflowDefinition, variables: Map<String, String>, source: AppAdminSource): AppAdminStart =
        start(definition, variables, source.workspace)
    suspend fun stop(executionId: String)
}
internal fun interface AppAdminAudit {
    suspend fun record(sessionId: String, action: String, detail: String)
}

/** App-owned effective model/environment binding, evaluated afresh before proposal and apply.
 * Fingerprints must cover effective model/profile (including default fallback), provider configuration
 * revision and local runtime/target identity; never contain credentials or raw provider settings.
 * A missing/unsupported target must set supportsRun=false. No JSON argument can set these fields.
 */
data class AppAdminNativeSourceState(
    val modelFingerprint: String,
    val environmentFingerprint: String,
    val supportsRun: Boolean,
    val resolvedModelId: String? = null,
    val resolvedModelVariant: String? = null,
    /** Exact active local LinuxRuntime distribution. Null means run is unsupported. */
    val localDistributionId: String? = null,
    /** 冻结的原生来源；null 表示尚未捕获可信来源，运行路径会随后拒绝。 */
    val executionSource: top.wkbin.taixu.harness.workflow.WorkflowExecutionSource? = null,
)

/** Only explicitly frozen native targets are admissible; never fall back to the active target. */
internal fun appAdminLocalRunSupported(workspace: String, targetJson: String?, native: AppAdminNativeSourceState): Boolean {
    if (!native.supportsRun || native.resolvedModelId.isNullOrBlank() || native.resolvedModelVariant.isNullOrBlank()) return false
    val target = runCatching {
        Json.decodeFromString<SessionExecutionTarget>(targetJson ?: return false).also { it.validate() }
    }.getOrNull() ?: return false
    // 远程目标只认冻结连接本身；本地目标仍必须等于已就绪发行版，绝不回退到当前活动目标。
    val validOwner = if (target.remote != null) target.remote.distributionId == target.distribution
        else target.distribution == native.localDistributionId
    return validOwner && target.workspace == workspace &&
        workspace.startsWith("/") && workspace.split('/').none { it == "." || it == ".." } && !workspace.contains('\u0000')
}

/** Keep the start-time final defense AND reject unsupported proposals before approval/audit. */
internal fun appAdminValidateNativeRun(definition: WorkflowDefinition, boundExecution: Boolean = false) {
    AppAdminSafety.runnableDefinition(definition)
    require(boundExecution || definition.nodes.none { it.type in setOf(
        top.wkbin.taixu.core.model.workflow.WorkflowNodeType.BASH_COMMAND,
        top.wkbin.taixu.core.model.workflow.WorkflowNodeType.PROCESS_SERVICE,
        top.wkbin.taixu.core.model.workflow.WorkflowNodeType.TAIXU_BUILD,
        top.wkbin.taixu.core.model.workflow.WorkflowNodeType.AGENT_INFERENCE,
        top.wkbin.taixu.core.model.workflow.WorkflowNodeType.SUBAGENT_DELEGATE,
        top.wkbin.taixu.core.model.workflow.WorkflowNodeType.HOST_ACTION,
        top.wkbin.taixu.core.model.workflow.WorkflowNodeType.PLUGIN_ACTION,
    ) }) { "作用节点必须使用真实绑定执行租约" }
}
