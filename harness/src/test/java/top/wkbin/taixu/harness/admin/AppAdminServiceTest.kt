package top.wkbin.taixu.harness.admin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import top.wkbin.taixu.core.model.RunMode
import top.wkbin.taixu.core.model.workflow.*
import top.wkbin.taixu.harness.*
class AppAdminServiceTest {
    private class Port : AppAdminPort {
        val rows = linkedMapOf<String, WorkflowDefinition>()
        val runs = linkedMapOf<String, AppAdminRunStatus>()
        var source = AppAdminSource("/workspace/demo", "native-v1", true, true, "model", "variant")
        var rejectIdentity = false
        var writes = 0
        var starts = 0
        var stops = 0
        var lastVariables = emptyMap<String, String>()
        var lastWorkspace = ""
        var cancelStart = false
        var rejectedStart = false
        override suspend fun source(sessionId: String): AppAdminSource {
            require(!rejectIdentity && sessionId in setOf("owner", "other")) { "identity denied" }
            return source
        }
        override suspend fun definitions() = rows.values.toList()
        override suspend fun definition(id: String) = rows[id]
        override suspend fun save(definition: WorkflowDefinition, expectedHash: String) {
            check((rows[definition.id]?.let(::definitionHash) ?: "absent") == expectedHash) { "CAS mismatch" }
            rows[definition.id] = definition; writes++
        }
        override suspend fun delete(id: String, expectedHash: String): Boolean {
            check((rows[id]?.let(::definitionHash) ?: "absent") == expectedHash) { "CAS mismatch" }
            return (rows.remove(id) != null).also { writes++ }
        }
        override suspend fun validateRun(definition: WorkflowDefinition) {
            appAdminValidateNativeRun(definition)
        }
        override suspend fun status(executionId: String) = runs[executionId]
        override suspend fun start(definition: WorkflowDefinition, variables: Map<String, String>, workspace: String): AppAdminStart {
            starts++; lastVariables = variables; lastWorkspace = workspace
            if (cancelStart) throw CancellationException("cancelled")
            runs["real-execution"] = AppAdminRunStatus("real-execution", definition.id, definitionHash(definition),
                if (rejectedStart) "FAILED" else "RUNNING", !rejectedStart)
            return AppAdminStart("real-execution", !rejectedStart)
        }
        override suspend fun stop(executionId: String) {
            stops++; runs[executionId] = runs.getValue(executionId).copy(status = "CANCELLED", active = false)
        }
    }
    private class Fixture {
        val port = Port()
        var now = 100L
        val events = mutableListOf<String>()
        var failAppliedAudit = false
        var onApplying: (() -> Unit)? = null
        val service = AppAdminService(port, AppAdminAudit { _, action, detail ->
            if (failAppliedAudit && action == "applied") error("audit unavailable")
            if (action == "applying") onApplying?.invoke()
            events += "$action:$detail"
        }, { now })
        val backend = AppAdminBackend(service)
        suspend fun propose(op: String, session: String = "owner"): String {
            val output = service.execute(session, args("""{"module":"workflow","action":"propose","reason":"native user task","operation":$op}"""))
            return args(output).getValue("proposalId").jsonPrimitive.content
        }
        suspend fun apply(id: String, session: String = "owner", call: String = "native-call"): String {
            service.prepare(session, id, call)
            return service.applyNative(session, id, call)
        }
        suspend fun gate(id: String, native: Boolean, callId: String = "native-call", mode: RunMode = RunMode.BUILD,
                         request: suspend (ApprovalDecision) -> ToolResult = { error("unexpected approval") }): ToolResult =
            executeAppAdmin(backend, { mode }, ToolCall(callId, now, HarnessTool.READ,
                args("""{"module":"workflow","action":"apply","proposalId":"$id"}""")), "owner", native, now, request)
    }
    private companion object {
        fun args(raw: String) = Json.parseToJsonElement(raw).jsonObject
        fun definition(id: String = "custom") = WorkflowDefinition(id, "Custom",
            nodes = listOf(WorkflowNode("trigger", WorkflowNodeType.TRIGGER, "Trigger")))
        fun save(value: WorkflowDefinition = definition()) = """{"operation":"save","definition":${Json.encodeToString(value)}}"""
        fun run() = """{"operation":"run","workflowId":"custom","workspace":"/workspace/demo","variables":{"SAFE":"hello"}}"""
        suspend fun denied(block: suspend () -> Unit) {
            try { block(); fail("must fail closed") } catch (expected: IllegalArgumentException) { }
            catch (expected: IllegalStateException) { }
        }
    }
    @Test fun nativeEditAfterFinalRefreshCannotBeOverwrittenBySaveOrDelete(): Unit = runBlocking {
        // Race AFTER refresh, at audit. Port test only; Room cases: WorkflowDefinitionCasTest.
        for (operation in listOf("create", "update", "delete")) {
            val f = Fixture()
            if (operation != "create") f.port.rows["custom"] = definition()
            val op = if (operation == "delete") """{"operation":"delete","workflowId":"custom"}"""
                else save(definition().copy(name = "approved save"))
            val id = f.propose(op)
            val nativeEdit = definition().copy(name = "native concurrent edit")
            f.onApplying = { f.port.rows["custom"] = nativeEdit }
            denied { f.apply(id) }
            assertEquals(nativeEdit, f.port.rows["custom"])
            assertEquals(0, f.port.writes)
            assertTrue(f.events.any { it.startsWith("failed_or_partial:") })
            f.onApplying = null
            denied { f.apply(id) } // CAS rejection consumes even if the snapshot later matches.
            assertEquals(nativeEdit, f.port.rows["custom"])
        }
    }
    @Test fun nativeDeletionAfterRefreshCannotBeResurrectedBySave(): Unit = runBlocking {
        val f = Fixture(); f.port.rows["custom"] = definition()
        val id = f.propose(save(definition().copy(name = "approved save")))
        f.onApplying = { f.port.rows.remove("custom") }
        denied { f.apply(id) }
        assertFalse(f.port.rows.containsKey("custom"))
        assertEquals(0, f.port.writes)
        denied { f.apply(id) }
    }
    @Test fun unsupportedNativeNodesFailReadOnlyPreflightBeforeProposalAndAudit(): Unit = runBlocking {
        for (type in listOf(WorkflowNodeType.BASH_COMMAND, WorkflowNodeType.PROCESS_SERVICE,
            WorkflowNodeType.TAIXU_BUILD, WorkflowNodeType.HOST_ACTION)) {
            val f = Fixture()
            val config = when (type) {
                WorkflowNodeType.HOST_ACTION -> mapOf("action" to "status")
                WorkflowNodeType.TAIXU_BUILD -> mapOf("task" to "assembleDebug")
                else -> mapOf("command" to "echo safe")
            }
            val candidate = definition().copy(nodes = listOf(
                WorkflowNode("trigger", WorkflowNodeType.TRIGGER, "Trigger"),
                WorkflowNode("side-effect", type, "Side effect", config = config)))
            // Safety accepts these native definitions; production adapter preflight must reject.
            AppAdminSafety.runnableDefinition(candidate)
            f.port.rows["custom"] = candidate
            denied { f.propose(run()) }
            assertEquals(0, f.port.starts)
            assertEquals(0, f.port.writes)
            assertTrue(f.events.isEmpty())
        }
    }
    @Test fun saveDeleteAreRealAndOneShot(): Unit = runBlocking {
        val f = Fixture()
        val id = f.propose(save())
        assertEquals(0, f.port.writes)
        f.apply(id)
        assertEquals(definition(), f.port.rows["custom"])
        denied { f.service.applyNative("owner", id, "native-call") }
        assertEquals(1, f.port.writes)
        f.apply(f.propose("""{"operation":"delete","workflowId":"custom"}"""))
        assertFalse(f.port.rows.containsKey("custom"))
        assertEquals(2, f.port.writes)
    }
    @Test fun onlyNativeGateCanApplyAndFullAccessCannotSkip(): Unit = runBlocking {
        val f = Fixture()
        val id = f.propose(save())
        assertFalse(f.gate(id, true).success) // fabricated native bit lacks prepared call binding
        var asked = 0
        val paused = f.gate(id, false) { decision ->
            asked++; assertTrue(decision.reason.contains("proposalId=$id"))
            ToolResult("pause", f.now, "native-call", false, "native UI", awaitingApproval = true)
        }
        assertTrue(paused.awaitingApproval)
        assertEquals(1, asked)
        assertEquals(0, f.port.writes)
        assertTrue(f.gate(id, true).success)
        assertEquals(1, f.port.writes)
    }
    @Test fun backendApplyAndModelApprovalFlagsAreRejected(): Unit = runBlocking {
        val f = Fixture(); val id = f.propose(save())
        assertFalse(f.backend.execute("owner", args("""{"module":"workflow","action":"apply","proposalId":"$id"}""")).first)
        for (flag in listOf("approved", "approvedNative", "userConfirmed")) {
            val call = ToolCall("call", 1, HarnessTool.READ, args("""{"module":"workflow","action":"apply","proposalId":"$id","$flag":true}"""))
            val result = executeAppAdmin(f.backend, { RunMode.BUILD }, call, "owner", false, 1) { error("must not ask") }
            assertFalse(result.success)
        }
        assertEquals(0, f.port.writes)
    }
    @Test fun planIsReadOnlyEvenDuringNativeReplay(): Unit = runBlocking {
        val f = Fixture(); val id = f.propose(save())
        assertFalse(f.gate(id, false, mode = RunMode.PLAN).success)
        assertFalse(f.gate(id, true, mode = RunMode.PLAN).success)
        denied { f.apply(id) } // observed PLAN permanently invalidated it
        f.port.source = f.port.source.copy(build = false)
        denied { f.propose(save()) }
        assertTrue(f.backend.execute("owner", args("""{"module":"workflow","action":"list"}""")).first)
        assertEquals(0, f.port.writes)
    }
    @Test fun identityAndSessionAndCallAreBound(): Unit = runBlocking {
        val f = Fixture(); val id = f.propose(save())
        denied { f.service.prepare("other", id, "call") }
        denied { f.service.prepare("", id, "call") }
        f.service.prepare("owner", id, "original-call")
        denied { f.service.applyNative("owner", id, "new-call") }
        f.port.rejectIdentity = true
        denied { f.service.applyNative("owner", id, "original-call") }
        f.port.rejectIdentity = false
        denied { f.service.applyNative("owner", id, "original-call") } // observed identity loss consumed it
        assertFalse(f.backend.execute("member", args("""{"module":"workflow","action":"list"}""")).first)
        assertEquals(0, f.port.writes)
    }
    @Test fun ttlAndClockRollbackFailClosed(): Unit = runBlocking {
        for (delta in listOf(AppAdminService.TTL + 1, -1L)) {
            val f = Fixture(); val id = f.propose(save()); f.now += delta
            denied { f.apply(id) }
            f.now = 100
            denied { f.apply(id) }
            assertEquals(0, f.port.writes)
        }
    }
    @Test fun sourceAndDefinitionMismatchConsumePermanently(): Unit = runBlocking {
        val f = Fixture(); val id = f.propose(save())
        f.port.source = f.port.source.copy(fingerprint = "changed-model-or-authority")
        denied { f.apply(id) }
        f.port.source = f.port.source.copy(fingerprint = "native-v1")
        denied { f.apply(id) }
        val next = f.propose(save())
        f.service.prepare("owner", next, "call")
        f.port.rows["custom"] = definition().copy(name = "concurrent edit")
        denied { f.service.applyNative("owner", next, "call") }
        f.port.rows.clear()
        denied { f.apply(next) }
        assertEquals(0, f.port.writes)
    }
    @Test fun runFreezesWorkspaceDefinitionVariablesAndReturnsExecutionId(): Unit = runBlocking {
        val f = Fixture(); f.port.rows["custom"] = definition()
        val id = f.propose(run()); val result = args(f.apply(id))
        assertEquals("real-execution", result.getValue("executionId").jsonPrimitive.content)
        assertEquals("/workspace/demo", f.port.lastWorkspace)
        assertEquals(mapOf("SAFE" to "hello", "WORKFLOW_MODEL_ID" to "model", "WORKFLOW_MODEL_VARIANT" to "variant"), f.port.lastVariables)
        assertEquals(1, f.port.starts)
        denied { f.apply(id) }
    }
    @Test fun stopUsesManagerPortAndKeepsHistory(): Unit = runBlocking {
        val f = Fixture(); f.port.rows["custom"] = definition(); f.apply(f.propose(run()))
        f.apply(f.propose("""{"operation":"stop","executionId":"real-execution"}"""))
        assertEquals(1, f.port.stops)
        assertEquals("CANCELLED", f.port.runs.getValue("real-execution").status)
        assertTrue(f.port.rows.containsKey("custom"))
        val output = f.service.execute("owner", args("""{"module":"workflow","action":"status","executionId":"real-execution"}"""))
        assertTrue(output.contains("CANCELLED"))
    }
    @Test fun builtinAndUnknownParametersAndSecretsAreRejected(): Unit = runBlocking {
        val f = Fixture(); f.port.rows["custom"] = definition().copy(isBuiltin = true)
        denied { f.propose(save()) }
        denied { f.propose("""{"operation":"delete","workflowId":"custom"}""") }
        denied { f.propose(save(definition().copy(isBuiltin = true))) }
        f.port.rows["custom"] = definition()
        denied { f.propose(run().replace("SAFE", "API_KEY")) }
        denied { f.propose(run().replace("hello", "Bearer sensitive")) }
        denied { f.propose(run().replace("SAFE", "WORKFLOW_MODEL_ID")) }
        denied { f.propose(run().replace("/workspace/demo", "/workspace/elsewhere")) }
        denied { f.propose("""{"operation":"save","definition":${Json.encodeToString(definition())},"approved":true}""") }
        denied { f.service.execute("owner", args("""{"module":"workflow","action":"list","extra":true}""")) }
        assertEquals(0, f.port.writes)
    }
    @Test fun readNeverExportsConfigVariablesOrLogs(): Unit = runBlocking {
        val f = Fixture()
        f.port.rows["custom"] = definition().copy(defaultVariables = mapOf("secret_name" to "secret-value"),
            nodes = listOf(WorkflowNode("n", WorkflowNodeType.TRIGGER, "Hidden", config = mapOf("prompt" to "secret-prompt"))))
        for (action in listOf("""{"module":"workflow","action":"list"}""", """{"module":"workflow","action":"get","workflowId":"custom"}""")) {
            val output = f.service.execute("owner", args(action))
            assertFalse(output.contains("secret-value")); assertFalse(output.contains("secret_name")); assertFalse(output.contains("secret-prompt"))
        }
    }
    @Test fun cancellationAndAuditFailureNeverReplayOrClaimZeroEffects(): Unit = runBlocking {
        val f = Fixture(); f.port.rows["custom"] = definition(); f.port.cancelStart = true
        val id = f.propose(run())
        try { f.apply(id); fail("expected cancellation") } catch (_: CancellationException) { }
        denied { f.apply(id) }
        assertEquals(1, f.port.starts)
        assertTrue(f.events.any { it.startsWith("cancelled_or_partial:") })
        val saved = f.propose(save(definition("second"))); f.failAppliedAudit = true
        val output = f.apply(saved)
        assertTrue(output.contains("变更已执行"))
        assertTrue(f.port.rows.containsKey("second"))
        denied { f.apply(saved) }
    }
    @Test fun disableInvalidatesPreparedProposalsAndUnknownModulesFail(): Unit = runBlocking {
        val f = Fixture(); val id = f.propose(save()); f.service.prepare("owner", id, "call")
        f.backend.disableManagement()
        denied { f.service.applyNative("owner", id, "call") }
        assertFalse(f.backend.execute("owner", args("""{"module":"unknown","action":"list"}""")).first)
        assertEquals(0, f.port.writes)
    }
    @Test fun unsupportedNativeRunAndPluginNodesFailClosed(): Unit = runBlocking {
        val f = Fixture(); f.port.rows["custom"] = definition()
        f.port.source = f.port.source.copy(runSupported = false)
        denied { f.propose(run()) }
        denied { f.propose(save(definition().copy(nodes = listOf(WorkflowNode("plugin", WorkflowNodeType.PLUGIN_ACTION, "Plugin",
            config = mapOf("plugin" to "app.plugin", "extension" to "app.action")))))) }
        assertEquals(0, f.port.starts)
    }
    @Test fun agentAndDelegateRunAreRefusedWithoutPermissionEscalation(): Unit = runBlocking {
        for (type in listOf(WorkflowNodeType.AGENT_INFERENCE, WorkflowNodeType.SUBAGENT_DELEGATE)) {
            val f = Fixture()
            val value = definition().copy(nodes = listOf(WorkflowNode("agent", type, "Agent", config = mapOf("prompt" to "hello"))))
            // A saved Agent definition must not imply that this surface may run it.
            f.port.rows["custom"] = value
            denied { f.propose(run()) }
            assertEquals(0, f.port.starts)
            assertEquals(0, f.port.writes)
        }
    }
    @Test fun localTargetMustBeExplicitBoundAndMatchNativeRuntime() {
        val native = AppAdminNativeSourceState("model", "runtime", true, "model", "variant", "debian")
        fun supported(target: String?, state: AppAdminNativeSourceState = native) =
            appAdminLocalRunSupported("/workspace/demo", target, state)
        val local = """{"distribution":"debian","workspace":"/workspace/demo"}"""
        assertTrue(supported(local))
        for (target in listOf(null, "unbound", "restore-required", "{}", local.replace("debian", "ubuntu"),
            local.replace("/workspace/demo", "/workspace/other"),
            """{"distribution":"ssh:remote","workspace":"/workspace/demo"}""")) assertFalse(supported(target))
        assertFalse(supported(local, native.copy(localDistributionId = null)))
        assertFalse(supported(local, native.copy(supportsRun = false)))
        assertFalse(supported(local, native.copy(resolvedModelId = null)))
    }
    @Test fun nodeApprovalAndResumeActionsAreNotModelCallable(): Unit = runBlocking {
        val f = Fixture()
        for (action in listOf("decide", "resume", "retry", "approve")) {
            assertFalse(f.backend.execute("owner", args("""{"module":"workflow","action":"$action"}""")).first)
        }
        assertEquals(0, f.port.starts)
        assertEquals(0, f.port.writes)
    }
    @Test fun productionSourceBoundaryRequiresExplicitLocalReadyAndStableModelIdentity() {
        val workspace = "/workspace/demo"
        val local = """{"distribution":"debian","workspace":"/workspace/demo"}"""
        fun bound(target: String? = local, distro: String = "debian", ready: Boolean = true,
                  modelId: String? = "saved-profile", variant: String? = "concrete-model",
                  model: String = "public-model-hash") =
            appAdminBoundNativeSource(workspace, target, distro, ready, model, modelId, variant)
        val good = bound()
        assertTrue(good.supportsRun)
        assertTrue(appAdminLocalRunSupported(workspace, local, good))
        assertEquals("saved-profile", good.resolvedModelId)
        assertEquals("concrete-model", good.resolvedModelVariant)
        val remote = """{"distribution":"ssh:server","workspace":"/workspace/demo","remote":{"id":"server","name":"Server","host":"example.invalid","username":"user","workspace":"/workspace/demo"}}"""
        for (target in listOf(null, "unbound", "restore-required", "{}", remote,
            local.replace("debian", "ubuntu"), local.replace("/workspace/demo", "/workspace/other"),
            local.replace("/workspace/demo", "/workspace/../demo"))) assertFalse(bound(target).supportsRun)
        assertFalse(bound(ready = false).supportsRun)
        assertFalse(bound(distro = "").supportsRun)
        assertFalse(bound(distro = "ssh:server").supportsRun)
        assertFalse(bound(modelId = null).supportsRun)
        assertFalse(bound(modelId = "").supportsRun)
        assertFalse(bound(variant = null).supportsRun)
        assertFalse(bound(variant = "").supportsRun)
        assertNotEquals(good.environmentFingerprint, bound(ready = false).environmentFingerprint)
        assertNotEquals(good.environmentFingerprint, bound(distro = "ubuntu").environmentFingerprint)
        assertNotEquals(good.modelFingerprint, bound(model = "changed-public-model-hash").modelFingerprint)
    }
    @Test fun guiPilotAndExpansionCannotBecomeRunnableThroughAppAdmin(): Unit = runBlocking {
        val f = Fixture()
        val gui = WorkflowNode("gui", WorkflowNodeType.HOST_ACTION, "GUI", config = mapOf("action" to "gui_pilot", "goal" to "test"))
        f.port.rows["custom"] = definition().copy(nodes = listOf(gui))
        denied { f.propose(run()) }
        val expanded = definition().copy(nodes = listOf(WorkflowNode("trigger", WorkflowNodeType.TRIGGER,
            "Trigger", config = mapOf(WorkflowExpansions.SOURCE_KEY to "anything"))))
        denied { f.propose(save(expanded)) }
        assertEquals(0, f.port.starts)
        assertEquals(0, f.port.writes)
    }
    @Test fun publicModelOrEnvironmentSnapshotChangePermanentlyConsumesPreparedRun(): Unit = runBlocking {
        for (changed in listOf("public-model-change", "native-runtime-change")) {
            val f = Fixture(); f.port.rows["custom"] = definition()
            val id = f.propose(run())
            f.service.prepare("owner", id, "native-call")
            f.port.source = f.port.source.copy(fingerprint = changed)
            denied { f.service.applyNative("owner", id, "native-call") }
            f.port.source = f.port.source.copy(fingerprint = "native-v1")
            denied { f.service.applyNative("owner", id, "native-call") }
            assertEquals(0, f.port.starts)
        }
    }
    @Test fun boundLocalSourceRunsButRemoteAndUnknownSourcesNeverStart(): Unit = runBlocking {
        val local = """{"distribution":"debian","workspace":"/workspace/demo"}"""
        val remote = """{"distribution":"ssh:server","workspace":"/workspace/demo","remote":{"id":"server","name":"Server","host":"example.invalid","username":"user","workspace":"/workspace/demo"}}"""
        for (target in listOf(local, remote, null, "{}")) {
            val f = Fixture(); f.port.rows["custom"] = definition()
            val native = appAdminBoundNativeSource("/workspace/demo", target, "debian", true,
                "public-model-hash", "saved-profile", "concrete-model")
            f.port.source = f.port.source.copy(runSupported = native.supportsRun,
                modelId = native.resolvedModelId, modelVariant = native.resolvedModelVariant)
            if (target == local) {
                f.apply(f.propose(run()))
                assertEquals(1, f.port.starts)
                assertEquals("/workspace/demo", f.port.lastWorkspace)
                assertEquals("saved-profile", f.port.lastVariables["WORKFLOW_MODEL_ID"])
                assertEquals("concrete-model", f.port.lastVariables["WORKFLOW_MODEL_VARIANT"])
            } else {
                denied { f.propose(run()) }
                assertEquals(0, f.port.starts)
            }
        }
    }
    @Test fun nonAiHostAndHumanApprovalDefinitionsRemainNativelyRunnable() {
        for (node in listOf(
            WorkflowNode("host", WorkflowNodeType.HOST_ACTION, "Host", config = mapOf("action" to "status")),
            WorkflowNode("approval", WorkflowNodeType.HUMAN_APPROVAL, "Native approval"))) {
            AppAdminSafety.runnableDefinition(definition().copy(nodes = listOf(node)))
        }
    }
}
