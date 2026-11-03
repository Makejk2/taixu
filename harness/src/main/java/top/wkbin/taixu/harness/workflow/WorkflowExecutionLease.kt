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
import top.wkbin.taixu.core.model.workflow.WorkflowNodeType
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
) {
    fun target(): SessionExecutionTarget = Json.decodeFromString<SessionExecutionTarget>(targetJson).also {
        it.validate()
        require(it.remote == null && !it.distribution.startsWith("ssh:")) { "工作流尚不支持远程执行租约" }
        require(it.workspace == workspace && (workspace == "/workspace" || workspace.startsWith("/workspace/"))) { "执行工作区与批准目标不一致" }
        require(workspace.split('/').none { part -> part == "." || part == ".." } && '\u0000' !in workspace) { "非法工作区" }
        require(runMode == "build" && approvalMode in setOf("request", "assisted", "full_access")) { "工作流没有执行权限快照" }
    }

    fun verify(definition: WorkflowDefinition) {
        target()
        check(definition.nodes.none { top.wkbin.taixu.core.model.workflow.WorkflowExpansions.SOURCE_KEY in it.config }) { "动态扩图需重新批准完整定义；此运行不沿用原授权" }
        check(definitionHash == hash(definition)) { "工作流图已变化；必须重新批准完整定义" }
        check(definition.nodes.none { it.type in UNSUPPORTED }) { "此租约尚不支持 Agent、子智能体或宿主节点；不能提升来源权限" }
    }

    /** Existing durable runtimeEnvironmentId carries a versioned identity, not mutable variables. */
    fun encode(): String = PREFIX + buildJsonObject {
        put("target", targetJson); put("workspace", workspace); put("approvalMode", approvalMode)
        put("runMode", runMode); put("definitionHash", definitionHash)
    }

    companion object {
        private const val PREFIX = "workflow-bound-v1:"
        private val UNSUPPORTED = setOf(WorkflowNodeType.AGENT_INFERENCE, WorkflowNodeType.SUBAGENT_DELEGATE, WorkflowNodeType.HOST_ACTION)
        fun decode(identity: String?): WorkflowExecutionBinding? {
            if (identity?.startsWith(PREFIX) != true) return null
            val obj = Json.parseToJsonElement(identity.removePrefix(PREFIX)).jsonObject
            fun field(key: String) = obj.getValue(key).jsonPrimitive.content
            return WorkflowExecutionBinding(field("target"), field("workspace"), field("approvalMode"), field("runMode"), field("definitionHash")).also { it.target() }
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
        val owner = Owner()
        check(owners.putIfAbsent(executionId, owner) == null) { "该执行仍持有资源，请先重试清理" }
        var original: Throwable? = null
        try {
            // Install ownership before leaving NonCancellable: cancellation cannot lose a pin/world.
            val environment = withContext(NonCancellable) {
                owner.pin = acquireTargetPin(target.distribution)
                check(available(target.distribution)) { "绑定的发行版已删除或不可用；不会切回默认系统" }
                localFactory.open("workflow:$executionId", binding.workspace, target.distribution).also { world ->
                    owner.resources.own { world.close() }
                }
            }
            currentCoroutineContext().ensureActive()
            check(environment.id.distribution == target.distribution && environment.id.workspace == binding.workspace && environment.id.backend == "local-proot") {
                "执行环境工厂返回了不同目标"
            }
            return withContext(WorkflowExecutionContext(binding, environment, owner.resources, available) +
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
) : AbstractCoroutineContextElement(Key) {
    suspend fun <T> operation(definition: WorkflowDefinition, block: suspend () -> T): T = resources.activity {
        binding.verify(definition)
        check(available(environment.id.distribution)) { "绑定的发行版已删除或不可用；不会切回默认系统" }
        block()
    }
    companion object Key : CoroutineContext.Key<WorkflowExecutionContext>
}
