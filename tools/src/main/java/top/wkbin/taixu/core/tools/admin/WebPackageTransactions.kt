package top.wkbin.taixu.core.tools.admin

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.wkbin.taixu.core.tools.ToolRegistry
import top.wkbin.taixu.core.tools.WebPluginRepository

/** Existing installed Web packages: owner-locked removal; native import candidates are separate. */
class WebPackageTransactions(private val registry: ToolRegistry, private val web: WebPluginRepository,
    private val journal: PackageOperationJournal, private val other: PackageLifecycleTransactions? = null
) : PackageLifecycleTransactions {
    private val rows = ConcurrentHashMap<String,PackageOperation>().also { map ->
        journal.restore().forEach { journal.save(it); map[it.id]=it }
    }
    private fun record(row: PackageOperation) { journal.save(row); rows[row.id]=row }
    override suspend fun snapshot(module: String,id: String,target: String): PackageSnapshot {
        val manifest = registry.webPackages().find { it.id == id }
        if (module !in setOf("plugin","web_plugin") || manifest == null)
            return requireNotNull(other) { "PACKAGE_OWNER_NOT_COMPOSED" }.snapshot(module,id,target)
        val fingerprint=PackageAdministration.digest(Json.encodeToString(manifest))
        return PackageSnapshot(PackageMetadata(module,id,manifest.version,manifest.enabled,
            manifest.source == "BUILTIN",fingerprint),target,fingerprint,manifest.sha256,
            manifest.permissions.toSet(),manifest.source,emptySet(),manifest.version,"INSTALLED")
    }
    override suspend fun execute(owner: String,change: PackageChange,authorize: suspend () -> Boolean): PackageOperation {
        val expected=change.expected
        val manifest=registry.webPackages().find { it.id == expected.metadata.id }
        if (manifest == null || expected.metadata.module !in setOf("plugin","web_plugin"))
            return requireNotNull(other) { "PACKAGE_OWNER_NOT_COMPOSED" }.execute(owner,change,authorize)
        require(change.action == "remove") { "WEB_IMPORT_REQUIRES_NATIVE_DOCUMENT_CANDIDATE" }
        require(!change.deleteData) { "WEB_DATA_PURGE_REQUIRES_EXPLICIT_NATIVE_DATA_OWNER" }
        check(PackageAdministration.digest(Json.encodeToString(manifest)) == expected.manifestDigest)
        val row=PackageOperation(UUID.randomUUID().toString(),owner,"queued",true,false)
        record(row)
        var effects=false
        return try {
            web.remove(manifest) {
                check(authorize()); record(row.copy(state="running")); effects=true; true
            }
            row.copy(state="completed").also(::record)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { record(row.copy(state="unknown_partial",partial=effects)) }
            throw cancelled
        } catch (_: Exception) { row.copy(state=if(effects) "unknown_partial" else "rejected",partial=effects).also(::record) }
    }
    override suspend fun status(owner: String,id: String): PackageOperation? =
        rows[id]?.takeIf { it.owner == owner } ?: other?.status(owner,id)
    override suspend fun cancel(owner: String,id: String): PackageOperation? {
        val row=rows[id] ?: return other?.cancel(owner,id)
        require(row.owner == owner);return row
    }
}
