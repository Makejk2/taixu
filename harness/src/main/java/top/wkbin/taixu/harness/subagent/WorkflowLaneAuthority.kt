package top.wkbin.taixu.harness.subagent

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import top.wkbin.taixu.harness.workflow.WorkflowExecutionSource

/** Installed by the native workflow adapter, inherited by child lanes, never by tool arguments.
 * Runner must require matching session authority and route pending tool approvals through this
 * suspending bridge before retrying only the not-yet-executed tool call.
 */
class WorkflowLaneAuthority(
    val source: WorkflowExecutionSource,
    val executionId: String,
    val nodeId: String,
    reviewModel: suspend (fingerprint: String, variant: String) -> Boolean = { _, _ -> false },
    val approve: suspend (callId: String, tool: String, preview: String) -> Boolean,
) : AbstractCoroutineContextElement(Key) {
    private val modelReviews = kotlinx.coroutines.sync.Mutex()
    private val approvedModels = mutableSetOf<Pair<String,String>>()
    private val nativeModelReview = reviewModel
    suspend fun reviewModel(fingerprint: String, variant: String): Boolean {
        modelReviews.lock()
        try {
            val key = fingerprint to variant
            if (key in approvedModels) return true
            val approved = nativeModelReview(fingerprint, variant)
            if (approved) approvedModels += key
            return approved
        } finally { modelReviews.unlock() }
    }
    companion object Key : CoroutineContext.Key<WorkflowLaneAuthority>
}
