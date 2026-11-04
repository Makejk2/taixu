package top.wkbin.taixu.harness.workflow

import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import top.wkbin.taixu.core.model.SessionExecutionTarget
import top.wkbin.taixu.core.model.workflow.*
import top.wkbin.taixu.runtime.StorageActivityGate
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.environment.*
import top.wkbin.taixu.runtime.shell.*

class WorkflowExecutionLeaseTest {
    private fun definition(type: WorkflowNodeType = WorkflowNodeType.BASH_COMMAND) = WorkflowDefinition(
        id = "lease-test", name = "lease", description = "", category = "test",
        nodes = listOf(WorkflowNode("start", WorkflowNodeType.TRIGGER, "start"),
            WorkflowNode("action", type, "action", config = mapOf("command" to "echo bound")),
            WorkflowNode("end", WorkflowNodeType.TERMINAL_OUTPUT, "end")),
        edges = listOf(WorkflowEdge(id = "e1", fromNodeId = "start", toNodeId = "action"), WorkflowEdge(id = "e2", fromNodeId = "action", toNodeId = "end")))
    private fun binding(d: WorkflowDefinition) = WorkflowExecutionBinding(
        Json.encodeToString(SessionExecutionTarget.serializer(), SessionExecutionTarget("debian", "/workspace/demo")),
        "/workspace/demo", "assisted", "build", WorkflowExecutionBinding.hash(d))
    private class World(override val id: ExecutionEnvironmentId) : ExecutionEnvironment {
        var closed = false
        var closeAttempts = 0
        var failCloses = 0
        val commands = mutableListOf<ShellCommand>()
        val processes = mutableListOf<String>()
        override val files: ExecutionFiles get() = error("unused")
        override fun workingDirectory(explicit: String?) = explicit ?: id.workspace
        override suspend fun execute(command: ShellCommand): CommandResult {
            commands += command; return CommandResult(0, "ok", "", 1)
        }
        override suspend fun startBackground(id: String, command: ShellCommand): ManagedProcess {
            processes += id; commands += command
            val session = Proxy.newProxyInstance(LinuxSession::class.java.classLoader, arrayOf(LinuxSession::class.java)) { _, _, _ -> null } as LinuxSession
            return ManagedProcess(id, 0, session)
        }
        override suspend fun stopBackground(id: String) = processes.remove(id)
        override fun listBackground() = emptyList<ManagedProcess>()
        override fun getBackgroundLogs(id: String) = emptyList<String>()
        override suspend fun openSession(config: SessionConfig): LinuxSession = error("unused")
        override suspend fun openTerminal(config: SessionConfig): LinuxSession = error("unused")
        override suspend fun close() {
            closeAttempts++
            if (closeAttempts <= failCloses) error("close failed")
            closed = true; processes.clear()
        }
    }
    private val forbiddenRuntime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader,
        arrayOf(LinuxRuntime::class.java)) { _, method, _ -> error("shared runtime bypass: ${method.name}") } as LinuxRuntime

    @Test fun bindingRoundTripAndDefinitionChangeFails() {
        val d = definition(); val b = binding(d)
        assertEquals(b, WorkflowExecutionBinding.decode(b.encode()))
        b.verify(d)
        assertTrue(runCatching { b.verify(d.copy(name = "changed")) }.isFailure)
        assertTrue(runCatching { b.copy(runMode = "plan").verify(d) }.isFailure)
        assertTrue(runCatching { b.copy(approvalMode = "unknown").verify(d) }.isFailure)
    }
    @Test fun agentAndHostRemainDenied() {
        listOf(WorkflowNodeType.AGENT_INFERENCE, WorkflowNodeType.HOST_ACTION, WorkflowNodeType.SUBAGENT_DELEGATE).forEach {
            val d = definition(it); assertTrue(runCatching { binding(d).verify(d) }.isFailure)
        }
    }
    @Test fun linuxNodesUsePinnedWorldAndCloseOwnedProcesses() = runBlocking {
        for (type in listOf(WorkflowNodeType.BASH_COMMAND, WorkflowNodeType.PROCESS_SERVICE, WorkflowNodeType.TAIXU_BUILD)) {
            val d = definition(type); val b = binding(d)
            val world = World(ExecutionEnvironmentId("local-proot", "debian", b.workspace, "workflow:run"))
            val leases = WorkflowExecutionLeases(ExecutionEnvironmentFactory { owner, workspace, distro ->
                assertEquals("workflow:run", owner); assertEquals(b.workspace, workspace); assertEquals("debian", distro); world
            }, acquireTargetPin = { StorageActivityGate().acquire() }) { it == "debian" }
            leases.use("run", b) {
                val context = currentCoroutineContext()[WorkflowExecutionContext]!!
                val result = context.operation(d) {
                    LinuxNodeExecutor(forbiddenRuntime).execute(d.nodes[1], WorkflowRuntimeContext("run", d.id, b.workspace)) { _, _ -> }
                }
                assertEquals(NodeRunStatus.SUCCESS, result.status)
                assertEquals(b.workspace, world.commands.single().workingDirectory)
                if (type == WorkflowNodeType.TAIXU_BUILD) assertTrue(world.commands.single().forcePty)
            }
            assertTrue(world.closed); assertTrue(world.processes.isEmpty())
        }
    }
    @Test fun removedTargetFailsBeforeNextOperationAndCloses() = runBlocking {
        val d = definition(); val b = binding(d); var available = true; var calls = 0
        val world = World(ExecutionEnvironmentId("local-proot", "debian", b.workspace, "workflow:run"))
        val leases = WorkflowExecutionLeases(ExecutionEnvironmentFactory { _, _, _ -> world }, acquireTargetPin = { StorageActivityGate().acquire() }) { available }
        val result = runCatching { leases.use("run", b) {
            val lease = currentCoroutineContext()[WorkflowExecutionContext]!!
            lease.operation(d) { calls++ }; available = false; lease.operation(d) { calls++ }
        } }
        assertTrue(result.isFailure); assertEquals(1, calls); assertTrue(world.closed)
    }
    @Test fun cancellationDrainsBeforeClosingIndependentLease() = runBlocking {
        val d = definition(); val b = binding(d); val gate = StorageActivityGate()
        val world = World(ExecutionEnvironmentId("local-proot", "debian", b.workspace, "workflow:run"))
        val leases = WorkflowExecutionLeases(ExecutionEnvironmentFactory { _, _, _ -> world }, acquireTargetPin = { gate.acquire() }) { true }
        val entered = CompletableDeferred<Unit>(); var drained = false
        val job = launch { leases.use("run", b) {
            currentCoroutineContext()[WorkflowExecutionContext]!!.operation(d) {
                try { entered.complete(Unit); awaitCancellation() } finally { drained = true; assertFalse(world.closed) }
            }
        } }
        entered.await(); job.cancelAndJoin(); assertTrue(drained); assertTrue(world.closed)
        assertFalse(leases.hasResources("run")); gate.cleanup { }
    }
    @Test fun schedulerPersistsIdentityBeforeCommandAndLeaseFailureIsTerminal() = runBlocking {
        val d = definition(); val b = binding(d)
        val world = World(ExecutionEnvironmentId("local-proot", "debian", b.workspace, "workflow:run"))
        val leases = WorkflowExecutionLeases(ExecutionEnvironmentFactory { _, _, _ -> world }, acquireTargetPin = { StorageActivityGate().acquire() }) { true }
        val scheduler = WorkflowScheduler(setOf(PassthroughNodeExecutor(), LinuxNodeExecutor(forbiddenRuntime)), WorkflowApprovalBroker())
        val checkpoints = mutableListOf<WorkflowRuntimeState>()
        val handle = scheduler.execute(d, emptyMap(), b.workspace, this,
            checkpoint = { checkpoints += it }, environmentId = b.encode(),
            executionLease = { id, run -> leases.use(id, b, run) })
        withTimeout(5000) { while (!handle.settled) delay(1) }
        assertEquals(WorkflowRunStatus.SUCCESS, handle.state.value.status)
        assertEquals(b, WorkflowExecutionBinding.decode(checkpoints.first().runtimeEnvironmentId))
        assertTrue(world.closed)
        val denied = scheduler.execute(d, emptyMap(), b.workspace, this, executionLease = { _, _ -> error("target removed") })
        withTimeout(5000) { while (!denied.settled) delay(1) }
        assertEquals(WorkflowRunStatus.FAILED, denied.state.value.status)
    }
    @Test fun failedCloseRetainsOwnerAndRealStoragePinUntilCleanupOnlyRetry() = runBlocking {
        val d = definition(); val b = binding(d); val gate = StorageActivityGate()
        val world = World(ExecutionEnvironmentId("local-proot", "debian", b.workspace, "workflow:retained"))
        world.failCloses = 1
        var opens = 0; var runs = 0
        val leases = WorkflowExecutionLeases(ExecutionEnvironmentFactory { _, _, distro ->
            opens++; assertEquals("debian", distro); world
        }, acquireTargetPin = { id -> gate.acquireTarget(id) { id == "debian" } }) { true }
        assertTrue(runCatching { leases.use("retained", b) { runs++ } }.isFailure)
        assertTrue(leases.hasResources("retained")); assertNotNull(leases.pendingCleanup.value["retained"])
        assertTrue(runCatching { gate.cleanup { fail("maintenance crossed pending cleanup") } }.isFailure)
        assertTrue(runCatching { leases.use("retained", b) { runs++ } }.isFailure)
        assertTrue(leases.retryCleanup("retained")); assertTrue(leases.retryCleanup("retained"))
        assertEquals(1, opens); assertEquals(1, runs); assertEquals(2, world.closeAttempts)
        assertFalse(leases.hasResources("retained")); assertTrue(leases.pendingCleanup.value.isEmpty())
        gate.cleanup { }
    }

    @Test fun cancelledFailedCloseKeepsPinAndOriginalCancellation() = runBlocking {
        val d = definition(); val b = binding(d); val gate = StorageActivityGate()
        val world = World(ExecutionEnvironmentId("local-proot", "debian", b.workspace, "workflow:cancel"))
        world.failCloses = 1
        val leases = WorkflowExecutionLeases(ExecutionEnvironmentFactory { _, _, _ -> world },
            acquireTargetPin = { gate.acquire() }) { true }
        val entered = CompletableDeferred<Unit>()
        val job = launch { leases.use("cancel", b) { entered.complete(Unit); awaitCancellation() } }
        entered.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled); assertTrue(leases.hasResources("cancel"))
        assertTrue(runCatching { gate.cleanup { fail("pending cancelled scope") } }.isFailure)
        leases.retryCleanup("cancel"); gate.cleanup { }
    }

    @Test fun graphMutationAndWrongFactoryTargetNeverReachBusiness() = runBlocking {
        val d = definition(); val b = binding(d); val gate = StorageActivityGate()
        val world = World(ExecutionEnvironmentId("local-proot", "debian", b.workspace, "workflow:graph"))
        val leases = WorkflowExecutionLeases(ExecutionEnvironmentFactory { _, _, _ -> world },
            acquireTargetPin = { gate.acquire() }) { true }
        assertTrue(runCatching { leases.use("graph", b) {
            currentCoroutineContext()[WorkflowExecutionContext]!!.operation(d.copy(name = "mutated")) { fail("changed graph") }
        } }.isFailure)
        assertTrue(world.closed); gate.cleanup { }
        val wrong = World(ExecutionEnvironmentId("local-proot", "ubuntu", b.workspace, "workflow:wrong"))
        val wrongLeases = WorkflowExecutionLeases(ExecutionEnvironmentFactory { _, _, _ -> wrong },
            acquireTargetPin = { gate.acquire() }) { true }
        assertTrue(runCatching { wrongLeases.use("wrong", b) { fail("mutable active target") } }.isFailure)
        assertTrue(wrong.closed); gate.cleanup { }
    }

    @Test fun defaultPinFailsClosedAndTrailingAvailabilityLambdaCompiles() = runBlocking {
        var opened = false
        val leases = WorkflowExecutionLeases(ExecutionEnvironmentFactory { _, _, _ -> opened = true; error("must not open") }) { true }
        assertTrue(runCatching { leases.use("default", binding(definition())) { fail("unpinned run") } }.isFailure)
        assertFalse(opened); assertFalse(leases.hasResources("default"))
    }

    @Test fun productionBindingAcquiresExplicitPinRatherThanLexicalLease() = runBlocking {
        val d = definition(); val b = binding(d); val gate = StorageActivityGate()
        val world = World(ExecutionEnvironmentId("local-proot", "debian", b.workspace, "workflow:factory"))
        world.failCloses = 1
        var pins = 0
        val runtime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) { _, method, args ->
            when (method.name) {
                "acquireExecutionTargetLease" -> { pins++; assertEquals("debian", args!![0]); gate.acquire() }
                "getInstalledDistros" -> kotlinx.coroutines.flow.MutableStateFlow(listOf(top.wkbin.taixu.core.model.InstalledDistro("debian", "Debian")))
                else -> error("unexpected mutable or lexical runtime API: ${method.name}")
            }
        } as LinuxRuntime
        val leases = createWorkflowExecutionLeases(ExecutionEnvironmentFactory { _, _, _ -> world }, runtime)
        assertTrue(runCatching { leases.use("factory", b) { } }.isFailure)
        assertEquals(1, pins)
        var maintenance = false
        assertTrue(runCatching { gate.cleanup { maintenance = true } }.isFailure)
        assertFalse(maintenance)
        leases.retryCleanup("factory")
        gate.cleanup { maintenance = true }
        assertTrue(maintenance)
    }

}
