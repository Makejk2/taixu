package top.wkbin.taixu.core.tools.admin

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.wkbin.taixu.core.database.AgentSkillRepository
import top.wkbin.taixu.core.model.AgentSkill
import top.wkbin.taixu.core.tools.skill.SkillInstallationManager

/** Native document controller only. Credentials/URIs/paths never appear in the review DTO. */
class NativeReviewedSkillService(private val candidates: NativePackageCandidateStore,
    private val installer: SkillInstallationManager,private val skills: AgentSkillRepository,
    private val skillsDirectory: File,private val journal: PackageOperationJournal) {
    data class Review(val id: String,val owner: String,val packageId: String,val version: String,
        val archiveDigest: String,val permissions: List<String>,val replacesExisting: Boolean,val expiresAt: Long)
    private data class Entry(val view: Review,val candidate: String,val registration: AgentSkill?,
        val directoryDigest: String?,var call: String?=null)
    private val pending=ConcurrentHashMap<String,Entry>()
    private val maximumInspectionBytes=32*1024*1024L

    suspend fun inspect(owner: String,candidateToken: String): Review = candidates.inspect(owner,candidateToken) { meta,input ->
        require(meta.module=="skill" && meta.size<=maximumInspectionBytes) { "SKILL_INSPECTION_MEMORY_LIMIT" }
        val bytes=input.readNBytes((maximumInspectionBytes+1).toInt())
        require(bytes.size<=maximumInspectionBytes) { "SKILL_INSPECTION_MEMORY_LIMIT" }
        val inspection=installer.inspectZipBytes(bytes)
        check(inspection.canProceed) { "SKILL_SECURITY_BLOCKED" }
        skills.ensureInitialized()
        val registered=skills.allSkills.first().find { it.id=="custom_${inspection.pkg.manifest.id}" }
        require(registered?.isBuiltin!=true && registered?.isImmutable!=true)
        val path=File(skillsDirectory,inspection.pkg.manifest.id)
        require(path.canonicalFile.parentFile==skillsDirectory.canonicalFile)
        val view=Review(UUID.randomUUID().toString(),owner,inspection.pkg.manifest.id,inspection.pkg.manifest.version,
            meta.sha256,inspection.pkg.manifest.permissions.map { it.id },registered!=null,
            minOf(meta.expiresAt,System.currentTimeMillis()+120_000))
        pending[view.id]=Entry(view,candidateToken,registered,installer.directoryDigest(path))
        view
    }
    fun arm(owner: String,id: String,nativeCall: String): Review {
        val entry=requireNotNull(pending[id])
        synchronized(entry) {
            check(entry.view.owner==owner && entry.view.expiresAt>System.currentTimeMillis())
            check(entry.call==null || entry.call==nativeCall);entry.call=nativeCall;return entry.view
        }
    }
    suspend fun commitNative(owner: String,id: String,nativeCall: String,authorize: suspend () -> Boolean): PackageOperation {
        val entry=requireNotNull(pending[id])
        synchronized(entry) {
            check(entry.view.owner==owner && entry.call==nativeCall && entry.view.expiresAt>System.currentTimeMillis())
            check(pending.remove(id,entry))
        }
        val row=PackageOperation(UUID.randomUUID().toString(),owner,"queued",true,false)
        journal.save(row);var effects=false
        return try {
            candidates.consume(owner,entry.candidate,entry.view.archiveDigest) { input ->
                val bounded=input.readNBytes((maximumInspectionBytes+1).toInt())
                require(bounded.size<=maximumInspectionBytes)
                val inspection=installer.inspectZipBytes(bounded)
                check(inspection.pkg.manifest.id==entry.view.packageId && inspection.canProceed)
                installer.commitApproved(inspection,skillsDirectory,"/attachments/skills",entry.directoryDigest,
                    authorize={check(authorize());journal.save(row.copy(state="running"));effects=true;true},
                    expectedRegistration=entry.registration)
            }
            row.copy(state="completed").also(journal::save)
        } catch(failure: Throwable) {
            journal.save(row.copy(state=if(effects) "unknown_partial" else "rejected",partial=effects))
            throw failure
        }
    }
}
