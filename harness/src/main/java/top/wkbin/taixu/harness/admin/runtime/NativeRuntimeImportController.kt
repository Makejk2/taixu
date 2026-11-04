package top.wkbin.taixu.harness.admin.runtime

import android.content.ContentResolver
import android.net.Uri
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.wkbin.taixu.core.database.HarnessSessionRepository
import top.wkbin.taixu.core.database.repository.BotAssignmentRepository
import top.wkbin.taixu.core.database.repository.PersonaBotRepository
import top.wkbin.taixu.core.datastore.AiAdminPreferences
import top.wkbin.taixu.harness.admin.NativeSessionOwnership
import top.wkbin.taixu.runtime.RuntimeInstallRequest
import top.wkbin.taixu.runtime.admin.*

/** Singleton native controller, not a tool backend. No model actor, path, approval string or URI
 * is accepted through any tool route. Mirrors NativeManagementQueue's private handler/metadata
 * pattern, with separate RUNNING -> operation status tracking (enqueue is never APPLIED). */
class NativeRuntimeImportController(
    private val resolver: ContentResolver,
    private val archives: PrivateNativeImportStore,
    private val components: RuntimeAdminComponents,
    private val sessions: HarnessSessionRepository,
    private val ownership: NativeSessionOwnership,
    private val bots: PersonaBotRepository,
    private val assignments: BotAssignmentRepository,
    private val preferences: AiAdminPreferences,
    private val scope: CoroutineScope,
) {
    class Selection internal constructor(
        internal val owner: String, internal val epoch: Long,
        internal val snapshot: DistroResourceSnapshot,
    ) {
        internal val consumed = java.util.concurrent.atomic.AtomicBoolean(false)
    }
    data class Review(
        val id: String, val sourceSessionId: String, val epoch: Long,
        val snapshot: DistroResourceSnapshot, val sha256: String, val sizeBytes: Long,
        val expiresAt: Long, val state: String = "pending_native_confirmation",
        val operationId: String? = null, val cleanupPending: Boolean = false,
    )
    private val gate = NativeRuntimeImportGate()
    private val lock = Mutex()
    private val mutableReviews = MutableStateFlow<List<Review>>(emptyList())
    val reviews: StateFlow<List<Review>> = mutableReviews.asStateFlow()

    private suspend fun authorize(owner: String) {
        require(!owner.startsWith("workflow:") && !owner.startsWith("subagent:"))
        require(ownership.owns(owner))
        require(sessions.findById(owner)?.runMode == "build")
        require(assignments.assignmentForSession(owner) == null && bots.getBotBySessionId(owner) == null)
        require(owner !in bots.observeGroupSessionRefs().first())
    }
    private suspend fun <T> boundary(owner: String, epoch: Long, block: suspend () -> T): T =
        preferences.withManagementSnapshot { state ->
            require(state.first && state.second == epoch) { "NATIVE_IMPORT_AUTHORIZATION_CHANGED" }
            authorize(owner)
            block()
        }

    /** Capture source, prefs epoch and full owner snapshot BEFORE launching OpenDocument. */
    suspend fun beginSelectionFromNative(owner: String, target: String): Selection =
        preferences.withManagementSnapshot { state ->
            require(state.first)
            authorize(owner)
            Selection(owner, state.second, components.nativeImportSnapshot(owner, target))
        }

    /** Only an actual ActivityResultContracts.OpenDocument callback supplies this content URI. */
    suspend fun selectedDocumentFromNative(selection: Selection, uri: Uri) {
        require(uri.scheme == "content")
        check(selection.consumed.compareAndSet(false, true))
        check(scope.isActive) { "NATIVE_IMPORT_SCOPE_CLOSED" }
        boundary(selection.owner, selection.epoch) {
            require(components.nativeImportSnapshot(selection.owner, selection.snapshot.targetId) == selection.snapshot)
        }
        var staged: NativeImportHandle? = null
        var transferred = false
        try {
            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                staged = archives.capture({ requireNotNull(resolver.openInputStream(uri)) }, { context.ensureActive() })
            }
            val handle = requireNotNull(staged)
            lock.withLock {
                boundary(selection.owner, selection.epoch) {
                    require(components.nativeImportSnapshot(selection.owner, selection.snapshot.targetId) == selection.snapshot)
                    check(mutableReviews.value.size < 32)
                    val review = Review(UUID.randomUUID().toString(), selection.owner, selection.epoch,
                        selection.snapshot, handle.sha256, handle.sizeBytes, System.currentTimeMillis() + 120000)
                    gate.propose(review.id, selection.owner, selection.epoch, review.expiresAt,
                        execute = {
                            components.distro.startNativeImport(selection.snapshot,
                                RuntimeInstallRequest(selection.snapshot.targetId), handle) { execute ->
                                boundary(selection.owner, selection.epoch) {
                                    require(components.nativeImportSnapshot(selection.owner, selection.snapshot.targetId) == selection.snapshot)
                                    execute() // LinuxRuntime.applyManagement owns final lifecycle CAS.
                                }
                            }
                        }, release = { handle.close() })
                    transferred = true
                    mutableReviews.value += review
                    scope.launch(start = CoroutineStart.ATOMIC) {
                        try {
                            delay((review.expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
                        } finally {
                            withContext(NonCancellable + Dispatchers.IO) {
                                lock.withLock {
                                    if (mutableReviews.value.any { it.id == review.id && it.operationId == null }) {
                                        release(review.id, "expired")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            if (!transferred) withContext(NonCancellable + Dispatchers.IO) { staged?.close() }
        }
    }

    /** Called by an explicit native confirmation button only, not a JSON boolean/string. */
    suspend fun confirmFromNative(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val review = mutableReviews.value.singleOrNull { it.id == id } ?: return@withLock
            if (review.state != "pending_native_confirmation") return@withLock
            try {
                val operation = boundary(review.sourceSessionId, review.epoch) {
                    require(components.nativeImportSnapshot(review.sourceSessionId, review.snapshot.targetId) == review.snapshot)
                    gate.confirmFromNative(id, review.sourceSessionId, review.epoch)
                }
                update(id) { it.copy(state = "queued", operationId = operation) }
                // App-scoped monitoring survives navigation; operation journal survives process death.
                scope.launch {
                    while (isActive) {
                        val status = components.distro.status(operation)
                        lock.withLock {
                            update(id) { it.copy(state = status?.state ?: "unknown_partial",
                                cleanupPending = status?.cleanupPending ?: true) }
                        }
                        if (status == null || status.state !in setOf("queued", "running")) break
                        delay(500)
                    }
                }
            } catch (failure: Throwable) {
                withContext(NonCancellable) { release(id, "not_applied_authorization_or_snapshot_changed") }
                if (failure is CancellationException) throw failure
            }
        }
    }
    suspend fun cancelFromNative(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val review = mutableReviews.value.singleOrNull { it.id == id } ?: return@withLock
            if (review.operationId == null) release(id, "cancelled")
        }
    }
    suspend fun dismissFromNative(id: String) = lock.withLock {
        val review = mutableReviews.value.singleOrNull { it.id == id } ?: return@withLock
        require(review.state !in setOf("pending_native_confirmation", "queued", "running") && !review.cleanupPending)
        mutableReviews.value = mutableReviews.value.filterNot { it.id == id }
    }
    private fun release(id: String, state: String) {
        try { gate.discard(id); update(id) { it.copy(state = state, cleanupPending = false) } }
        catch (_: Exception) { update(id) { it.copy(state = "cleanup_pending", cleanupPending = true) } }
    }
    private fun update(id: String, transform: (Review) -> Review) {
        mutableReviews.value = mutableReviews.value.map { if (it.id == id) transform(it) else it }
    }
}
