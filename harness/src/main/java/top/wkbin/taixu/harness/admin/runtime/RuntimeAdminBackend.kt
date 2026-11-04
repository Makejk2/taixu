package top.wkbin.taixu.harness.admin.runtime

import kotlinx.serialization.json.*
import top.wkbin.taixu.harness.admin.model.ModelAdminActor
import top.wkbin.taixu.harness.admin.model.ModelAdminAuthority
import top.wkbin.taixu.runtime.RuntimeInstallRequest

/** Router-facing strict protocol. Native import is intentionally constructed via picker callback,
 * not accepted as a path, URI, handle id, or self-approved flag in model arguments. */
class RuntimeAdminBackend(
    private val distro: RuntimeAdminAdapter,
    private val terminal: TerminalAdminAdapter,
    private val gate: RuntimeAdminGate,
    private val authority: ModelAdminAuthority,
    private val workspace: suspend (String) -> String,
) {
    suspend fun execute(actor: ModelAdminActor, args: JsonObject): Any? {
        require(authority.isControlledUserSession(actor))
        val module = args.text("module")
        val action = args.text("action")
        require(module in setOf("distro", "terminal"))
        if (action == "apply") {
            args.keys("module", "action", "proposalId")
            return gate.prepare(actor, args.text("proposalId"))
        }
        if (module == "distro") return when (action) {
            "list" -> { args.keys("module", "action"); distro.list() }
            "health", "updatecheck" -> {
                args.keys("module", "action", "target")
                if (action == "health") distro.health(args.text("target")) else distro.checkUpdate(args.text("target"))
            }
            "status" -> { args.keys("module", "action", "operationId"); distro.status(args.text("operationId")) }
            "install", "switch", "update", "uninstall", "reset" -> {
                if (action in setOf("install", "update")) args.keys("module", "action", "target", "digest")
                else args.keys("module", "action", "target")
                val target = args.text("target")
                val frozen = distro.snapshot(target, workspace(actor.sessionId)).let { it.copy(rootfsDigests = it.rootfsDigests.toMap(), installedIds = it.installedIds.toSet()) }
                require(frozen.targetId == target)
                val mutation = when (action) {
                    "install" -> RuntimeMutation.Install(RuntimeInstallRequest(target, approvedManifestDigest = args.text("digest")))
                    "switch" -> RuntimeMutation.Switch(target)
                    "update" -> RuntimeMutation.Update(target, args.text("digest"))
                    "uninstall" -> RuntimeMutation.Uninstall(target)
                    else -> RuntimeMutation.Reset(target)
                }
                gate.propose(actor, module, action,
                    "Target $target; source ${frozen.sourceId}; workspace ${frozen.workspaceId}; generation ${frozen.generation}. May stop foreground workloads; reset preserves workspace, uninstall removes rootfs.") {
                    distro.start(frozen, mutation)
                }
            }
            else -> error("DISTRO_ACTION_REJECTED")
        }
        return when (action) {
            "list", "recovery" -> {
                args.keys("module", "action")
                if (action == "list") terminal.list() else terminal.recovery()
            }
            "create", "switch", "close", "closeAll", "recover", "input" -> {
                when (action) {
                    "create" -> args.keys("module", "action", "label", "cwd", "target")
                    "switch", "close" -> args.keys("module", "action", "id")
                    "input" -> args.keys("module", "action", "id", "text")
                    else -> args.keys("module", "action")
                }
                val frozen = terminal.snapshot().let { it.copy(resourceIds = it.resourceIds.toSet(), restoringIds = it.restoringIds.toSet()) }
                val id = if (action in setOf("switch", "close", "input")) args.text("id") else ""
                require(id.isEmpty() || id in frozen.resourceIds)
                gate.propose(actor, module, action,
                    "Foreground ${frozen.activeId}; frozen tabs ${frozen.resourceIds}. Close stops interactive processes; last close may create native fallback. Input interacts with a live process.") {
                    when (action) {
                        "create" -> terminal.create(frozen, args.text("label"), args.text("cwd"), args.text("target"))
                        "switch" -> terminal.switch(frozen, id)
                        "close" -> terminal.close(frozen, setOf(id))
                        "closeAll" -> terminal.close(frozen, frozen.resourceIds)
                        "recover" -> terminal.recover(frozen)
                        else -> terminal.input(frozen, id, args.text("text"))
                    }
                }
            }
            else -> error("TERMINAL_ACTION_REJECTED")
        }
    }
    private fun JsonObject.text(key: String): String =
        requireNotNull((this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content).also { require(it.isNotBlank()) }
    private fun JsonObject.keys(vararg allowed: String) { require(keys == allowed.toSet()) }
}
