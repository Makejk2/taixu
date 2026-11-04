package top.wkbin.taixu.core.model.bot

import kotlinx.serialization.Serializable

/** Cumulative limits are absolute ceilings, not refillable tokens. Native approval may raise them.
 * Only group/member zero means unlimited (with explicit native approval). maxHop must be positive:
 * zero is rejected, not an implicit disable/unlimited switch; a larger finite hop needs approval.
 * Rate and queue always remain positive even when cumulative ceilings are explicitly removed.
 */
@Serializable
data class TeamCommunicationPolicy(
    val groupLimit: Long = 64,
    val memberLimit: Long = 8,
    val maxHop: Int = 8,
    val rateLimit: Int = 16,
    val rateWindowMs: Long = 60_000,
    val queueLimit: Int = 32,
    val unlimitedExplicit: Boolean = false,
) {
    fun validate() {
        require(groupLimit >= 0 && memberLimit >= 0 && maxHop > 0)
        require((groupLimit != 0L && memberLimit != 0L) || unlimitedExplicit) {
            "0累计上限仅允许用户在原生审批中明确选择不设上限"
        }
        require(rateLimit > 0 && rateWindowMs > 0 && queueLimit > 0) {
            "速率、窗口与队列背压必须大于0，不允许无限资源"
        }
    }
}

@Serializable
data class TeamCommunicationReceipt(val id: String, val state: String, val holdReason: String? = null)

/** sequence is the monotonic DELIVERY cursor, not hold creation order or a message-id sort. */
@Serializable
data class TeamCommunicationMessage(
    val sequence: Long, val id: String, val groupId: String,
    val sourceAssignmentId: String, val targetAssignmentId: String,
    val instruction: String, val hop: Int, val state: String, val holdReason: String?,
)

@Serializable
data class TeamCommunicationSnapshot(
    val policy: TeamCommunicationPolicy, val revision: Int, val charged: Long, val held: Long,
)
