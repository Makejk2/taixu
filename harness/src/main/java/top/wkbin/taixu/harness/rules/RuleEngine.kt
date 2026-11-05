package top.wkbin.taixu.harness.rules

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.wkbin.taixu.core.database.UserManagementRule
import top.wkbin.taixu.core.database.UserRuleRepository

@Serializable
data class RuleCondition(
    val tools: Set<String> = emptySet(),
    val taskKinds: Set<String> = emptySet(),
    val memberRoles: Set<String> = emptySet(),
)
@Serializable
data class RuleActions(
    val block: Boolean = false,
    val requireNativeReview: Boolean = false,
    val pause: Boolean = false,
    val maxToolCalls: Long? = null,
    val routeToRoles: Set<String> = emptySet(),
)
@Serializable
data class RuleDocument(val version: Int = 1, val condition: RuleCondition = RuleCondition(), val actions: RuleActions)

/** All values are supplied by trusted execution context, never copied from tool arguments. */
data class RuleExecutionContext(
    val sessionId: String, val workspace: String, val tool: String,
    val taskKind: String?, val memberRole: String?, val consumedToolCalls: Long,
)
data class TypedRuleDecision(
    val matchedRuleIds: List<String> = emptyList(),
    val matchedRevisions: Map<String, Long> = emptyMap(),
    val storageRevisions: Map<String, Long> = emptyMap(),
    val blocked: Boolean = false,
    val requireNativeReview: Boolean = false,
    val paused: Boolean = false,
    val maxToolCalls: Long? = null,
    /** null = unrestricted; empty = conflicting routing restrictions, must pause. */
    val allowedMemberRoles: Set<String>? = null,
    val failureCode: String? = null,
) {
    val mayExecute: Boolean get() = !blocked && !paused && failureCode == null
}

object RuleCodec {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }
    fun decode(content: String): RuleDocument {
        require(content.length <= 32768) { "rule_content_too_large" }
        val doc = json.decodeFromString<RuleDocument>(content)
        require(doc.version == 1) { "unsupported_rule_version" }
        fun checkNames(names: Set<String>) {
            require(names.size <= 64 && names.all { it.matches(Regex("[A-Za-z0-9_.:/-]{1,128}")) })
        }
        checkNames(doc.condition.tools); checkNames(doc.condition.taskKinds)
        checkNames(doc.condition.memberRoles); checkNames(doc.actions.routeToRoles)
        require(doc.actions.maxToolCalls == null || doc.actions.maxToolCalls in 0..1_000_000L)
        return doc
    }
    fun validate(row: UserManagementRule) {
        require(row.id.matches(Regex("custom:[A-Za-z0-9_.-]{1,96}"))) { "builtin_or_invalid_id" }
        require(row.priority in -1000..1000 && row.revision > 0)
        require(row.scope in setOf("global", "session", "workspace"))
        val scopeId = row.scopeId
        require(if (row.scope == "global") scopeId == null else !scopeId.isNullOrBlank() && scopeId.length <= 1024)
        decode(row.content)
    }
}

/** Monotonic restrictions only: priority controls diagnostics, never overrides a deny. */
class RuleEngine(private val repository: UserRuleRepository) {
    suspend fun evaluate(context: RuleExecutionContext): TypedRuleDecision = try {
        require(context.consumedToolCalls >= 0)
        var decision = TypedRuleDecision()
        val snapshot = repository.list()
        decision = decision.copy(storageRevisions = snapshot.filter { it.enabled }.associate { it.id to it.revision })
        val rows = snapshot.filter { it.enabled && when (it.scope) {
            "global" -> true
            "session" -> it.scopeId == context.sessionId
            "workspace" -> it.scopeId == context.workspace
            else -> error("invalid_scope")
        } }.sortedWith(compareByDescending<UserManagementRule> { it.priority }.thenBy { it.id })
        for (row in rows) {
            RuleCodec.validate(row)
            val doc = RuleCodec.decode(row.content)
            val c = doc.condition
            if (c.tools.isNotEmpty() && context.tool !in c.tools) continue
            if (c.taskKinds.isNotEmpty() && context.taskKind !in c.taskKinds) continue
            if (c.memberRoles.isNotEmpty() && context.memberRole !in c.memberRoles) continue
            val a = doc.actions
            val budget = listOfNotNull(decision.maxToolCalls, a.maxToolCalls).minOrNull()
            val roles = if (a.routeToRoles.isEmpty()) decision.allowedMemberRoles
                else decision.allowedMemberRoles?.intersect(a.routeToRoles) ?: a.routeToRoles
            decision = decision.copy(
                matchedRuleIds = decision.matchedRuleIds + row.id,
                matchedRevisions = decision.matchedRevisions + (row.id to row.revision),
                blocked = decision.blocked || a.block,
                requireNativeReview = decision.requireNativeReview || a.requireNativeReview,
                paused = decision.paused || a.pause || (budget != null && context.consumedToolCalls >= budget) ||
                    (roles != null && context.memberRole !in roles),
                maxToolCalls = budget, allowedMemberRoles = roles,
            )
        }
        decision
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { TypedRuleDecision(blocked = true, failureCode = "RULE_POLICY_UNAVAILABLE") }
}
