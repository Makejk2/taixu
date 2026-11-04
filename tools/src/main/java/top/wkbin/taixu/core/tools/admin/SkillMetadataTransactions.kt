package top.wkbin.taixu.core.tools.admin

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.wkbin.taixu.core.database.AgentSkillRepository

/** Metadata operations use the repository's native writer lock and Room row CAS. */
class SkillMetadataTransactions(private val skills: AgentSkillRepository,
    private val journal: PackageOperationJournal,
    private val installer: top.wkbin.taixu.core.tools.skill.SkillInstallationManager,
    private val installedRoot: java.io.File,
    private val other: PackageLifecycleTransactions? = null) : PackageLifecycleTransactions {
    private val rows = ConcurrentHashMap<String, PackageOperation>().also { map ->
        journal.restore().forEach { journal.save(it); map[it.id] = it }
    }
    private fun record(row: PackageOperation) { journal.save(row); rows[row.id] = row }
    override suspend fun snapshot(module: String, id: String, target: String): PackageSnapshot {
        if (module != "skill") return requireNotNull(other) { "PACKAGE_OWNER_NOT_COMPOSED" }.snapshot(module,id,target)
        skills.ensureInitialized()
        val row = requireNotNull(skills.allSkills.first().find { it.id == id }) { "SKILL_NOT_FOUND" }
        val fingerprint = PackageAdministration.digest(Json.encodeToString(row))
        return PackageSnapshot(PackageMetadata("skill",id,null,row.isEnabled,row.isBuiltin,fingerprint),
            target,fingerprint,row.resourcePath?.let { installer.directoryDigest(java.io.File(it)) },emptySet(),if(row.isBuiltin) "BUILTIN" else "CUSTOM",emptySet())
    }
    override suspend fun execute(owner: String, change: PackageChange, authorize: suspend () -> Boolean): PackageOperation {
        if (change.expected.metadata.module != "skill" || change.action !in setOf("enable","disable","remove"))
            return requireNotNull(other) { "SKILL_PACKAGE_INSTALL_OWNER_NOT_COMPOSED" }.execute(owner,change,authorize)
        require(!change.deleteData || change.action == "remove")
        require(owner.isNotBlank())
        skills.ensureInitialized()
        val skill = requireNotNull(skills.allSkills.first().find { it.id == change.expected.metadata.id })
        check(PackageAdministration.digest(Json.encodeToString(skill)) == change.expected.manifestDigest)
        val row = PackageOperation(UUID.randomUUID().toString(),owner,"queued",true,false)
        record(row)
        var effectsPossible = false
        val auth: suspend () -> Boolean = {
            check(authorize()) { "PACKAGE_APPROVAL_REVOKED" }
            record(row.copy(state="running")); effectsPossible=true; true
        }
        return try {
            val changed = if(change.action == "remove" && change.deleteData)
                installer.removeApproved(skill, change.expected.packageDigest, installedRoot, auth)
                else if(change.action == "remove") skills.deleteApproved(skill,auth)
                else skills.setEnabledApproved(skill,change.action == "enable",auth)
            check(changed) { "SKILL_REVISION_CHANGED" }
            row.copy(state="completed").also(::record)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { record(row.copy(state="unknown_partial",partial=effectsPossible)) }
            throw cancelled
        } catch (_: Exception) { row.copy(state=if(effectsPossible) "unknown_partial" else "rejected",partial=effectsPossible).also(::record) }
    }
    override suspend fun status(owner: String, operationId: String) =
        rows[operationId]?.takeIf { it.owner == owner } ?: other?.status(owner,operationId)
    override suspend fun cancel(owner: String, operationId: String): PackageOperation? {
        val row = rows[operationId] ?: return other?.cancel(owner,operationId)
        require(row.owner == owner)
        return row // Synchronous metadata commits have no replayable background job.
    }
}
