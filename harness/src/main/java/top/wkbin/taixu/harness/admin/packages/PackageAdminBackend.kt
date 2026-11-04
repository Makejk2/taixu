package top.wkbin.taixu.harness.admin.packages

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.wkbin.taixu.core.tools.admin.*

data class PackageAdminActor(val sessionId: String, val operationId: String)
interface PackageAdminAuthority {
    suspend fun isNativeMainBuild(actor: PackageAdminActor): Boolean
}
/** Deliberately native-only: do not deserialize an approval, epoch or actor from tool arguments. */
data class PackageAdminProposal internal constructor(val id: String, val actor: PackageAdminActor,
    val epoch: Long, val change: PackageChange, val expiresAt: Long,
    var applyCallId: String? = null, var applyOperationId: String? = null, val cancelOperationId: String? = null)
class PackageAdminBackend internal constructor(private val api: PackageAdministration,
    private val authority: PackageAdminAuthority) {
    private val gate = Mutex()
    @Volatile private var epoch = 0L
    @Volatile private var enabled = false
    private val proposals = mutableMapOf<String, PackageAdminProposal>()
    suspend fun setManagementEnabled(value: Boolean) = gate.withLock {
        if (enabled != value) { enabled = value; epoch++; proposals.clear() }
    }
    suspend fun disableManagement() = setManagementEnabled(false)
    suspend fun list(module: String) = api.list(module)
    suspend fun status(actor: PackageAdminActor, operationId: String): PackageOperation? {
        check(authority.isNativeMainBuild(actor)); return api.transactions.status(actor.sessionId, operationId)
    }
    suspend fun propose(actor: PackageAdminActor, action: String, module: String, id: String,
        target: String, desired: PackageSnapshot? = null, deleteData: Boolean = false): PackageAdminProposal {
        check(authority.isNativeMainBuild(actor))
        require(module in setOf("plugin", "process_plugin", "web_plugin", "skill"))
        require(action in if (module != "skill") setOf("install", "update", "remove", "sync")
            else setOf("install", "update", "remove", "enable", "disable", "sync"))
        require(action != "sync" || (id == "native-sync" && target == "native-config" && desired == null))
        val snapshot = api.transactions.snapshot(module, id, target)
        require(snapshot.metadata.module == module && snapshot.metadata.id == id && snapshot.target == target)
        require(!deleteData || action == "remove")
        return gate.withLock {
            check(enabled)
            PackageAdminProposal(UUID.randomUUID().toString(), actor, epoch,
                PackageChange(action, snapshot, desired, deleteData), System.currentTimeMillis() + 120_000).also { proposals[it.id] = it }
        }
    }
    suspend fun proposeCancel(actor: PackageAdminActor, operationId: String): PackageAdminProposal {
        check(authority.isNativeMainBuild(actor))
        val operation=requireNotNull(api.transactions.status(actor.sessionId,operationId)) { "PACKAGE_OPERATION_NOT_FOUND" }
        require(operation.owner==actor.sessionId)
        return gate.withLock {
            check(enabled)
            val snapshot=PackageSnapshot(PackageMetadata("operation",operation.id,null,false,false,operation.state),
                actor.sessionId,operation.state,null,emptySet(),"NATIVE_OPERATION",emptySet())
            PackageAdminProposal(UUID.randomUUID().toString(),actor,epoch,PackageChange("cancel",snapshot),
                System.currentTimeMillis()+120_000,cancelOperationId=operationId).also { proposals[it.id]=it }
        }
    }
    suspend fun prepare(actor: PackageAdminActor, id: String, callId: String): PackageAdminProposal {
        check(authority.isNativeMainBuild(actor))
        return gate.withLock {
            val p = requireNotNull(proposals[id]) { "PROPOSAL_EXPIRED" }
            check(enabled && p.epoch == epoch && p.actor.sessionId == actor.sessionId)
            if (p.expiresAt <= System.currentTimeMillis() || (if(p.cancelOperationId!=null)
                api.transactions.status(actor.sessionId,p.cancelOperationId)?.state != p.change.expected.manifestDigest
                else api.transactions.snapshot(p.change.expected.metadata.module,p.change.expected.metadata.id,p.change.expected.target) != p.change.expected)) {
                proposals.remove(id); error("PACKAGE_PROPOSAL_EXPIRED_OR_CHANGED")
            }
            check(p.applyCallId == null || (p.applyCallId == callId && p.applyOperationId == actor.operationId))
            p.applyCallId = callId; p.applyOperationId = actor.operationId; p
        }
    }

    /** Native approved replay consumes before effects; failures cannot rearm a proposal. */
    suspend fun approveNative(actor: PackageAdminActor, id: String, callId: String): PackageOperation {
        check(authority.isNativeMainBuild(actor))
        val proposal = gate.withLock {
            val p = requireNotNull(proposals[id]) { "PROPOSAL_EXPIRED" }
            check(enabled && p.epoch == epoch && p.actor.sessionId == actor.sessionId &&
                p.applyCallId == callId && p.applyOperationId == actor.operationId)
            proposals.remove(id)
            check(p.expiresAt > System.currentTimeMillis()); p
        }
        if (proposal.cancelOperationId != null) {
            check(enabled && epoch == proposal.epoch && authority.isNativeMainBuild(actor))
            return requireNotNull(api.transactions.cancel(actor.sessionId,proposal.cancelOperationId))
        }
        return api.transactions.execute(actor.sessionId,proposal.change) {
            // Never reacquire the control-plane mutex while inside the lifecycle owner lock.
            authority.isNativeMainBuild(actor) && enabled && epoch == proposal.epoch
        }
    }
    suspend fun cancel(actor: PackageAdminActor, operationId: String): PackageOperation? {
        check(authority.isNativeMainBuild(actor)); return api.transactions.cancel(actor.sessionId, operationId)
    }
}
object PackageAdminFactory {
    fun create(api: PackageAdministration, authority: PackageAdminAuthority) = PackageAdminBackend(api, authority)
}
