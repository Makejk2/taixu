package top.wkbin.taixu.harness.workflow

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import top.wkbin.taixu.core.model.SessionExecutionTarget
import top.wkbin.taixu.core.model.workflow.WorkflowDefinition
import top.wkbin.taixu.core.model.workflow.*
import top.wkbin.taixu.harness.core.SessionResourceScope
import top.wkbin.taixu.runtime.environment.ExecutionEnvironment
import top.wkbin.taixu.runtime.environment.ExecutionEnvironmentContext
import top.wkbin.taixu.runtime.environment.ExecutionEnvironmentFactory

/** Native approval snapshot, never parsed from model tool arguments. No credentials are stored. */
data class WorkflowExecutionBinding(
    val targetJson: String,
    val workspace: String,
    val approvalMode: String,
    val runMode: String,
    val definitionHash: String,
    val source: WorkflowExecutionSource? = null,
    val pluginStamps: Map<String, String> = emptyMap(),
) {
    fun target(): SessionExecutionTarget = Json.decodeFromString<SessionExecutionTarget>(targetJson).also {
        it.validate()
        require(it.workspace == workspace && (workspace == "/workspace" || workspace.startsWith("/workspace/"))) { "执行工作区与批准目标不一致" }
        require(workspace.split('/').none { part -> part == "." || part == ".." } && '\u0000' !in workspace) { "非法工作区" }
        require(runMode == "build" && approvalMode in setOf("request", "assisted", "full_access")) { "工作流没有执行权限快照" }
    }

    fun verify(definition: WorkflowDefinition) {
        target()
        check(definitionHash == hash(definition)) { "工作流图已变化；必须重新批准完整定义" }
        source?.verify(this)
        check(pluginStamps.keys == definition.nodes.filter { it.type == WorkflowNodeType.PLUGIN_ACTION }.map { it.id }.toSet()) {
            "插件节点缺少逐项原生内容身份"
        }
        check(source != null || definition.nodes.none { it.type in SOURCE_REQUIRED }) {
            "Agent/宿主/插件节点缺少可信来源授权；旧日志不能升级权限"
        }
    }

    /** Existing durable runtimeEnvironmentId carries a versioned identity, not mutable variables. */
    fun encode(): String = PREFIX + buildJsonObject {
        put("target", targetJson); put("workspace", workspace); put("approvalMode", approvalMode)
        put("runMode", runMode); put("definitionHash", definitionHash)
        source?.let { put("source", Json.encodeToJsonElement(WorkflowExecutionSource.serializer(), it)) }
        put("pluginStamps", JsonObject(pluginStamps.toSortedMap().mapValues { JsonPrimitive(it.value) }))
    }

    companion object {
        private const val PREFIX = "workflow-bound-v1:"
        private val SOURCE_REQUIRED = setOf(WorkflowNodeType.AGENT_INFERENCE, WorkflowNodeType.SUBAGENT_DELEGATE,
            WorkflowNodeType.HOST_ACTION, WorkflowNodeType.PLUGIN_ACTION)
        fun decode(identity: String?): WorkflowExecutionBinding? {
            if (identity?.startsWith(PREFIX) != true) return null
            val obj = Json.parseToJsonElement(identity.removePrefix(PREFIX)).jsonObject
            fun field(key: String) = obj.getValue(key).jsonPrimitive.content
            return WorkflowExecutionBinding(field("target"), field("workspace"), field("approvalMode"), field("runMode"), field("definitionHash"),
                obj["source"]?.let { Json.decodeFromJsonElement(WorkflowExecutionSource.serializer(), it) },
                obj["pluginStamps"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()).also { it.target() }
        }
        fun hash(definition: WorkflowDefinition): String = MessageDigest.getInstance("SHA-256")
            .digest(Json.encodeToString(WorkflowDefinition.serializer(), definition).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/** A workflow owns its environment independently of the source chat. Inject the LOCAL factory,
 * not SelectedExecutionEnvironmentFactory (its session registry deliberately rejects wf owners).
 * available must check this exact installed target on acquisition AND before every node operation.
 */
class WorkflowExecutionLeases(
    private val localFactory: ExecutionEnvironmentFactory,
    private val acquireTargetPin: suspend (String) -> AutoCloseable = { error("缺少可保留的目标租约") },
    private val available: suspend (distribution: String) -> Boolean,
    private val verifySource: suspend (String, WorkflowExecutionSource) -> Boolean = { _, _ -> false },
    private val remote: FrozenRemoteWorkflowEnvironment? = null,
    private val reviewJournal: suspend (top.wkbin.taixu.core.database.WorkflowNativeReviewEvent) -> Unit = { error("NATIVE_REVIEW_JOURNAL_UNAVAILABLE") },
    private val settleReview: suspend (String, String) -> Unit = { _, _ -> error("NATIVE_REVIEW_JOURNAL_UNAVAILABLE") },
    private val readReview: suspend (String) -> top.wkbin.taixu.core.database.WorkflowNativeReviewEvent? = { error("NATIVE_REVIEW_LOOKUP_UNAVAILABLE") },
    private val capturePluginStamps: suspend (WorkflowDefinition) -> Map<String, String> = { graph ->
        check(graph.nodes.none { it.type == WorkflowNodeType.PLUGIN_ACTION }) { "PLUGIN_STAMP_CAPTURE_UNAVAILABLE" }
        emptyMap()
    },
) {
    private class Owner {
        val resources = SessionResourceScope()
        val cleanup = Mutex()
        var pin: AutoCloseable? = null
        @Volatile var running = true
    }
    private val owners = ConcurrentHashMap<String, Owner>()
    private val failures = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Cleanup failure is separate from the business result; never authorizes replay. */
    val pendingCleanup = failures.asStateFlow()
    fun hasResources(executionId: String): Boolean = owners.containsKey(executionId)

    suspend fun <T> use(executionId: String, binding: WorkflowExecutionBinding, block: suspend () -> T): T {
        val target = binding.target()
        binding.source?.let { check(verifySource(executionId, it)) { "持久来源批准不可核验，未创建执行环境" } }
        val owner = Owner()
        check(owners.putIfAbsent(executionId, owner) == null) { "该执行仍持有资源，请先重试清理" }
        var original: Throwable? = null
        try {
            // Install ownership before leaving NonCancellable: cancellation cannot lose a pin/world.
            val environment = withContext(NonCancellable) {
                val world = if (target.remote == null) {
                    owner.pin = acquireTargetPin(target.distribution)
                    check(available(target.distribution)) { "批准的本地系统不可用；不会回退" }
                    localFactory.open("workflow:$executionId", binding.workspace, target.distribution)
                } else {
                    val opener = requireNotNull(remote) { "REMOTE_WORKFLOW_FACTORY_UNAVAILABLE" }
                    owner.pin = opener.pin(target)
                    check(opener.available(target)) { "批准的远程目标不可用；不会回退" }
                    opener.open("workflow:$executionId", target)
                }
                world.also { owner.resources.own { it.close() } }
            }
            currentCoroutineContext().ensureActive()
            check(environment.id.distribution == target.distribution && environment.logicalWorkspace == binding.workspace &&
                environment.id.backend == if (target.remote == null) "local-proot" else "ssh") {
                "执行环境工厂返回了不同目标"
            }
            val targetAvailable: suspend (String) -> Boolean = { id ->
                id == target.distribution && if (target.remote == null) available(id) else remote?.available(target) == true
            }
            return withContext(WorkflowExecutionContext(binding, environment, owner.resources, targetAvailable, reviewJournal, settleReview, readReview, capturePluginStamps) +
                ExecutionEnvironmentContext(environment, owner.resources::own)) { block() }
        } catch (failure: Throwable) {
            original = failure
            throw failure
        } finally {
            owner.running = false
            try { dispose(executionId, owner) } catch (cleanup: Throwable) {
                if (original != null) original.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    /** Only closes retained resources. It never opens an environment or invokes the workflow. */
    suspend fun retryCleanup(executionId: String): Boolean {
        val owner = owners[executionId] ?: return true
        check(!owner.running) { "工作流仍在执行，须先停止业务" }
        dispose(executionId, owner)
        return !hasResources(executionId)
    }

    private suspend fun dispose(executionId: String, owner: Owner) = withContext(NonCancellable) {
        owner.cleanup.withLock {
            if (owners[executionId] !== owner) return@withLock
            try {
                owner.resources.close()
                owner.pin?.close() // Deliberately NOT in finally: failed resources keep storage pinned.
                owners.remove(executionId, owner)
                failures.update { it - executionId }
            } catch (failure: Throwable) {
                failures.update { it + (executionId to (failure.message ?: "资源清理失败")) }
                throw failure
            }
        }
    }
}

internal class WorkflowExecutionContext(
    val binding: WorkflowExecutionBinding,
    val environment: ExecutionEnvironment,
    private val resources: SessionResourceScope,
    private val available: suspend (String) -> Boolean,
    val reviewJournal: suspend (top.wkbin.taixu.core.database.WorkflowNativeReviewEvent) -> Unit = { error("NATIVE_REVIEW_JOURNAL_UNAVAILABLE") },
    val settleReview: suspend (String, String) -> Unit = { _, _ -> error("NATIVE_REVIEW_JOURNAL_UNAVAILABLE") },
    val readReview: suspend (String) -> top.wkbin.taixu.core.database.WorkflowNativeReviewEvent? = { error("NATIVE_REVIEW_LOOKUP_UNAVAILABLE") },
    val capturePluginStamps: suspend (WorkflowDefinition) -> Map<String, String> = { graph ->
        check(graph.nodes.none { it.type == WorkflowNodeType.PLUGIN_ACTION }) { "PLUGIN_STAMP_CAPTURE_UNAVAILABLE" }
        emptyMap()
    },
) : AbstractCoroutineContextElement(Key) {
    @Volatile private var approvedPluginStamps: Map<String, String> = binding.pluginStamps

    fun pluginStamp(nodeId: String): String? = approvedPluginStamps[nodeId]

    @Synchronized
    private fun rememberPluginStamps(stamps: Map<String, String>) {
        approvedPluginStamps = approvedPluginStamps + stamps
    }

    /** Frozen-binding contract only. Without a journal there is nothing to replay; the binding carries
     * source authorization and per-node plugin identities, so a bare definition cannot upgrade anything.
     */
    suspend fun <T> operation(definition: WorkflowDefinition, block: suspend () -> T): T = resources.activity {
        binding.verify(definition)
        check(available(environment.id.distribution)) { "绑定的发行版已删除或不可用；不会切回默认系统" }
        block()
    }

    /** Replays the persisted approval chain so the running graph is exactly the approved one. */
    suspend fun verifyHistory(state: WorkflowRuntimeState) {
        WorkflowExpansions.validateHistory(state)
        var graph = state.baseDefinition ?: state.definition
        binding.verify(graph)
        var stamps = binding.pluginStamps
        val reviewIds = mutableSetOf<String>()
        state.journal.forEach { entry ->
            val patch = entry.expansion ?: return@forEach
            val next = WorkflowExpansions.append(graph, entry.nodeId, patch)
            if (binding.source == null) {
                check(next.nodes.none { it.type in setOf(WorkflowNodeType.AGENT_INFERENCE, WorkflowNodeType.SUBAGENT_DELEGATE,
                    WorkflowNodeType.HOST_ACTION, WorkflowNodeType.PLUGIN_ACTION) }) { "旧绑定扩图不能升级来源权限" }
            }
            if (binding.source != null) {
                val admission = requireNotNull(entry.expansionAdmission) { "扩图缺少原生批准凭据" }
                check(reviewIds.add(admission.reviewId)) { "扩图复用批准凭据" }
                check(admission.parentHash == WorkflowExecutionBinding.hash(graph) &&
                    admission.definitionHash == WorkflowExecutionBinding.hash(next)) { "扩图批准链 hash 不匹配" }
                check(admission.pluginStamps.keys == patch.nodes.filter { it.type == WorkflowNodeType.PLUGIN_ACTION }.map { it.id }.toSet() &&
                    admission.pluginStamps.values.all { it.isNotBlank() }) { "扩图插件内容身份不完整" }
                val digest = expansionAdmissionDigest(binding, state.executionId, entry.nodeId, graph, next, patch, admission.pluginStamps)
                check(admission.requestDigest == digest) { "扩图批准摘要不匹配" }
                val row = requireNotNull(readReview(admission.reviewId)) { "扩图原生批准记录不存在" }
                check(row.state == "APPROVED" && row.executionId == state.executionId && row.nodeId == entry.nodeId &&
                    row.requestDigest == digest) { "扩图未获持久原生批准" }
                stamps = stamps + admission.pluginStamps
            }
            graph = next
        }
        check(graph == state.definition) { "运行图并非批准链重放结果" }
        rememberPluginStamps(stamps)
    }

    suspend fun <T> operation(state: WorkflowRuntimeState, block: suspend () -> T): T = resources.activity {
        verifyHistory(state)
        check(available(environment.id.distribution)) { "绑定的发行版已删除或不可用；不会切回默认系统" }
        block()
    }
    companion object Key : CoroutineContext.Key<WorkflowExecutionContext>
}
