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
): AppAdminBackend = createAppAdminBackend(
    workflows, sessions, bots, assignments, audit, runManager,
    nativeSourceState = { sessionId ->
        val session = requireNotNull(sessions.findById(sessionId)) { "原生会话不存在" }
        val distribution = runtime.activeDistroId.value
        val ready = runtime.state.value is RuntimeState.Ready
        val model = try {
            provider().resolveConfigured(session.modelId, session.modelVariant)
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
        appAdminBoundNativeSource(
            session.workspace, session.executionTargetJson, distribution, ready,
            model?.let(::appAdminModelFingerprint) ?: adminHash("unresolved-model"),
            if (identity != null) session.modelId else null,
            identity?.model,
        )
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
