package top.wkbin.taixu.core.model.bot

import kotlinx.serialization.Serializable

/** A request, never a grant. Attribution is resolved from the native assignment session. */
@Serializable
data class TeamSuggestion(
    val id: String,
    val groupId: String,
    val sourceAssignmentId: String,
    val sourceSessionId: String,
    val idempotencyKey: String,
    val reason: String,
    val operationJson: String,
    val beforeSnapshot: String,
    val createdAt: Long,
    val sourceRevision: Int,
    val sourceTeamBinding: String,
    val sourceMembershipStamp: String,
    val status: String = "pending",
    val proposalId: String? = null,
    val decidedBy: String? = null,
    val decidedAt: Long? = null,
)
