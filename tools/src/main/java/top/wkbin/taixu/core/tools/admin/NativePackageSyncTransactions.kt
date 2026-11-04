package top.wkbin.taixu.core.tools.admin

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.wkbin.taixu.core.database.AgentSkillRepository
import top.wkbin.taixu.core.database.SkillScanRoot
import top.wkbin.taixu.core.datastore.RegistryPreferences
import top.wkbin.taixu.core.tools.SignedRegistryRequest
import top.wkbin.taixu.core.tools.ToolRegistry

/** Native settings and native root resolver only. Never deserialize these from tool arguments. */
class NativePackageSyncTransactions(
    private val registry: ToolRegistry,
    private val skills: AgentSkillRepository,
    private val preferences: RegistryPreferences?,
    private val nativeRoots: suspend () -> List<SkillScanRoot>,
    private val scope: CoroutineScope,
    private val journal: PackageOperationJournal,
    private val other: PackageLifecycleTransactions,
) : PackageLifecycleTransactions {
    private data class Frozen(val request: SignedRegistryRequest?, val roots: List<SkillScanRoot>, val snapshot: PackageSnapshot)
    private val gate = Mutex()
    private val previewLock = Any()
    private val frozen = ConcurrentHashMap<String, Frozen>()
    private val rows = ConcurrentHashMap<String, PackageOperation>().also { map ->
        journal.restore().forEach { journal.save(it); map[it.id] = it }
    }
    private val jobs = ConcurrentHashMap<String, Job>()
    private fun record(row: PackageOperation) { journal.save(row); rows[row.id] = row }
    private suspend fun config(module: String): Pair<SignedRegistryRequest?, List<SkillScanRoot>> = when (module) {
        "plugin", "process_plugin" -> {
            val settings = requireNotNull(preferences) { "NATIVE_REGISTRY_SETTINGS_UNAVAILABLE" }
            val request = SignedRegistryRequest(settings.manifestUrl.first(), settings.signatureUrl.first(), settings.publicKey.first())
            require(request.manifestUrl.startsWith("https://") && request.signatureUrl.startsWith("https://") && request.publicKeyBase64.isNotBlank())
            request to emptyList()
        }
        "skill" -> {
            val roots = nativeRoots().map { SkillScanRoot(it.hostDir.canonicalFile, it.guestPrefix) }.toList()
            require(roots.isNotEmpty()) { "NATIVE_SKILL_ROOTS_UNAVAILABLE" }
            require(roots.distinctBy { it.hostDir.path to it.guestPrefix }.size == roots.size)
            null to roots
        }
        else -> error("SYNC_MODULE_UNSUPPORTED")
    }
    private fun digest(request: SignedRegistryRequest?, roots: List<SkillScanRoot>): String {
        val text = if (request != null) listOf(request.manifestUrl, request.signatureUrl, request.publicKeyBase64).joinToString("\u0000")
        else buildString {
            roots.forEach { root ->
                append(root.hostDir.path).append('\u0000').append(root.guestPrefix).append('\u0000')
                if (!root.hostDir.exists()) { append("MISSING"); return@forEach }
                require(root.hostDir.isDirectory)
                var count = 0; var size = 0L
                root.hostDir.walkTopDown().onEnter { dir ->
                    require(!java.nio.file.Files.isSymbolicLink(dir.toPath())); true
                }.sortedBy { it.relativeTo(root.hostDir).path }.forEach { file ->
                    require(++count <= 10000 && !java.nio.file.Files.isSymbolicLink(file.toPath()))
                    append(file.relativeTo(root.hostDir).path).append('\u0000')
                    if (file.isFile) {
                        size += file.length(); require(size <= 64L * 1024 * 1024)
                        val hash = java.security.MessageDigest.getInstance("SHA-256")
                        file.inputStream().use { input -> val buffer=ByteArray(65536); while(true) {
                            val n=input.read(buffer); if(n<0) break; hash.update(buffer,0,n)
                        } }
                        append(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
                    }
                    append('\u0000')
                }
            }
        }
        return PackageAdministration.digest(text)
    }
    /** Reserved aggregate ID/target; neither represents a path or caller-supplied catalog. */
    override suspend fun snapshot(module: String, id: String, target: String): PackageSnapshot {
        if (id != "native-sync") return other.snapshot(module,id,target)
        require(target == "native-config")
        val (request, roots) = withContext(Dispatchers.IO) { config(module) }
        val hash = withContext(Dispatchers.IO) { digest(request,roots) }
        val snapshot = PackageSnapshot(PackageMetadata(module,id,null,false,false,hash),target,hash,null,
            emptySet(),if(request!=null) "NATIVE_SETTINGS_ED25519" else "NATIVE_SKILL_ROOTS",emptySet())
        synchronized(previewLock) {
            if (frozen.size >= 64) frozen.clear() // Bounded native previews; stale proposals fail closed.
            frozen["$module:$hash"] = Frozen(request,roots,snapshot)
        }
        return snapshot
    }
    override suspend fun execute(owner: String, change: PackageChange, authorize: suspend () -> Boolean): PackageOperation {
        if(change.action != "sync") return other.execute(owner,change,authorize)
        require(owner.isNotBlank() && change.expected.metadata.id == "native-sync" && !change.deleteData && change.desired == null)
        val key = "${change.expected.metadata.module}:${change.expected.manifestDigest}"
        val candidate = requireNotNull(frozen.remove(key)) { "SYNC_PREVIEW_REQUIRED" }
        require(candidate.snapshot == change.expected)
        val row=PackageOperation(UUID.randomUUID().toString(),owner,"queued",true,false)
        record(row)
        val job=scope.launch(start=CoroutineStart.LAZY) {
            var effects=false
            try {
                gate.withLock {
                    val (request,roots)=config(change.expected.metadata.module)
                    check(request == candidate.request && roots == candidate.roots)
                    check(withContext(Dispatchers.IO) { digest(request,roots) } == change.expected.manifestDigest)
                    check(authorize()) { "PACKAGE_APPROVAL_REVOKED" }
                    record(row.copy(state="running")); effects=true
                    if(request!=null) check(registry.updateSigned(request) {
                        config(change.expected.metadata.module).first == candidate.request && authorize()
                    }.isSuccess) { "REGISTRY_SYNC_FAILED" }
                    else skills.syncFromDirectories(candidate.roots) {
                        val fresh=config(change.expected.metadata.module)
                        fresh.second == candidate.roots &&
                            withContext(Dispatchers.IO) { digest(null,fresh.second) } == change.expected.manifestDigest && authorize()
                    }
                    record(row.copy(state="completed"))
                }
            } catch(cancelled: CancellationException) {
                withContext(NonCancellable) { record(row.copy(state=if(effects) "unknown_partial" else "cancelled",partial=effects)) }
                throw cancelled
            } catch(_: Exception) { record(row.copy(state=if(effects) "unknown_partial" else "rejected",partial=effects)) }
        }
        jobs[row.id]=job
        job.invokeOnCompletion {
            jobs.remove(row.id)
            if (rows[row.id]?.state == "queued") record(row.copy(state="cancelled"))
        }
        job.start()
        return row
    }
    override suspend fun status(owner: String, operationId: String): PackageOperation? =
        rows[operationId]?.takeIf { it.owner==owner } ?: other.status(owner,operationId)
    override suspend fun cancel(owner: String, operationId: String): PackageOperation? {
        val row=rows[operationId] ?: return other.cancel(owner,operationId)
        require(row.owner==owner)
        jobs[operationId]?.cancel()
        return rows[operationId]
    }
}
