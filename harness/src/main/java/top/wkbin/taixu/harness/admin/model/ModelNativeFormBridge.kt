package top.wkbin.taixu.harness.admin.model

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** No key, URL, header, or free text can enter the AI-visible request contract. */
enum class ModelNativeFormOperation { CREATE, DELETE, CHANGE_CREDENTIALS }
data class ModelNativeFormRequest(
    val requestId: String,
    val sourceSessionId: String,
    val operation: ModelNativeFormOperation,
    val modelId: String?,
    val expiresAt: Long,
)
enum class ModelNativeFormState { PENDING_NATIVE_FORM, EXECUTING, APPLIED, CANCELLED, EXPIRED, UNKNOWN_PARTIAL }
data class ModelNativeFormStatus(val requestId: String, val state: ModelNativeFormState)

/** Implement in the native UI owner. Opening a form is not executing a mutation. The UI collects
 * credentials locally and calls AiProfileWriter only inside the approved lifecycle/CAS boundary.
 * This port deliberately never transports form values through the harness or tool journal. */
fun interface ModelNativeFormLauncher {
    suspend fun open(request: ModelNativeFormRequest)
}

/** This is a callback bridge, NOT an AiProfileWriter implementation. Integrator must call the
 * native callbacks only from its trusted UI controller, never expose them as router actions.
 * Epoch/ownership/PLAN checks must be repeated by authorize at opening AND submission. */
class ModelNativeFormBridge(
    private val launcher: ModelNativeFormLauncher,
    private val authorize: suspend (String) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val managementSnapshot: suspend () -> Pair<Boolean, Long> = { false to -1L },
    private val withNativeWriteBoundary: suspend (suspend () -> Unit) -> Unit = { error("MODEL_NATIVE_WRITE_BOUNDARY_MISSING") },
) {
    private data class Entry(val request: ModelNativeFormRequest, var state: ModelNativeFormState, val epoch: Long)
    private val lock = Mutex()
    private val entries = linkedMapOf<String, Entry>()

    suspend fun request(sourceSessionId: String, operation: ModelNativeFormOperation, modelId: String? = null): ModelNativeFormStatus {
        require(authorize(sourceSessionId)) { "MODEL_NATIVE_FORM_FORBIDDEN" }
        val snapshot = managementSnapshot()
        require(snapshot.first) { "MODEL_NATIVE_MANAGEMENT_DISABLED" }
        require((operation == ModelNativeFormOperation.CREATE) == (modelId == null))
        require(modelId == null || modelId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val request = ModelNativeFormRequest(UUID.randomUUID().toString(), sourceSessionId, operation, modelId, clock() + 300_000)
        lock.withLock {
            expire()
            // Never evict an executing request: its external effects may already have happened.
            entries.entries.removeAll { it.value.state in setOf(ModelNativeFormState.EXPIRED, ModelNativeFormState.CANCELLED, ModelNativeFormState.APPLIED) }
            check(entries.size < 128) { "MODEL_NATIVE_FORM_CAPACITY" }
            entries[request.requestId] = Entry(request, ModelNativeFormState.PENDING_NATIVE_FORM, snapshot.second)
        }
        try { launcher.open(request) }
        catch (e: Exception) {
            lock.withLock { entries[request.requestId]?.let { if (it.state == ModelNativeFormState.PENDING_NATIVE_FORM) it.state = ModelNativeFormState.CANCELLED } }
            throw e
        }
        return status(sourceSessionId, request.requestId)
    }

    suspend fun status(sourceSessionId: String, requestId: String): ModelNativeFormStatus = lock.withLock {
        expire()
        val entry = requireNotNull(entries[requestId])
        require(entry.request.sourceSessionId == sourceSessionId)
        ModelNativeFormStatus(requestId, entry.state)
    }

    /** Consume before any writer IO. Native UI must supply a real snapshot-CAS/lifecycle operation
     * as write; returning normally means confirmed completion, not merely queued work. Cancellation
     * or any failure after admission is UNKNOWN_PARTIAL and cannot be submitted a second time. */
    suspend fun submitFromNative(requestId: String, write: suspend (ModelNativeFormRequest) -> Unit): ModelNativeFormStatus {
        val request = lock.withLock {
            expire()
            val entry = requireNotNull(entries[requestId])
            check(entry.state == ModelNativeFormState.PENDING_NATIVE_FORM)
            entry.request
        }
        require(authorize(request.sourceSessionId)) { "MODEL_NATIVE_FORM_FORBIDDEN" }
        lock.withLock {
            expire()
            val entry = requireNotNull(entries[requestId])
            check(entry.state == ModelNativeFormState.PENDING_NATIVE_FORM)
            entry.state = ModelNativeFormState.EXECUTING
        }
        try {
            withNativeWriteBoundary {
                val live = managementSnapshot()
                val expectedEpoch = lock.withLock { entries.getValue(requestId).epoch }
                check(live.first && live.second == expectedEpoch && authorize(request.sourceSessionId)) {
                    "MODEL_NATIVE_AUTHORIZATION_CHANGED"
                }
                write(request)
                lock.withLock { entries.getValue(requestId).state = ModelNativeFormState.APPLIED }
            }
        } catch (failure: Throwable) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                lock.withLock { entries.getValue(requestId).state = ModelNativeFormState.UNKNOWN_PARTIAL }
            }
            throw failure
        }
        return status(request.sourceSessionId, requestId)
    }

    suspend fun cancelFromNative(sourceSessionId: String, requestId: String): ModelNativeFormStatus = lock.withLock {
        expire()
        val entry = requireNotNull(entries[requestId])
        require(entry.request.sourceSessionId == sourceSessionId)
        check(entry.state != ModelNativeFormState.EXECUTING) { "已开始写入，请核对结果，不能伪报取消" }
        if (entry.state == ModelNativeFormState.PENDING_NATIVE_FORM) entry.state = ModelNativeFormState.CANCELLED
        ModelNativeFormStatus(requestId, entry.state)
    }

    suspend fun invalidateSession(sourceSessionId: String) = lock.withLock {
        entries.values.filter { it.request.sourceSessionId == sourceSessionId && it.state == ModelNativeFormState.PENDING_NATIVE_FORM }
            .forEach { it.state = ModelNativeFormState.CANCELLED }
    }
    private fun expire() {
        entries.values.filter { it.state == ModelNativeFormState.PENDING_NATIVE_FORM && it.request.expiresAt <= clock() }
            .forEach { it.state = ModelNativeFormState.EXPIRED }
    }
}
