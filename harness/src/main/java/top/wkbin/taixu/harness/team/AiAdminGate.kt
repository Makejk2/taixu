package top.wkbin.taixu.harness.team

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Disabling still permits inspection, grant revocation and stopping previously started teams. */
internal fun aiAdminRequiresEnabled(tool: String, args: JsonObject): Boolean {
    val action = (args["action"] as? JsonPrimitive)?.content.orEmpty()
    if (tool == "app_config" && (args["module"] as? JsonPrimitive)?.content == "model")
        return action !in setOf("list", "get", "form_status")
    if (tool == "app_config" && (args["module"] as? JsonPrimitive)?.content == "workflow")
        return action !in setOf("list", "get", "status")
    if (tool == "app_config") return action !in setOf("list", "get", "grants", "revoke", "modules", "inspect_module")
    if (tool == "team") {
        if (action in setOf("list", "get", "status", "proposal", "suggestions", "apply")) return false
        val op = (args["operation"] as? JsonObject)?.get("op") as? JsonPrimitive
        return action != "propose" || op?.content != "stop"
    }
    return false
}

/** Detect off/on changes even when no tool ran while disabled; stale grants/proposals never revive. */
internal class AiAdminEpochGate {
    private val lock = kotlinx.coroutines.sync.Mutex()
    private var lastRevision: Long? = null
    suspend fun check(snapshot: Pair<Boolean, Long>, invalidate: suspend () -> Unit): Boolean {
        lock.lock()
        try {
            if (!snapshot.first || lastRevision != snapshot.second) invalidate()
            lastRevision = snapshot.second
            return snapshot.first
        } finally { lock.unlock() }
    }
}
