package top.wkbin.taixu.harness.workflow

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import top.wkbin.taixu.core.database.AiModelRepository
import top.wkbin.taixu.core.database.HarnessSessionEntity
import top.wkbin.taixu.core.database.HarnessSessionRepository
import kotlinx.coroutines.currentCoroutineContext
import top.wkbin.taixu.harness.subagent.WorkflowLaneAuthority
import top.wkbin.taixu.harness.SubagentOrchestrator
import top.wkbin.taixu.harness.subagent.SubagentLaneRunner

data class WorkflowAgentRequest(
    val executionId: String,
    val nodeId: String,
    val nodeTitle: String,
    val prompt: String,
    val workspacePath: String,
    val modelId: String? = null,
    val modelVariant: String? = null,
    val role: String = "",
    val department: String = "",
    val agentQuery: String = "",
    val taskName: String = "",
    val writePaths: List<String> = emptyList(),
)

data class WorkflowAgentResult(
    val success: Boolean,
    val output: String,
    val toolCallCount: Int? = null,
)

/** Stable, awaitable Harness boundary consumed by workflow nodes. */
interface WorkflowAgentExecutionPort {
    suspend fun infer(request: WorkflowAgentRequest): WorkflowAgentResult
    suspend fun delegate(request: WorkflowAgentRequest): WorkflowAgentResult
}

/**
 * Runs workflow agents in a durable background session without changing the chat UI's
 * foreground session. Coroutine cancellation is propagated into the lane runner, which
 * records an aborted operation before unwinding.
 */
class HarnessWorkflowAgentExecutionPort(
    private val sessions: HarnessSessionRepository,
    private val models: AiModelRepository,
    private val laneRunner: SubagentLaneRunner,
    private val subagentOrchestrator: SubagentOrchestrator,
    private val provider: () -> top.wkbin.taixu.harness.ProviderClient = { error("WORKFLOW_MODEL_RESOLVER_UNAVAILABLE") },
) : WorkflowAgentExecutionPort {
    private val sessionCreationMutex = Mutex()

    override suspend fun infer(request: WorkflowAgentRequest): WorkflowAgentResult {
        val session = ensureSession(request)
        val frozenRow = requireNotNull(models.findById(requireNotNull(session.modelId)))
        val configured = provider().resolveSavedModelProfile(frozenRow.id,requireNotNull(session.modelVariant))
        check(models.findById(frozenRow.id) == frozenRow) { "来源模型在解析期间变化，需重新批准" }
        val result = laneRunner.run(
            sessionId = session.id,
            laneName = "subagent:workflow-agent:${request.nodeId}",
            prompt = request.prompt,
            workspace = request.workspacePath,
            modelId = request.modelId ?: session.modelId,
            modelVariant = request.modelVariant ?: session.modelVariant,
            modelConfig = configured,
            // 节点声明了写范围才启用闸门；未声明的推理节点维持原有可写行为，
            // 否则存量工作流里"生成文件"类推理节点会被静默降级成只读。
            writePaths = request.writePaths.takeIf { it.isNotEmpty() },
        )
        return WorkflowAgentResult(result.success, result.summary, result.toolCallCount)
    }

    override suspend fun delegate(request: WorkflowAgentRequest): WorkflowAgentResult {
        val session = ensureSession(request)
        val args = JsonObject(
            buildMap {
                put("prompt", JsonPrimitive(request.prompt))
                put("taskName", JsonPrimitive(request.taskName.ifBlank { request.nodeTitle }))
                request.role.takeIf(String::isNotBlank)?.let { put("role", JsonPrimitive(it)) }
                request.department.takeIf(String::isNotBlank)?.let { put("department", JsonPrimitive(it)) }
                request.agentQuery.takeIf(String::isNotBlank)?.let { put("agentQuery", JsonPrimitive(it)) }
                put("model", JsonPrimitive(checkNotNull(session.modelId)))
                if (request.writePaths.isNotEmpty()) {
                    put("writePaths", JsonArray(request.writePaths.map(::JsonPrimitive)))
                }
            },
        )
        val (success, output) = subagentOrchestrator.executeSubagents(args, session.id)
        return WorkflowAgentResult(success, output)
    }

    private suspend fun ensureSession(request: WorkflowAgentRequest): HarnessSessionEntity {
        val sessionId = "workflow:${request.executionId}:${request.nodeId}"
        return sessionCreationMutex.withLock {
            val now = System.currentTimeMillis()
            val authority = checkNotNull(currentCoroutineContext()[WorkflowLaneAuthority]) {
                "缺少可信工作流来源授权；禁止使用活动模型或默认权限"
            }
            check(authority.executionId == request.executionId && authority.nodeId == request.nodeId)
            val source = authority.source
            val binding = checkNotNull(currentCoroutineContext()[WorkflowExecutionContext]).binding
            source.verify(binding)
            val model = checkNotNull(models.findById(source.modelBinding.modelId)) { "来源模型已删除" }
            val candidateFingerprint = WorkflowModelBinding.fingerprint(model)
            if (candidateFingerprint != source.modelBinding.fingerprint) {
                require(source.modelBinding.variant in model.model.split(',').map(String::trim)) { "原变体已删除，需重新选择并原生批准" }
                check(authority.reviewModel(candidateFingerprint,source.modelBinding.variant)) { "用户未批准新模型配置，未执行" }
                check(models.findById(model.id) == model) { "审阅期间模型再次变化，请重新批准" }
            } else source.modelBinding.verify(model)
            check(request.workspacePath == source.workspace)
            check(request.modelId == null || request.modelId == source.modelBinding.modelId)
            check(request.modelVariant == null || request.modelVariant == source.modelBinding.variant)
            val resolvedModelId = source.modelBinding.modelId
            val resolvedVariant = source.modelBinding.variant
            val existing = sessions.findById(sessionId)
            val entity = (existing ?: HarnessSessionEntity(
                id = sessionId,
                title = "工作流 · ${request.nodeTitle}",
                createdAt = now,
                updatedAt = now,
                modelId = resolvedModelId,
                modelVariant = resolvedVariant,
                workspace = request.workspacePath,
                approvalMode = source.approvalMode,
                runMode = source.runMode,
                executionTargetJson = source.targetJson,
            )).copy(
                title = "工作流 · ${request.nodeTitle}",
                updatedAt = now,
                modelId = resolvedModelId,
                modelVariant = resolvedVariant,
                workspace = request.workspacePath.ifBlank { existing?.workspace.orEmpty() },
                approvalMode = source.approvalMode,
                runMode = source.runMode,
                executionTargetJson = source.targetJson,
            )
            sessions.upsert(entity)
            entity
        }
    }
}
