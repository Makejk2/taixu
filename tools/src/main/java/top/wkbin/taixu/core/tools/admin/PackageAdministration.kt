package top.wkbin.taixu.core.tools.admin

import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.wkbin.taixu.core.database.AgentSkillRepository
import top.wkbin.taixu.core.tools.ToolRegistry
import top.wkbin.taixu.core.tools.WebPluginRepository
import top.wkbin.taixu.core.tools.skill.SkillInstallationManager

/** Only native picker implementations may create these; never resolve a model-supplied path. */
interface NativePackageDocument {
    suspend fun pluginUri(owner: String): android.net.Uri
    suspend fun skillBytes(owner: String): ByteArray
}
data class PackageMetadata(val module: String, val id: String, val version: String?, val enabled: Boolean,
    val builtin: Boolean, val fingerprint: String)
data class PackageSnapshot(val metadata: PackageMetadata, val target: String, val manifestDigest: String,
    val packageDigest: String?, val permissions: Set<String>, val verifiedSource: String?, val capabilities: Set<String>,
    val installedVersion: String? = null, val installationState: String? = null)
data class PackageChange(val action: String, val expected: PackageSnapshot, val desired: PackageSnapshot? = null,
    val deleteData: Boolean = false) {
    fun addedPermissions() = desired?.permissions.orEmpty() - expected.permissions
    fun removedPermissions() = expected.permissions - desired?.permissions.orEmpty()
}
data class PackageOperation(val id: String, val owner: String, val state: String, val durable: Boolean, val partial: Boolean)

/** Must CAS under the actual service lock shared by UI writers, record owner before effects,
 * and call authorize immediately before first effect. A control-plane mutex is NOT sufficient.
 * Process installs belong to ToolManager/InstallTransactionManager, never registry.call.
 */
interface PackageLifecycleTransactions {
    suspend fun snapshot(module: String, id: String, target: String): PackageSnapshot
    suspend fun execute(owner: String, change: PackageChange, authorize: suspend () -> Boolean): PackageOperation
    suspend fun status(owner: String, operationId: String): PackageOperation?
    suspend fun cancel(owner: String, operationId: String): PackageOperation?
}

/** Real read/inspection adapter. No production mutation fallback exists without service CAS. */
class PackageAdministration(private val registry: ToolRegistry, private val web: WebPluginRepository,
    private val skills: AgentSkillRepository, private val installer: SkillInstallationManager,
    val transactions: PackageLifecycleTransactions) {
    suspend fun list(module: String): List<PackageMetadata> = when (module) {
        "plugin", "process_plugin", "web_plugin" -> (when(module) {
            "process_plugin" -> registry.load()
            "web_plugin" -> registry.webPackages()
            else -> registry.load() + registry.webPackages()
        }).filter { it.plugin != null }.map {
            PackageMetadata(module, it.id, it.version, it.enabled, it.source == "BUILTIN", digest(Json.encodeToString(it)))
        }
        "skill" -> { skills.ensureInitialized(); skills.allSkills.first().map {
            PackageMetadata(module, it.id, null, it.isEnabled, it.isBuiltin, digest(Json.encodeToString(it)))
        } }
        else -> error("UNKNOWN_MODULE")
    }
    suspend fun inspectProcess(document: NativePackageDocument, owner: String) = registry.inspectLocal(document.pluginUri(owner))
    suspend fun inspectWeb(document: NativePackageDocument, owner: String) = web.preview(document.pluginUri(owner))
    suspend fun inspectSkill(document: NativePackageDocument, owner: String) = installer.inspectZipBytes(document.skillBytes(owner))
    companion object {
        /** Identity only, never claim this is the archive digest or signature verification. */
        fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
