package top.wkbin.taixu.core.tools.admin

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.wkbin.taixu.core.model.ToolManifest
import top.wkbin.taixu.core.tools.ToolRegistry

/** Native controller owns this service; none of its approval methods are model-router actions.
 * Opening/inspecting a document only creates a review. Actual immutable commit needs the
 * armed native call plus an independently revalidated authorization epoch. */
class NativeReviewedPluginService(
    private val candidates: NativePackageCandidateStore,
    private val registry: ToolRegistry,
    private val journal: PackageOperationJournal,
) {
    data class Review(val id: String, val owner: String, val module: String,
        val packageId: String, val version: String, val archiveDigest: String,
        val archiveSize: Long, val addedPermissions: Set<String>, val expiresAt: Long)
    private data class Entry(val view: Review, val candidate: String, val manifest: ToolManifest,
        val previousDigest: String?, var nativeCall: String? = null)
    private val pending = ConcurrentHashMap<String,Entry>()

    suspend fun inspect(owner: String, candidateToken: String): Review =
        candidates.inspect(owner,candidateToken) { metadata,input ->
            require(metadata.module in setOf("process_plugin","web_plugin"))
            val execution=if(metadata.module == "web_plugin") "WEB" else "PROCESS"
            val manifest=registry.inspectReviewedStream(input,execution,metadata.size)
            val previous=(registry.load()+registry.webPackages()).find { it.id == manifest.id }
            val previousDigest=previous?.let { PackageAdministration.digest(Json.encodeToString(it)) }
            val view=Review(UUID.randomUUID().toString(),owner,metadata.module,manifest.id,manifest.version,
                metadata.sha256,metadata.size,manifest.permissions.toSet()-previous?.permissions.orEmpty().toSet(),
                minOf(metadata.expiresAt,System.currentTimeMillis()+120_000))
            pending[view.id]=Entry(view,candidateToken,manifest,previousDigest)
            view
        }

    fun arm(owner: String, reviewId: String, nativeCallId: String): Review {
        val entry=requireNotNull(pending[reviewId])
        synchronized(entry) {
            require(entry.view.owner==owner && entry.view.expiresAt>System.currentTimeMillis())
            require(entry.nativeCall==null || entry.nativeCall==nativeCallId)
            entry.nativeCall=nativeCallId
            return entry.view
        }
    }

    suspend fun commitNative(owner: String, reviewId: String, nativeCallId: String,
        authorize: suspend () -> Boolean): PackageOperation {
        val entry=requireNotNull(pending[reviewId])
        synchronized(entry) {
            require(entry.view.owner==owner && entry.nativeCall==nativeCallId)
            require(entry.view.expiresAt>System.currentTimeMillis())
            check(pending.remove(reviewId,entry))
        }
        val row=PackageOperation(UUID.randomUUID().toString(),owner,"queued",true,false)
        journal.save(row)
        var effectPossible=false
        return try {
            candidates.consume(owner,entry.candidate,entry.view.archiveDigest) { stream ->
                // The importer opens this private, already verified stream exactly once.
                var opened=false
                registry.importReviewedStream(open={ check(!opened);opened=true;stream },
                    size=entry.view.archiveSize,expected=entry.manifest,
                    execution=if(entry.view.module=="web_plugin") "WEB" else "PROCESS",
                    selectedDigest=entry.previousDigest,authorize={
                        check(authorize());journal.save(row.copy(state="running"));effectPossible=true;true
                    })
            }
            row.copy(state="completed").also(journal::save)
        } catch(failure: Throwable) {
            journal.save(row.copy(state=if(effectPossible) "unknown_partial" else "rejected",partial=effectPossible))
            throw failure
        }
    }
}
