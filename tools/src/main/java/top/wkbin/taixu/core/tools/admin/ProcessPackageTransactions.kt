package top.wkbin.taixu.core.tools.admin

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.wkbin.taixu.core.tools.*
import top.wkbin.taixu.runtime.tools.InstallEvent

/** Concrete process-package transactions. Other package owners are composed, never simulated. */
class ProcessPackageTransactions(
    private val tools: ToolRepository, private val manager: ToolManager,
    private val scope: CoroutineScope, private val journal: PackageOperationJournal,
    private val other: PackageLifecycleTransactions? = null,
) : PackageLifecycleTransactions {
    private val rows = ConcurrentHashMap<String, PackageOperation>().also { map ->
        journal.restore().forEach { journal.save(it); map[it.id] = it }
    }
    private val jobs = ConcurrentHashMap<String, Job>()
    private fun record(row: PackageOperation) { journal.save(row); rows[row.id] = row }
    override suspend fun snapshot(module: String, id: String, target: String): PackageSnapshot {
        if (module !in setOf("process_plugin", "plugin")) return requireNotNull(other) { "PACKAGE_OWNER_NOT_COMPOSED" }.snapshot(module,id,target)
        val candidate = requireNotNull(tools.manifest(id)) { "PACKAGE_NOT_FOUND" }
        val installed = tools.findById(target,id)
        require(candidate.plugin?.execution != "WEB") { "USE_WEB_PACKAGE_OWNER" }
        val fingerprint = PackageAdministration.digest(Json.encodeToString(candidate))
        return PackageSnapshot(PackageMetadata(module,id,candidate.version,
            installed?.state in setOf("INSTALLED","UPDATE_AVAILABLE"),candidate.source == "BUILTIN",fingerprint),
            target,fingerprint,null,candidate.permissions.toSet(),candidate.source,emptySet(),
            installed?.installedVersion, installed?.state)
    }
    override suspend fun execute(owner: String, change: PackageChange, authorize: suspend () -> Boolean): PackageOperation {
        if (change.expected.metadata.module !in setOf("process_plugin", "plugin")) return requireNotNull(other) { "PACKAGE_OWNER_NOT_COMPOSED" }.execute(owner,change,authorize)
        require(owner.isNotBlank())
        require(change.action in setOf("install","update","remove"))
        val expected = change.expected
        val manifest = requireNotNull(tools.manifest(expected.metadata.id))
        check(PackageAdministration.digest(Json.encodeToString(manifest)) == expected.manifestDigest)
        require(change.desired == null || change.desired == expected) { "PACKAGE_CANDIDATE_MUST_BE_REVIEWED" }
        val id = UUID.randomUUID().toString()
        val queued = PackageOperation(id,owner,"queued",true,false)
        record(queued)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var effectsPossible = false
            try {
                val state = ApprovedPackageState(expected.installedVersion,expected.installationState)
                val authorizeUnderOwner: suspend () -> Boolean = {
                    check(authorize()) { "PACKAGE_APPROVAL_REVOKED" }
                    record(queued.copy(state="running")); effectsPossible = true; true
                }
                if (change.action == "remove") {
                    // ToolManager rechecks the installed receipt/manifest under the owner lock.
                    manager.uninstallApproved(expected.target,manifest.id,manifest,change.deleteData,state,authorizeUnderOwner)
                    record(queued.copy(state="completed"))
                } else {
                    var terminal = false
                    val events = if(change.action == "install") manager.installApproved(expected.target,manifest,state,authorizeUnderOwner)
                        else manager.updateApproved(expected.target,manifest,state,authorizeUnderOwner)
                    events.collect { event -> when(event) {
                        is InstallEvent.Completed -> { terminal=true; record(queued.copy(state="completed")) }
                        is InstallEvent.RolledBack -> { terminal=true; record(queued.copy(state="rolled_back",partial=true)) }
                        is InstallEvent.Cancelled -> { terminal=true; record(queued.copy(state="cancelled_or_partial",partial=effectsPossible)) }
                        is InstallEvent.Failed -> { terminal=true; record(queued.copy(state="failed_or_partial",partial=effectsPossible)) }
                        else -> Unit
                    } }
                    check(terminal) { "INSTALLER_FINISHED_WITHOUT_TERMINAL_EVENT" }
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { record(queued.copy(state="cancelled_or_partial",partial=effectsPossible)) }
                throw cancelled
            } catch (_: Exception) { record(queued.copy(state=if(effectsPossible) "unknown_partial" else "rejected",partial=effectsPossible)) }
            finally { jobs.remove(id) }
        }
        jobs[id] = job
        job.start()
        return queued
    }
    override suspend fun cancel(owner: String, operationId: String): PackageOperation? {
        val row = rows[operationId] ?: return other?.cancel(owner, operationId)
        require(row.owner == owner) { "PACKAGE_OPERATION_OWNER_MISMATCH" }
        val job = jobs[operationId] ?: return row // Interrupted history is queryable, never replayable.
        job.cancelAndJoin()
        return rows[operationId]
    }
    override suspend fun status(owner: String, operationId: String): PackageOperation? =
        rows[operationId]?.takeIf { it.owner == owner } ?: other?.status(owner,operationId)
}
