package top.wkbin.taixu.harness.workflow

import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.environment.ExecutionEnvironmentFactory

/** Factory is explicitly LOCAL, never selected from a mutable active distro. */
fun createWorkflowExecutionLeases(factory: ExecutionEnvironmentFactory, runtime: LinuxRuntime, workflows: top.wkbin.taixu.core.database.WorkflowRepository,
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
        reviewJournal = { row -> workflows.insertNativeReview(row) },
        settleReview = { id,state -> workflows.finishNativeReview(id,state) },
        verifySource = { executionId, source ->
            val row = workflows.sourceAuthorization(source.authorizationId)
            val digest = source.authorizationDigest()
            row != null && row.sourceSessionId == source.sourceSessionId && row.sourceOperationId == source.sourceOperationId &&
                row.definitionHash == source.definitionHash && row.targetHash == digest &&
                row.executionId == executionId
        })
