package top.wkbin.taixu.harness.workflow

import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.environment.ExecutionEnvironmentFactory

/** Factory is explicitly LOCAL, never selected from a mutable active distro. */
fun createWorkflowExecutionLeases(factory: ExecutionEnvironmentFactory, runtime: LinuxRuntime) =
    WorkflowExecutionLeases(factory,
        available = { id -> runtime.installedDistros.value.any { it.id == id } },
        acquireTargetPin = { id -> runtime.acquireExecutionTargetLease(id) })
