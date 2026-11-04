package top.wkbin.taixu.harness.workflow

import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import top.wkbin.taixu.core.database.WorkflowNativeReviewEvent
import top.wkbin.taixu.core.model.workflow.*

internal data class ExpansionApproval(
    val output: NodeExecutionOutput,
    val patch: WorkflowExpansion? = null,
    val admission: WorkflowExpansionAdmission? = null,
)

internal suspend fun approveExpansion(
    node: WorkflowNode,
    context: WorkflowRuntimeContext,
    definition: WorkflowDefinition,
    broker: WorkflowApprovalBroker,
    onProgress: suspend (NodeRunStatus, String) -> Unit,
): ExpansionApproval {
    val source = node.config.getValue(WorkflowExpansions.SOURCE_KEY)
    val output = context.nodeOutputs[source]
    require(output?.status == NodeRunStatus.SUCCESS && source in context.upstreamNodeIds.orEmpty()) { "扩图结果源未成功执行或未激活" }
    val patch = WorkflowExpansions.decode(requireNotNull(output.structuredResult) { "扩图结果源没有结构化结果" })
    val next = WorkflowExpansions.append(definition, node.id, patch)
    val lease = currentCoroutineContext()[WorkflowExecutionContext]?.takeIf { it.binding.source != null }
    val stamps = lease?.capturePluginStamps(next).orEmpty().filterKeys { id -> patch.nodes.any { it.id == id } }
    if (lease != null) check(stamps.keys == patch.nodes.filter { it.type == WorkflowNodeType.PLUGIN_ACTION }.map { it.id }.toSet() &&
        stamps.values.all { it.isNotBlank() }) { "新增插件缺少真实安装内容身份" }
    val digest = lease?.let { expansionAdmissionDigest(it.binding, context.executionId, node.id, definition, next, patch, stamps) }
    val depth = WorkflowExpansions.depth(definition, node.id)
    val nextStages = patch.nodes.count { WorkflowExpansions.SOURCE_KEY in it.config }
    val review = buildString {
        appendLine("以下步骤将追加到本次运行。确认后可能执行命令、调用智能体或修改文件，请核对全部配置。变量引用按后续执行时的上下文解析。")
        appendLine("工作区：${context.workspacePath}")
        lease?.binding?.let { binding ->
            appendLine("冻结执行目标：${binding.targetJson}")
            appendLine("来源授权：${binding.source?.authorizationId}；权限：${binding.approvalMode}/${binding.runMode}")
            appendLine("新增插件内容身份：$stamps")
        }
        appendLine("父图 SHA-256：${WorkflowExecutionBinding.hash(definition)}")
        appendLine("完整新图 SHA-256：${WorkflowExecutionBinding.hash(next)}")
        if (digest != null) appendLine("本次批准摘要 SHA-256：$digest")
        appendLine("第 $depth / ${WorkflowExpansions.MAX_DEPTH} 层扩图。")
        if (nextStages > 0) appendLine("包含 $nextStages 个后续扩图审批；本次确认只覆盖以下步骤，后续提案仍需分别确认。")
        appendLine(node.description)
        appendLine("完整提案（含所有新增动作、目标配置、失败策略和连接；不截断）：")
        append(WorkflowExpansions.encode(patch))
    }
    val reviewId = UUID.randomUUID().toString()
    val token = UUID.randomUUID().toString()
    if (lease != null) {
        lease.reviewJournal(WorkflowNativeReviewEvent(reviewId, context.executionId, node.id, requireNotNull(digest), "REQUESTED", System.currentTimeMillis()))
    }
    var approved = false
    try {
        // Ensure durable read-back is wired before presenting a usable approval.
        if (lease != null) {
            val row = requireNotNull(lease.readReview(reviewId)) { "原生扩图审批日志不可读取" }
            check(row.state == "REQUESTED" && row.executionId == context.executionId && row.nodeId == node.id && row.requestDigest == digest) { "原生扩图审批请求回读不匹配" }
        }
        onProgress(NodeRunStatus.WAITING_APPROVAL, "等待确认新增 ${patch.nodes.size} 个步骤")
        val work = currentCoroutineContext()[WorkflowWorkToken]
        val decision = try {
            work?.pause()
            broker.await(WorkflowApprovalRequest(context.executionId, node.id,
                "确认第 $depth 层新增 ${patch.nodes.size} 个步骤", review, reviewToken = token))
        } finally { work?.resume() }
        approved = decision.approved && decision.reviewToken == token
        lease?.settleReview?.invoke(reviewId, if (approved) "APPROVED" else "DENIED")
        if (approved && lease != null) {
            val row = requireNotNull(lease.readReview(reviewId)) { "扩图批准未持久化" }
            check(row.state == "APPROVED" && row.executionId == context.executionId && row.nodeId == node.id && row.requestDigest == digest) { "扩图批准回读不匹配" }
        }
    } catch (failure: Throwable) {
        if (lease != null) withContext(NonCancellable) { runCatching { lease.settleReview(reviewId, "UNKNOWN") } }
        throw failure
    }
    return if (approved) ExpansionApproval(
        NodeExecutionOutput(NodeRunStatus.SUCCESS, textOutput = "已确认新增 ${patch.nodes.size} 个步骤"), patch,
        lease?.let { WorkflowExpansionAdmission(reviewId, WorkflowExecutionBinding.hash(definition), WorkflowExecutionBinding.hash(next), requireNotNull(digest), stamps) },
    ) else ExpansionApproval(NodeExecutionOutput(NodeRunStatus.FAILED, exitCode = 130, error = "用户拒绝扩图或审批快照不匹配"))
}
