package top.wkbin.taixu.harness.admin.model

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import top.wkbin.taixu.core.database.*

/** Construct only from the native operation journal/ManagementActorGuard, never JSON. */
data class ModelAdminActor(val sessionId: String, val operationId: String, val callId: String)

/** Must re-check real current user session, main lane and run mode on EVERY invocation/replay. */
interface ModelAdminAuthority {
    suspend fun isControlledUserSession(actor: ModelAdminActor): Boolean
    suspend fun isPlan(actor: ModelAdminActor): Boolean
}

sealed interface FieldEdit<out T> {
    data object Keep : FieldEdit<Nothing>
    data class Set<T>(val value: T?) : FieldEdit<T>
}

data class ModelProfilePatch(
    val temperature: FieldEdit<Float> = FieldEdit.Keep,
    val maxTokens: FieldEdit<Int> = FieldEdit.Keep,
    val topP: FieldEdit<Float> = FieldEdit.Keep,
    val reasoningMode: FieldEdit<String> = FieldEdit.Keep,
    val reasoningEffort: FieldEdit<String> = FieldEdit.Keep,
) {
    private fun <T> FieldEdit<T>.merge(old: T?): T? = when (this) {
        FieldEdit.Keep -> old
        is FieldEdit.Set -> value
    }
    fun apply(old: ModelSettings) = ModelSettings(temperature.merge(old.temperature),
        maxTokens.merge(old.maxTokens), topP.merge(old.topP), reasoningMode.merge(old.reasoningMode),
        reasoningEffort.merge(old.reasoningEffort)).also { it.validate() }
}

sealed interface ModelAdminCommand {
    data class Patch(val modelId: String, val patch: ModelProfilePatch) : ModelAdminCommand
    data class SetGlobalDefault(val modelId: String) : ModelAdminCommand
    data class SelectForCurrentSession(val modelId: String) : ModelAdminCommand
}

data class ModelProposalView(val proposalId: String, val operation: String, val targetId: String, val expiresAt: Long,
    val proposedSettings: ModelSettings? = null, val willResetVariant: Boolean = false)
data class ModelCatalog(val models: List<ManagedModel>, val currentSessionModelId: String?)
sealed interface ModelAdminResult {
    data class Catalog(val value: ModelCatalog) : ModelAdminResult
    data class Profile(val value: ManagedModel) : ModelAdminResult
    data class Proposal(val value: ModelProposalView) : ModelAdminResult
    data class ApprovalRequired(val value: ModelProposalView) : ModelAdminResult
    data object Applied : ModelAdminResult
    data class Rejected(val code: String) : ModelAdminResult
}

/** One-use approval bookkeeping only. Persistent concurrency correctness belongs to Room CAS. */
class ModelAdminService(
    private val repository: ModelManagementRepository,
    private val authority: ModelAdminAuthority,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = 120_000,
    private val audit: suspend (ModelAdminActor, String, String) -> Unit,
) {
    init { require(ttlMillis in 1..300_000) }
    private data class Pending(val session: String, val operation: String, val mutation: ModelMutation,
        val digest: String, val view: ModelProposalView, val applyCall: String? = null)
    private val pending = ConcurrentHashMap<String, Pending>()

    private suspend fun guard(actor: ModelAdminActor, write: Boolean = false) {
        require(authority.isControlledUserSession(actor))
        if (write && authority.isPlan(actor)) {
            invalidateSession(actor.sessionId)
            error("plan")
        }
    }
    private fun prune() { val now = clock(); pending.entries.removeIf { it.value.view.expiresAt <= now } }
    suspend fun list(actor: ModelAdminActor): ModelAdminResult = safe {
        guard(actor)
        val s = repository.snapshot(actor.sessionId)
        ModelAdminResult.Catalog(ModelCatalog(s.models, s.sessionModelId))
    }
    suspend fun get(actor: ModelAdminActor, id: String): ModelAdminResult = safe {
        guard(actor)
        ModelAdminResult.Profile(repository.snapshot(actor.sessionId).models.single { it.id == id })
    }
    suspend fun propose(actor: ModelAdminActor, command: ModelAdminCommand): ModelAdminResult = safe {
        guard(actor, true)
        prune()
        require(pending.size < 256)
        val s = repository.snapshot(actor.sessionId)
        val mutation = when (command) {
            is ModelAdminCommand.Patch -> ModelMutation.Settings(command.modelId,
                command.patch.apply(s.models.single { it.id == command.modelId }.settings))
            is ModelAdminCommand.SetGlobalDefault -> ModelMutation.GlobalDefault(command.modelId)
            is ModelAdminCommand.SelectForCurrentSession -> ModelMutation.SessionSelection(command.modelId)
        }
        require(s.models.any { it.id == mutation.modelId })
        val operation = when (mutation) {
            is ModelMutation.Settings -> "patch"
            is ModelMutation.GlobalDefault -> "global_default"
            is ModelMutation.SessionSelection -> "current_session"
        }
        val view = ModelProposalView(UUID.randomUUID().toString(), operation, mutation.modelId, clock() + ttlMillis,
            (mutation as? ModelMutation.Settings)?.value,
            mutation is ModelMutation.SessionSelection && s.sessionModelId != mutation.modelId && s.sessionVariant != null)
        pending[view.proposalId] = Pending(actor.sessionId, actor.operationId, mutation, s.digest, view)
        ModelAdminResult.Proposal(view)
    }
    /** Invoke before generic grants/FULL_ACCESS; no API accepts approval from tool JSON. */
    internal suspend fun prepare(actor: ModelAdminActor, id: String): ModelAdminResult = safe {
        guard(actor, true)
        prune()
        val p = requireNotNull(pending[id])
        require(p.session == actor.sessionId && p.operation == actor.operationId)
        require(p.applyCall == null || p.applyCall == actor.callId)
        if (repository.snapshot(actor.sessionId).digest != p.digest) {
            pending.remove(id, p)
            return@safe ModelAdminResult.Rejected("MODEL_SNAPSHOT_CHANGED")
        }
        require(pending.replace(id, p, p.copy(applyCall = actor.callId)))
        ModelAdminResult.ApprovalRequired(p.view)
    }
    /** Only native replay with same call/session/operation may enter. Remove BEFORE any IO. */
    internal suspend fun applyNative(actor: ModelAdminActor, id: String): ModelAdminResult = safe {
        guard(actor, true)
        val p = requireNotNull(pending[id])
        require(p.session == actor.sessionId && p.operation == actor.operationId && p.applyCall == actor.callId)
        require(pending.remove(id, p))
        require(clock() < p.view.expiresAt)
        // No mutation starts unless durable intent succeeded. Completion failures must not
        // masquerade as a side-effect-free rejection; the proposal is already consumed.
        audit(actor, "model_intent", p.view.operation)
        try {
            val applied = repository.compareAndSet(actor.sessionId, p.digest, p.mutation)
            audit(actor, "model_completion", if (applied) "applied" else "snapshot_changed")
            if (applied) ModelAdminResult.Applied else ModelAdminResult.Rejected("MODEL_SNAPSHOT_CHANGED")
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            ModelAdminResult.Rejected("MODEL_UNKNOWN_PARTIAL")
        }
    }
    fun invalidateSession(session: String) { pending.entries.removeIf { it.value.session == session } }
    fun disable() { pending.clear() }

    private suspend fun safe(block: suspend () -> ModelAdminResult): ModelAdminResult = try { block() }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { ModelAdminResult.Rejected("MODEL_ADMIN_REJECTED") }
}
