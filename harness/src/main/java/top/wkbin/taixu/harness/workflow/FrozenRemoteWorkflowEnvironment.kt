package top.wkbin.taixu.harness.workflow

import top.wkbin.taixu.core.datastore.RemoteServerRepository
import top.wkbin.taixu.core.model.SessionExecutionTarget
import top.wkbin.taixu.runtime.environment.ExecutionEnvironment
import top.wkbin.taixu.runtime.environment.ExecutionEnvironmentId
import top.wkbin.taixu.runtime.remote.SshConnector

/** Uses only the approved server snapshot. The global remote selection is never consulted. */
class FrozenRemoteWorkflowEnvironment(
    private val servers: RemoteServerRepository,
    private val connector: SshConnector,
) {
    suspend fun pin(target: SessionExecutionTarget): AutoCloseable {
        target.validate()
        return servers.acquireExecutionLease(requireNotNull(target.remote))
    }
    fun available(target: SessionExecutionTarget): Boolean {
        val expected = target.remote ?: return false
        return servers.servers.value.singleOrNull { it.id == expected.id } == expected
    }
    suspend fun open(owner: String, target: SessionExecutionTarget): ExecutionEnvironment {
        target.validate()
        val expected = requireNotNull(target.remote)
        require(target.distribution == expected.distributionId)
        val logical = target.workspace
        require(logical == "/workspace" || logical.startsWith("/workspace/"))
        require(logical.split('/').none { it == "." || it == ".." } && '\u0000' !in logical)
        val (actual, credentials) = servers.connection(expected.id)
        check(actual == expected) { "批准的远程连接已改变；不会切换到其他服务器" }
        val relative = logical.removePrefix("/workspace").trim('/')
        val physical = expected.workspace.trimEnd('/') + if (relative.isEmpty()) "" else "/$relative"
        return connector.openEnvironment(actual, credentials,
            ExecutionEnvironmentId("ssh", target.distribution, physical, owner), logical)
    }
}
