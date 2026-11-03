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
)
internal data class AppAdminRunStatus(
    val executionId: String,
    val workflowId: String,
    val definitionHash: String,
    val status: String,
    val active: Boolean,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
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
)

/** RunManager has no remote-target argument. Never silently run a remote/unbound session locally. */
internal fun appAdminLocalRunSupported(workspace: String, targetJson: String?, native: AppAdminNativeSourceState): Boolean {
    if (!native.supportsRun || native.localDistributionId.isNullOrBlank() ||
        native.resolvedModelId.isNullOrBlank() || native.resolvedModelVariant.isNullOrBlank()) return false
    val target = runCatching {
        Json.decodeFromString<SessionExecutionTarget>(targetJson ?: return false).also { it.validate() }
    }.getOrNull() ?: return false
    return target.remote == null && !target.distribution.startsWith("ssh:") &&
        target.distribution == native.localDistributionId && target.workspace == workspace &&
        workspace.startsWith("/") && workspace.split('/').none { it == "." || it == ".." } && !workspace.contains('\u0000')
}

/** Keep the start-time final defense AND reject unsupported proposals before approval/audit. */
internal fun appAdminValidateNativeRun(definition: WorkflowDefinition) {
    AppAdminSafety.runnableDefinition(definition)
    require(definition.nodes.none { it.type in setOf(
        top.wkbin.taixu.core.model.workflow.WorkflowNodeType.BASH_COMMAND,
        top.wkbin.taixu.core.model.workflow.WorkflowNodeType.PROCESS_SERVICE,
        top.wkbin.taixu.core.model.workflow.WorkflowNodeType.TAIXU_BUILD,
        top.wkbin.taixu.core.model.workflow.WorkflowNodeType.HOST_ACTION,
    ) }) { "此原生适配器尚无执行目标全程租约；含沙盒或宿主副作用节点请在工作流界面运行" }
}
