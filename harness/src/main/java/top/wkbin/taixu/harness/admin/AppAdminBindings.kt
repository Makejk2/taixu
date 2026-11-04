package top.wkbin.taixu.harness.admin

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import top.wkbin.taixu.core.database.*
import top.wkbin.taixu.core.database.repository.BotAssignmentRepository
import top.wkbin.taixu.core.database.repository.PersonaBotRepository
import top.wkbin.taixu.core.model.RuntimeState
import top.wkbin.taixu.harness.ModelConfig
import top.wkbin.taixu.harness.ProviderClient
import top.wkbin.taixu.harness.workflow.WorkflowRunManager
import top.wkbin.taixu.runtime.LinuxRuntime

/** Bind native, explicitly selected local sources; never infer a target from the active sandbox.
 * Resolution reads configuration only, not an inference API. ModelConfig has no profileId:
 * an explicit saved session profile + concrete variant is required for WORKFLOW_MODEL_* identity.
 * Missing/default selections can still read/manage definitions, but cannot run them here.
 */
fun createBoundAppAdminBackend(
    workflows: WorkflowRepository,
    sessions: HarnessSessionRepository,
    bots: PersonaBotRepository,
    assignments: BotAssignmentRepository,
    audit: AgentApprovalAuditRepository,
    runManager: () -> WorkflowRunManager,
    provider: () -> ProviderClient,
    runtime: LinuxRuntime,
    runtimeRepository: HarnessRuntimeRepository? = null,
    models: AiModelRepository? = null,
    remoteServers: top.wkbin.taixu.core.datastore.RemoteServerRepository? = null,
    plugins: (() -> top.wkbin.taixu.harness.plugins.HarnessPluginRegistry)? = null,
): AppAdminBackend = createAppAdminBackend(
    workflows, sessions, bots, assignments, audit, runManager,
    plugins = plugins,
    nativeSourceState = { sessionId ->
        val caller = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[NativeManagementActor]) { "缺少本次可信原生调用身份" }
        require(caller.sessionId==sessionId)
        val session = requireNotNull(sessions.findById(sessionId)) { "原生会话不存在" }
        val distribution = runtime.activeDistroId.value
        val ready = runtime.state.value is RuntimeState.Ready
        val model = try {
            provider().resolveSavedModelProfile(requireNotNull(session.modelId), requireNotNull(session.modelVariant))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null // Unknown/deleted/invalid profiles must not acquire a fabricated identity.
        }
        require(sessions.findById(sessionId) == session && runtime.activeDistroId.value == distribution &&
            (runtime.state.value is RuntimeState.Ready) == ready) { "原生来源解析期间已变化" }
        val identity = model?.takeIf {
            !session.modelId.isNullOrBlank() && !session.modelVariant.isNullOrBlank() &&
                it.provider.isNotBlank() && it.model.isNotBlank() &&
                it.model.equals(session.modelVariant, ignoreCase = true)
        }
        val lane = runtimeRepository?.findLane(sessionId, "main")
        val savedModel = identity?.let { models?.findById(requireNotNull(session.modelId)) }
        val operationId = caller.operationId
        val actualOperation = runtimeRepository?.findOperation(operationId)
        require(lane?.currentOperationId==operationId && actualOperation?.sessionId==sessionId && actualOperation.laneName=="main") {
            "本次调用并非当前原生主操作"
        }
        val source = if (savedModel != null && !operationId.isNullOrBlank() && !session.executionTargetJson.isNullOrBlank()) {
            top.wkbin.taixu.harness.workflow.WorkflowExecutionSource(
                sourceSessionId = sessionId, sourceOperationId = operationId,
                modelBinding = top.wkbin.taixu.harness.workflow.WorkflowModelBinding(
                    savedModel.id, requireNotNull(session.modelVariant),
                    top.wkbin.taixu.harness.workflow.WorkflowModelBinding.fingerprint(savedModel)),
                targetJson = requireNotNull(session.executionTargetJson), workspace = session.workspace,
                approvalMode = session.approvalMode, runMode = session.runMode,
                definitionHash = "", authorizationId = adminHash("$sessionId:$operationId"),
            )
        } else null
        require(runtimeRepository?.findLane(sessionId, "main") == lane && sessions.findById(sessionId) == session) {
            "原生操作来源解析期间已变化"
        }
        val target = runCatching { Json.decodeFromString<top.wkbin.taixu.core.model.SessionExecutionTarget>(
            requireNotNull(session.executionTargetJson)).also { it.validate() } }.getOrNull()
        val remoteReady = target?.remote?.let { expected ->
            remoteServers?.servers?.value?.singleOrNull { it.id == expected.id } == expected
        } == true
        val bound = appAdminBoundNativeSource(
            session.workspace, session.executionTargetJson, distribution, ready,
            model?.let(::appAdminModelFingerprint) ?: adminHash("unresolved-model"),
            if (identity != null) session.modelId else null,
            identity?.model,
        )
        val candidate = if (target?.remote != null) bound.copy(
            supportsRun = remoteReady && identity != null, localDistributionId = null,
            environmentFingerprint = adminHash(requireNotNull(session.executionTargetJson))) else bound
        candidate.copy(supportsRun = source != null && appAdminLocalRunSupported(session.workspace,session.executionTargetJson,candidate),
            executionSource = source)
    },
)

/** Explicit allowlist: no ModelConfig.toString(), API keys, or secret-bearing custom headers.
 * Only hashes leave this callback. Credential-only rotations have no native public revision;
 * this adapter does not claim to detect them. Public inference/capability settings are covered.
 */
internal fun appAdminModelFingerprint(model: ModelConfig): String = adminHash(buildJsonObject {
    put("name", model.name); put("provider", model.provider); put("model", model.model)
    put("baseUrl", model.baseUrl); put("protocol", model.protocol.name)
    put("requestsPerMinutePerKey", model.requestsPerMinutePerKey)
    put("temperature", model.temperature); put("maxTokens", model.maxTokens); put("topP", model.topP)
    put("reasoningMode", model.reasoningMode.name); put("reasoningEffort", model.reasoningEffort?.name)
    put("toolCallMode", model.toolCallMode.name)
    put("dynamicMcpTools", Json.encodeToJsonElement(model.dynamicMcpTools))
    put("contextTokens", model.contextTokens)
    put("compactionKeepRecentTokens", model.compactionKeepRecentTokens)
    put("compactionReserveTokens", model.compactionReserveTokens)
    put("pureChatMode", model.pureChatMode); put("visionEnabled", model.visionEnabled)
    put("responseApiEnabled", model.responseApiEnabled)
    put("promptCachingEnabled", model.promptCachingEnabled); put("promptCacheTtl1h", model.promptCacheTtl1h)
    put("firstResponseTimeoutMs", model.firstResponseTimeoutMs)
}.toString())
