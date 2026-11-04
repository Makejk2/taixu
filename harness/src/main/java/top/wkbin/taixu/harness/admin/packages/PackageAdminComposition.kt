package top.wkbin.taixu.harness.admin.packages

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import top.wkbin.taixu.core.database.AgentSkillRepository
import top.wkbin.taixu.core.tools.*
import top.wkbin.taixu.core.tools.admin.*
import top.wkbin.taixu.core.tools.skill.SkillInstallationManager

/** Each owner has a separate durable journal; no operation is replayed by composition. */
fun createPackageManagement(context: Context, registry: ToolRegistry, web: WebPluginRepository,
    skills: AgentSkillRepository, installer: SkillInstallationManager, tools: ToolRepository,
    manager: ToolManager, scope: CoroutineScope, authority: PackageAdminAuthority, skillRoot: File,
    registryPreferences: top.wkbin.taixu.core.datastore.RegistryPreferences? = null,
    nativeSkillRoots: suspend () -> List<top.wkbin.taixu.core.database.SkillScanRoot> = {
        error("NATIVE_SKILL_ROOTS_NOT_COMPOSED")
    }): PackageAdminBackend {
    fun journal(name: String) = PackageOperationJournal(File(context.filesDir,"management-packages/$name"))
    val skillOwner=SkillMetadataTransactions(skills,journal("skill"),installer,skillRoot)
    val processOwner=ProcessPackageTransactions(tools,manager,scope,journal("process"),skillOwner)
    val webOwner=WebPackageTransactions(registry,web,journal("web"),processOwner)
    val syncOwner=NativePackageSyncTransactions(registry,skills,registryPreferences,nativeSkillRoots,scope,journal("sync"),webOwner)
    return PackageAdminFactory.create(PackageAdministration(registry,web,skills,installer,syncOwner),authority)
}
