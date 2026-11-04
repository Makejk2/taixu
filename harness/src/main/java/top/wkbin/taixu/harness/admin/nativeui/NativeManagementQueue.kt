package top.wkbin.taixu.harness.admin.nativeui

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.wkbin.taixu.harness.admin.model.ModelNativeFormBridge
import top.wkbin.taixu.harness.admin.model.ModelNativeFormLauncher
import top.wkbin.taixu.harness.admin.model.ModelNativeFormRequest
import top.wkbin.taixu.harness.admin.session.NativeSessionRecovery

enum class ManagementAction { MODEL_FORM, PLUGIN_INSTALL, SKILL_INSTALL, SESSION_CLAIM, SESSION_LOAD, SESSION_CLEANUP, CREDENTIAL_CLEANUP }
enum class ManagementState { PENDING, RUNNING, OPENING, APPLIED, CANCELLED, EXPIRED, UNKNOWN_PARTIAL }
data class ManagementPending(
    val id: String,
    val sourceSessionId: String,
    val action: ManagementAction,
    val title: String,
    val reason: String,
    val risks: List<String>,
    val expiresAt: Long,
    val state: ManagementState = ManagementState.PENDING,
)

/** Native process memory only. Never register this object or handlers in a tool router/journal.
 * Handlers are retained privately, not serialized in the visible metadata projection. */
class NativeManagementQueue(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Handler(val confirm: suspend () -> ManagementState, val cancel: suspend () -> Unit)
    private val lock = Mutex()
    private val handlers = mutableMapOf<String, Handler>()
    private val mutablePending = MutableStateFlow<List<ManagementPending>>(emptyList())
    val pending: StateFlow<List<ManagementPending>> = mutablePending.asStateFlow()

    suspend fun enqueueNative(metadata: ManagementPending, confirm: suspend () -> ManagementState,
        cancel: suspend () -> Unit) = lock.withLock {
        check(metadata.state == ManagementState.PENDING && metadata.expiresAt > clock())
        check(mutablePending.value.none { it.id == metadata.id })
        check(mutablePending.value.size < 128) { "NATIVE_QUEUE_CAPACITY" }
        handlers[metadata.id] = Handler(confirm, cancel)
        mutablePending.value = mutablePending.value + metadata
    }

    /** UI click entry points accept an opaque id, never a model-supplied approve boolean. */
    suspend fun confirmFromNative(id: String) = resolve(id, true)
    suspend fun cancelFromNative(id: String) = resolve(id, false)

    private suspend fun resolve(id: String, confirm: Boolean) {
        val (handler, expired) = lock.withLock {
            val item = mutablePending.value.singleOrNull { it.id == id } ?: return
            if (item.state != ManagementState.PENDING) return
            val expired = item.expiresAt <= clock()
            val handler = requireNotNull(handlers.remove(id))
            update(id, ManagementState.RUNNING)
            handler to expired
        }
        try {
            val result = if (expired || !confirm) {
                handler.cancel()
                if (expired) ManagementState.EXPIRED else ManagementState.CANCELLED
            } else handler.confirm()
            check(result != ManagementState.PENDING && result != ManagementState.RUNNING)
            lock.withLock { update(id, result) }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { lock.withLock { update(id, ManagementState.UNKNOWN_PARTIAL) } }
            if (failure is CancellationException) throw failure
            // No raw exception/URI/key is projected into the panel or journal.
        }
    }

    suspend fun finishModelFromNative(id: String, state: ManagementState) = lock.withLock {
        require(state in setOf(ManagementState.APPLIED, ManagementState.CANCELLED, ManagementState.EXPIRED, ManagementState.UNKNOWN_PARTIAL))
        check(mutablePending.value.single { it.id == id }.state == ManagementState.OPENING)
        update(id, state)
    }

    suspend fun dismissFinishedFromNative(id: String) = lock.withLock {
        check(mutablePending.value.single { it.id == id }.state !in setOf(ManagementState.PENDING, ManagementState.RUNNING, ManagementState.OPENING))
        mutablePending.value = mutablePending.value.filterNot { it.id == id }
    }

    private fun update(id: String, state: ManagementState) {
        mutablePending.value = mutablePending.value.map { if (it.id == id) it.copy(state = state) else it }
    }
}

/** Wire to ModelNativeFormBridge.launcher; enqueueing/opening is NOT an applied mutation. */
class QueuedModelNativeFormLauncher(
    private val queue: NativeManagementQueue,
    private val bridge: () -> ModelNativeFormBridge,
    private val openEditorFromNative: suspend (ModelNativeFormRequest) -> Unit,
) : ModelNativeFormLauncher {
    override suspend fun open(request: ModelNativeFormRequest) {
        queue.enqueueNative(ManagementPending(request.requestId, request.sourceSessionId,
            ManagementAction.MODEL_FORM, "模型原生表单 · ${request.operation}",
            "会话请求修改模型档案；凭据只在原生编辑页填写。",
            listOf("打开页面不代表保存成功", "提交时必须重新校验会话所有权、epoch 与写入快照"), request.expiresAt),
            confirm = { openEditorFromNative(request); ManagementState.OPENING },
            cancel = { bridge().cancelFromNative(request.sourceSessionId, request.requestId) })
    }
}

suspend fun NativeManagementQueue.enqueueSessionFromNative(
    recovery: NativeSessionRecovery, sourceSessionId: String, targetSessionId: String,
    action: ManagementAction, expectedRevision: Long, expiresAt: Long,
) {
    require(action in setOf(ManagementAction.SESSION_CLAIM, ManagementAction.SESSION_LOAD, ManagementAction.SESSION_CLEANUP))
    enqueueNative(ManagementPending(UUID.randomUUID().toString(), sourceSessionId, action,
        "会话原生操作 · $targetSessionId", "需要本机用户明确操作。",
        listOf("清理可能永久删除会话资源", "所有权与运行时保留状态会在执行时再次校验"), expiresAt), confirm = {
        when (action) {
            ManagementAction.SESSION_CLAIM -> check(recovery.claimFromNative(targetSessionId, expectedRevision))
            ManagementAction.SESSION_LOAD -> recovery.loadFromNative(targetSessionId)
            ManagementAction.SESSION_CLEANUP -> recovery.retryCleanupFromNative(targetSessionId, sourceSessionId)
            else -> error("Unsupported native session action")
        }
        ManagementState.APPLIED
    }, cancel = {})
}
