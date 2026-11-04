package top.wkbin.taixu.harness.workflow

import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.environment.ExecutionEnvironmentFactory

/** Factory is explicitly LOCAL, never selected from a mutable active distro. */
/** 生产装配可注入审批日志/插件/远程冻结目标；省略 workflows 时全部来源审批通道 fail-closed，
 * 保持无日志装配下既不能伪造扩图批准、也不能凭旧日志升级来源权限。
 */
fun createWorkflowExecutionLeases(
    factory: ExecutionEnvironmentFactory,
    runtime: LinuxRuntime,
    workflows: top.wkbin.taixu.core.database.WorkflowRepository? = null,
    remote: FrozenRemoteWorkflowEnvironment? = null,
    readReview: suspend (String) -> top.wkbin.taixu.core.database.WorkflowNativeReviewEvent? = { error("NATIVE_REVIEW_LOOKUP_UNAVAILABLE") },
    plugins: top.wkbin.taixu.harness.plugins.HarnessPluginRegistry? = null,
) =
    WorkflowExecutionLeases(factory,
        available = { id -> runtime.installedDistros.value.any { it.id == id } },
        acquireTargetPin = { id -> runtime.acquireExecutionTargetLease(id) },
        remote = remote,
        readReview = readReview,
        capturePluginStamps = { graph ->
            graph.nodes.filter { it.type == top.wkbin.taixu.core.model.workflow.WorkflowNodeType.PLUGIN_ACTION }.associate { node ->
                node.id to requireNotNull(plugins?.nodeIdentity(node.config["plugin"].orEmpty(), node.config["extension"].orEmpty())) {
                    "插件尚无可信安装内容身份"
                }
            }
        },
        reviewJournal = { row ->
            requireNotNull(workflows) { "NATIVE_REVIEW_JOURNAL_UNAVAILABLE" }.insertNativeReview(row)
        },
        settleReview = { id, state ->
            requireNotNull(workflows) { "NATIVE_REVIEW_JOURNAL_UNAVAILABLE" }.finishNativeReview(id, state)
        },
        verifySource = { executionId, source ->
            val digest = source.authorizationDigest()
            val row = workflows?.sourceAuthorization(source.authorizationId)
            row != null && row.sourceSessionId == source.sourceSessionId && row.sourceOperationId == source.sourceOperationId &&
                row.definitionHash == source.definitionHash && row.targetHash == digest &&
                row.executionId == executionId
        })
