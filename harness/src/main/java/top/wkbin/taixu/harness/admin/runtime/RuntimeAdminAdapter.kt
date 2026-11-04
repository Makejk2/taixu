package top.wkbin.taixu.harness.admin.runtime

import kotlinx.coroutines.*
import top.wkbin.taixu.runtime.*
import top.wkbin.taixu.runtime.admin.*
import top.wkbin.taixu.runtime.terminal.TerminalSessionManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed interface RuntimeMutation {
    data class Install(val request: RuntimeInstallRequest) : RuntimeMutation
    data class Import(val request: RuntimeInstallRequest, val handle: NativeImportHandle) : RuntimeMutation
    data class Switch(val distro: String) : RuntimeMutation
    data class Update(val distro: String, val approvedDigest: String) : RuntimeMutation
    data class Uninstall(val distro: String) : RuntimeMutation
    data class Reset(val distro: String) : RuntimeMutation
}

data class RuntimeOperation(
    val id: String, val state: String, val outcome: Any? = null,
    val cleanupPending: Boolean = false,
)

/** Actual LinuxRuntime calls; no shell substitution, path heuristics or automatic replay.
 * App scope must outlive individual tool invocation. Owner CAS implementation is mandatory.
 */
class RuntimeAdminAdapter(
    private val runtime: LinuxRuntime,
    private val scope: CoroutineScope,
    private val journal: RuntimeOperationJournal,
) {
    private val operations = ConcurrentHashMap<String, RuntimeOperation>().also { map ->
        journal.recover().forEach { row -> journal.write(row); map[row.id] = row }
    }
    private fun record(row: RuntimeOperation) { journal.write(row); operations[row.id] = row }
    fun list() = runtime.installedDistros.value.toList()
    suspend fun health(target: String) = runtime.healthCheck(target)
    suspend fun checkUpdate(target: String) = runtime.checkRootfsUpdate(target)
    suspend fun snapshot(target: String, workspace: String) = runtime.managementSnapshot(target, workspace)
    fun status(id: String): RuntimeOperation? = operations[id]

    /** Gate must consume native approval BEFORE calling this. Returning queued is never success. */
    fun start(expected: DistroResourceSnapshot, mutation: RuntimeMutation): String =
        startGuarded(expected, mutation) { execute -> execute() }

    /** Native UI uses the same owner CAS, with the preferences/ownership boundary held in the
     * actual application job, not merely at the earlier enqueue/confirmation point. */
    internal fun startNativeImport(expected: DistroResourceSnapshot, request: RuntimeInstallRequest,
        handle: NativeImportHandle, boundary: suspend (suspend () -> Unit) -> Unit): String =
        startGuarded(expected, RuntimeMutation.Import(request, handle), boundary)

    private fun startGuarded(expected: DistroResourceSnapshot, mutation: RuntimeMutation,
        boundary: suspend (suspend () -> Unit) -> Unit): String {
        check(scope.isActive) { "RUNTIME_OPERATION_SCOPE_CLOSED" }
        val id = UUID.randomUUID().toString()
        record(RuntimeOperation(id, "queued"))
        // ATOMIC ensures finally owns the archive even if scope cancellation races dispatch.
        scope.launch(start = CoroutineStart.ATOMIC) {
            var finalState = "unknown_partial"
            var cleanupPending = false
            try {
                ensureActive()
                record(RuntimeOperation(id, "running"))
                val frozenMutation = when(mutation) {
                    is RuntimeMutation.Install -> ApprovedRuntimeMutation.Install(mutation.request)
                    is RuntimeMutation.Import -> ApprovedRuntimeMutation.Import(mutation.request, mutation.handle)
                    is RuntimeMutation.Switch -> ApprovedRuntimeMutation.Switch(mutation.distro)
                    is RuntimeMutation.Update -> ApprovedRuntimeMutation.Update(mutation.distro, mutation.approvedDigest)
                    is RuntimeMutation.Uninstall -> ApprovedRuntimeMutation.Uninstall(mutation.distro)
                    is RuntimeMutation.Reset -> ApprovedRuntimeMutation.Reset(mutation.distro)
                }
                boundary {
                    val outcome = runtime.applyManagement(expected, frozenMutation)
                    finalState = if (outcome.errorOrNull() == null) "finished" else "failed_or_partial"
                }
            } catch (e: AdminSnapshotChanged) {
                finalState = "snapshot_changed"
            } catch (e: CancellationException) {
                cleanupPending = true
                throw e
            } catch (_: Exception) {
                cleanupPending = true
            } finally {
                if (mutation is RuntimeMutation.Import) try { mutation.handle.close() }
                catch (_: Exception) { cleanupPending = true }
                // Publish terminal status only after archive cleanup, avoiding an optimistic poll.
                record(RuntimeOperation(id, finalState, cleanupPending = cleanupPending))
            }
        }
        return id
    }
}

/** Frozen resource set goes to owner transaction; never use closeAllSessions(), whose deleteAll
 * would also delete rows created after approval. No terminal output/history is exposed. */
class TerminalAdminAdapter(
    private val terminals: TerminalSessionManager,
) {
    data class Tab(val id: String, val label: String, val cwd: String, val distribution: String,
        val alive: Boolean, val foreground: Boolean)
    fun list() = terminals.handles.value.map {
        Tab(it.id, it.label, it.workingDirectory, it.distributionId, it.isAlive, terminals.activeId.value == it.id)
    }
    fun recovery() = terminals.restoreFailures.value.toList()
    suspend fun snapshot() = terminals.managementSnapshot()
    suspend fun create(expected: TerminalResourceSnapshot, label: String, cwd: String, distro: String) =
        terminals.createApproved(expected, label, cwd, distro).id
    suspend fun switch(expected: TerminalResourceSnapshot, id: String) = terminals.switchApproved(expected, id)
    suspend fun close(expected: TerminalResourceSnapshot, ids: Set<String>) = terminals.closeApproved(expected, ids)
    suspend fun recover(expected: TerminalResourceSnapshot) = terminals.recoverApproved(expected)
    suspend fun input(expected: TerminalResourceSnapshot, id: String, text: String) = terminals.inputApproved(expected, id, text)
}
