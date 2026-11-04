package top.wkbin.taixu.harness.admin.rules

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import top.wkbin.taixu.core.database.UserManagementRule
import top.wkbin.taixu.core.database.UserRuleRepository
import top.wkbin.taixu.harness.rules.RuleCodec
import java.util.UUID

data class RuleManagementActor(val sessionId: String, val operationId: String)
/** Implement using trusted main-lane actor gate and native preview bridge. Never tool-supplied booleans. */
interface RuleManagementAuthority {
    suspend fun requireNativeMainActor(actor: RuleManagementActor)
}
data class RuleChangeProposal internal constructor(
    val id: String, val action: String, val ruleId: String,
    val expectedRevision: Long?, val replacement: UserManagementRule?, val epoch: Long,
)

/** app_config module=rules adapter. Factory must wire authority to the shared epoch/actor gate. */
class UserRulesBackend(private val repository: UserRuleRepository, private val authority: RuleManagementAuthority) {
    private val mutex = Mutex()
    private var enabled = false
    private var epoch = 0L
    private data class Pending(val actor: RuleManagementActor, val proposal: RuleChangeProposal,
        val expiresAt: Long, var callId: String? = null)
    private val pending = mutableMapOf<String, Pending>()
    /** Native settings callback only; not exposed by execute. */
    suspend fun setManagementEnabled(value: Boolean) = mutex.withLock {
        if (enabled != value) { enabled = value; epoch++; pending.clear() }
    }
    suspend fun disableManagement() = setManagementEnabled(false)

    suspend fun execute(actor: RuleManagementActor, args: JsonObject): JsonElement {
        authority.requireNativeMainActor(actor)
        require(args["module"]?.jsonPrimitive?.content == "rules")
        val action = args["action"]?.jsonPrimitive?.content ?: error("missing_action")
        val fields = when (action) {
            "list" -> setOf("module", "action")
            "get" -> setOf("module", "action", "id")
            "propose" -> setOf("module", "action", "operation", "id", "expectedRevision", "rule")
            "apply" -> setOf("module", "action", "proposalId")
            else -> error("unknown_action")
        }
        require(args.keys.all { it in fields }) { "unknown_field" }
        fun text(key: String) = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("invalid_$key")
        return when (action) {
            "list" -> JsonArray(repository.list().map(::summary))
            "get" -> repository.get(text("id"))?.let(::summary) ?: JsonNull
            "propose" -> mutex.withLock {
                check(enabled) { "management_disabled" }
                require(pending.size < 128) { "proposal_limit" }
                val op = text("operation")
                require(op in setOf("create", "replace", "delete"))
                val id = text("id")
                require(id.matches(Regex("custom:[A-Za-z0-9_.-]{1,96}"))) { "builtin_read_only" }
                val expected = args["expectedRevision"]?.jsonPrimitive?.long
                require(if (op == "create") expected == null else expected != null && expected > 0 && expected < Long.MAX_VALUE)
                val replacement = if (op == "delete") {
                    require("rule" !in args); null
                } else {
                    val body = args["rule"]?.jsonObject ?: error("missing_rule")
                    require(body.keys == setOf("enabled", "scope", "scopeId", "priority", "content"))
                    UserManagementRule(id, body.getValue("enabled").jsonPrimitive.boolean,
                        body.getValue("scope").jsonPrimitive.content,
                        body.getValue("scopeId").let { if (it == JsonNull) null else it.jsonPrimitive.content },
                        body.getValue("priority").jsonPrimitive.int, body.getValue("content").jsonPrimitive.content,
                        expected?.plus(1) ?: 1).also(RuleCodec::validate)
                }
                val proposal = RuleChangeProposal(UUID.randomUUID().toString(), op, id, expected, replacement, epoch)
                pending[proposal.id] = Pending(actor, proposal, System.currentTimeMillis() + 120_000)
                buildJsonObject { put("proposalId", proposal.id); put("status", "native_review_required") }
            }
            else -> error("native_gate_required")
        }
    }
    suspend fun prepare(actor: RuleManagementActor, id: String, callId: String): RuleChangeProposal {
        authority.requireNativeMainActor(actor)
        return mutex.withLock {
            val entry = requireNotNull(pending[id])
            check(enabled && entry.proposal.epoch == epoch && entry.actor.sessionId == actor.sessionId)
            if (entry.expiresAt <= System.currentTimeMillis() || !matches(entry.proposal)) {
                pending.remove(id); error("rule_proposal_expired_or_changed")
            }
            check(entry.callId == null || entry.callId == callId)
            entry.callId = callId; entry.proposal
        }
    }

    suspend fun applyNative(actor: RuleManagementActor, id: String, callId: String): JsonElement {
        authority.requireNativeMainActor(actor)
        return mutex.withLock {
            val entry = requireNotNull(pending[id])
            check(enabled && entry.proposal.epoch == epoch && entry.actor.sessionId == actor.sessionId && entry.callId == callId)
            pending.remove(id) // Consume before the persistent CAS; never replay uncertain effects.
            check(entry.expiresAt > System.currentTimeMillis())
            val p = entry.proposal
            check(matches(p)) { "rule_revision_conflict" }
            val changed = when(p.action) {
                "create" -> repository.create(requireNotNull(p.replacement))
                "replace" -> repository.replace(requireNotNull(p.replacement), requireNotNull(p.expectedRevision))
                else -> repository.delete(p.ruleId, requireNotNull(p.expectedRevision))
            }
            check(changed) { "rule_revision_conflict" }
            buildJsonObject { put("status", "applied"); put("id", p.ruleId) }
        }
    }

    private suspend fun matches(p: RuleChangeProposal): Boolean {
        val row = repository.get(p.ruleId)
        return if (p.action == "create") row == null else row?.revision == p.expectedRevision
    }

    private fun summary(row: UserManagementRule) = buildJsonObject {
        put("id", row.id); put("enabled", row.enabled); put("scope", row.scope)
        put("scopeId", row.scopeId?.let(::JsonPrimitive) ?: JsonNull)
        put("priority", row.priority); put("content", row.content); put("revision", row.revision)
        put("builtin", false)
    }
}
