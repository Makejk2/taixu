package top.wkbin.taixu.harness.admin.runtime

import kotlinx.coroutines.CoroutineScope
import top.wkbin.taixu.harness.admin.model.ModelAdminAuthority
import top.wkbin.taixu.harness.admin.model.ModelAdminActor
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.RuntimeInstallRequest
import top.wkbin.taixu.runtime.admin.*
import top.wkbin.taixu.runtime.terminal.TerminalSessionManager

class RuntimeAdminComponents(
    val backend: RuntimeAdminBackend,
    val gate: RuntimeAdminGate,
    val distro: RuntimeAdminAdapter,
    private val workspace: suspend (String) -> String,
    private val authorityState: RuntimeManagementState,
) {
    fun setManagementSnapshot(snapshot: Pair<Boolean, Long>) {
        if (authorityState.enabled != snapshot.first || authorityState.revision != snapshot.second) gate.invalidate()
        authorityState.revision = snapshot.second; authorityState.enabled = snapshot.first
    }
    fun disableManagement() { authorityState.enabled = false; gate.invalidate() }
    /** Native provenance is checked by NativeRuntimeImportController, never a fabricated actor. */
    internal suspend fun nativeImportSnapshot(owner: String, target: String): DistroResourceSnapshot {
        require(top.wkbin.taixu.runtime.DistributionCatalog.supported.any { it.id == target })
        return distro.snapshot(target, workspace(owner)).let {
            it.copy(rootfsDigests = it.rootfsDigests.toMap(), installedIds = it.installedIds.toSet())
        }
    }
    /** Native UI completion is typed; selected is only a proposal, never imported/completed. */
    suspend fun proposeImport(actor: ModelAdminActor, request: RuntimeInstallRequest,
        handle: NativeImportHandle) : RuntimeAdminGate.Preview {
        val frozen = distro.snapshot(request.distributionId, workspace(actor.sessionId)).let { it.copy(rootfsDigests = it.rootfsDigests.toMap(), installedIds = it.installedIds.toSet()) }
        require(frozen.targetId == request.distributionId)
        return gate.propose(actor, "distro", "import",
            "Import ${handle.sizeBytes} bytes, sha256=${handle.sha256}, target=${frozen.targetId}, workspace=${frozen.workspaceId}. Native archive handle retained until execution.") {
            distro.start(frozen, RuntimeMutation.Import(request, handle))
        }
    }
}
fun createRuntimeAdmin(
    runtime: LinuxRuntime, terminals: TerminalSessionManager,
    scope: CoroutineScope, authority: ModelAdminAuthority, workspace: suspend (String) -> String, journal: RuntimeOperationJournal,
): RuntimeAdminComponents {
    val distro = RuntimeAdminAdapter(runtime, scope, journal)
    val terminal = TerminalAdminAdapter(terminals)
    val state = RuntimeManagementState()
    val gate = RuntimeAdminGate(authority, { state.revision }, { state.enabled })
    return RuntimeAdminComponents(RuntimeAdminBackend(distro, terminal, gate, authority, workspace), gate, distro, workspace, state)
}

class RuntimeManagementState {
    @Volatile var enabled: Boolean = false
    @Volatile var revision: Long = -1
}
