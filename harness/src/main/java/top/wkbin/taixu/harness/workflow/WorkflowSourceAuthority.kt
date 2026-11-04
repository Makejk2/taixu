package top.wkbin.taixu.harness.workflow

import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import top.wkbin.taixu.core.model.workflow.WorkflowApprovalRequest
import top.wkbin.taixu.harness.subagent.WorkflowLaneAuthority

/** Call only after the durable native journal has consumed this source authorization. */
suspend fun <T> withWorkflowSourceAuthority(
    source: WorkflowExecutionSource,
    executionId: String,
    nodeId: String,
    broker: WorkflowApprovalBroker,
    onApprovalState: suspend (Boolean) -> Unit = {},
    block: suspend () -> T,
): T {
    val executionContext = checkNotNull(currentCoroutineContext()[WorkflowExecutionContext]) {
        "工作流来源必须在真实执行租约中装配"
    }
    val binding = executionContext.binding
    source.verify(binding)
    suspend fun awaitNative(request: WorkflowApprovalRequest): Boolean {
        val id = UUID.randomUUID().toString()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest((request.nodeId + "\u0000" + request.title + "\u0000" + request.description).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        executionContext.reviewJournal(top.wkbin.taixu.core.database.WorkflowNativeReviewEvent(
            id,executionId,nodeId,digest,"REQUESTED",System.currentTimeMillis()))
        return try {
            onApprovalState(true)
            val token = currentCoroutineContext()[WorkflowWorkToken]
            val decision = try {
                token?.pause()
                broker.await(request)
            } finally {
                token?.resume()
            }
            val approved = decision.approved && decision.reviewToken == request.reviewToken
            executionContext.settleReview(id,if(approved) "APPROVED" else "DENIED")
            approved
        } catch (failure: Throwable) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { executionContext.settleReview(id,"UNKNOWN") }
            }
            throw failure
        } finally {
            onApprovalState(false)
        }
    }
    val authority = WorkflowLaneAuthority(source, executionId, nodeId, reviewModel = { fingerprint, variant ->
        val token = UUID.randomUUID().toString()
        awaitNative(WorkflowApprovalRequest(executionId = executionId, nodeId = "$nodeId:model:$fingerprint",
            title = "工作流来源模型已变化，需要重新批准",
            description = "档案=${source.modelBinding.modelId}；变体=$variant；新配置摘要=$fingerprint。" +
                "目标、工作区及工具权限不变；不显示密钥、地址或请求头。拒绝则不调用新配置。",
            reviewToken = token))
    }) { callId, tool, preview ->
        val token = UUID.randomUUID().toString()
        awaitNative(WorkflowApprovalRequest(
            executionId = executionId,
            nodeId = "$nodeId:tool:$callId",
            title = "工作流工具审批 · $tool",
            description = preview,
            reviewToken = token,
        ))
    }
    return withContext(authority) { block() }
}
