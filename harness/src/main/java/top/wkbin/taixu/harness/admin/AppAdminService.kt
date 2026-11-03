package top.wkbin.taixu.harness.admin

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import top.wkbin.taixu.core.model.workflow.WorkflowDefinition

/** In-memory, immutable, owner/call-bound one-shot authority; restart cannot restore approval. */
internal class AppAdminService(
    private val port: AppAdminPort,
    private val audit: AppAdminAudit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Proposal(
        val id: String, val session: String, val reason: String, val operation: JsonObject,
        val source: AppAdminSource, val before: String, val createdAt: Long,
        val definition: WorkflowDefinition?, val variables: Map<String, String>,
        var pendingCall: String? = null, var consumed: Boolean = false,
    )
    private val lock = Mutex()
    private val proposals = linkedMapOf<String, Proposal>()

    suspend fun disableManagement() = lock.withLock { proposals.values.forEach { it.consumed = true } }
    suspend fun invalidateSession(session: String) = lock.withLock {
        proposals.values.filter { it.session == session }.forEach { it.consumed = true; it.pendingCall = null }
    }

    suspend fun execute(session: String, args: JsonObject): String = lock.withLock {
        val source = source(session)
        require(adminText(args, "module") == "workflow") { "不支持的模块" }
        val action = adminText(args, "action")
        when (action) {
            "list" -> {
                adminKeys(args, setOf("module", "action"))
                buildJsonObject { put("workflows", buildJsonArray {
                    port.definitions().take(200).forEach { add(AppAdminSafety.summary(it)) }
                }); put("limit", 200) }.toString()
            }
            "get" -> {
                adminKeys(args, setOf("module", "action", "workflowId"))
                AppAdminSafety.summary(requireNotNull(port.definition(adminText(args, "workflowId"))) { "工作流不存在" }).toString()
            }
            "status" -> {
                adminKeys(args, setOf("module", "action", "executionId"))
                AppAdminSafety.status(requireNotNull(port.status(adminText(args, "executionId"))) { "运行不存在" }).toString()
            }
            "propose" -> {
                adminKeys(args, setOf("module", "action", "reason", "operation"))
                require(source.build) { "PLAN 只读，不允许提案" }
                val reason = adminText(args, "reason")
                require(reason.length <= 1000) { "原因过长" }
                val op = args["operation"] as? JsonObject ?: error("operation 必须为对象")
                // Deep freeze JSON before validation; neither caller nor native replay can replace it.
                val frozen = Json.parseToJsonElement(op.toString()).jsonObject
                val (definition, variables) = validate(frozen, source)
                val before = snapshot(frozen)
                proposals.entries.removeAll { it.value.consumed || clock() - it.value.createdAt !in 0..TTL }
                require(proposals.size < 100) { "待审批提案过多" }
                val p = Proposal(UUID.randomUUID().toString(), session, reason, frozen, source, before,
                    clock(), definition, variables.toMap())
                audit.record(session, "proposed", auditDetail(p))
                proposals[p.id] = p
                buildJsonObject {
                    put("proposalId", p.id); put("expiresAt", p.createdAt + TTL)
                    put("operationHash", adminHash(frozen.toString())); put("snapshotHash", adminHash(before))
                    put("note", "仅创建提案，未执行。apply 必须通过逐次原生审批；FULL_ACCESS 不豁免。")
                }.toString()
            }
            "apply" -> error("apply 只能经过原生 AppAdminExecutionGate")
            else -> error("不支持的 action")
        }
    }

    private suspend fun source(session: String): AppAdminSource {
        require(session.isNotBlank()) { "必须绑定原生会话" }
        return try {
            port.source(session).also { current ->
                require(current.fingerprint.isNotBlank()) { "无法核验原生来源" }
                proposals.values.filter { it.session == session && it.source != current }.forEach {
                    it.consumed = true; it.pendingCall = null
                }
            }
        } catch (e: Exception) {
            proposals.values.filter { it.session == session }.forEach { it.consumed = true; it.pendingCall = null }
            throw e
        }
    }
    private fun proposal(session: String, id: String): Proposal {
        val p = requireNotNull(proposals[id]) { "提案不存在或已清理" }
        require(p.session == session) { "提案不属于当前会话" }
        require(!p.consumed) { "提案已消费，禁止重放" }
        if (clock() - p.createdAt !in 0..TTL) {
            p.consumed = true
            error("提案过期且已消费")
        }
        return p
    }
    private suspend fun refresh(p: Proposal) {
        try {
            val current = source(p.session)
            require(!p.consumed && current.build && current == p.source) { "来源工作区、模型、环境或权限已变化" }
            require(snapshot(p.operation) == p.before) { "工作流定义或运行快照已变化" }
            validate(p.operation, current)
        } catch (e: Exception) {
            p.consumed = true
            p.pendingCall = null
            // No raw exception, commands, variables or definitions in the journal/model output.
            if (e is CancellationException) throw e
            error("提案快照失配或不可验证，已永久消费；请重新查询并提案")
        }
    }
    suspend fun prepare(session: String, id: String, callId: String): String = lock.withLock {
        require(callId.isNotBlank()) { "缺少原生调用身份" }
        val p = proposal(session, id)
        refresh(p)
        require(p.pendingCall == null || p.pendingCall == callId) { "提案已绑定其他调用" }
        p.pendingCall = callId
        audit.record(session, "approval_requested", auditDetail(p))
        buildString {
            appendLine("工作流原生变更审批（逐次；不包含节点审批权限）")
            appendLine("proposalId=${p.id}; session=${p.session}; callId=$callId")
            appendLine("operation=${adminText(p.operation, "operation")}; expiresAt=${p.createdAt + TTL}")
            appendLine("reason=${p.reason}")
            if (adminText(p.operation, "operation") == "delete") {
                appendLine("警告：删除定义会按现有数据库外键级联删除该定义的执行历史；停止不会删除历史。")
            }
            appendLine("sourceHash=${adminHash(p.source.toString())}; workspace=${p.source.workspace}")
            appendLine("definition/target snapshotHash=${adminHash(p.before)}")
            appendLine("operationHash=${adminHash(p.operation.toString())}")
            p.operation["workflowId"]?.let { appendLine("workflowId=${it.jsonPrimitive.content}") }
            p.operation["executionId"]?.let { appendLine("executionId=${it.jsonPrimitive.content}") }
            p.definition?.let { appendLine("workflowId=${it.id}; name=${it.name}") }
            p.definition?.let { appendLine("definitionHash=${definitionHash(it)}; nodes=${it.nodes.size}; variables=${p.variables.size}（值不外发）") }
            append("保存/删除/运行/停止都可能产生持久副作用；运行返回受理 ID，不代表完成。停止保留历史。")
        }
    }
    suspend fun applyNative(session: String, id: String, callId: String): String = lock.withLock {
        val p = proposal(session, id)
        require(p.pendingCall == callId && callId.isNotBlank()) { "无匹配调用的原生审批，不执行" }
        refresh(p)
        // Consume BEFORE audit/mutation. No failed/cancelled/partial path can automatically replay.
        p.consumed = true
        p.pendingCall = null
        audit.record(session, "applying", auditDetail(p)) // failure here is known to precede mutation
        val output = try {
            when (adminText(p.operation, "operation")) {
                "save" -> { port.save(requireNotNull(p.definition), p.before); "已保存工作流；未运行" }
                "delete" -> {
                    check(port.delete(adminText(p.operation, "workflowId"), p.before)) { "删除未被仓库确认" }
                    "已删除自定义工作流；现有仓库按外键级联删除该定义的历史，不会声称保留历史"
                }
                "run" -> {
                    val started = port.start(requireNotNull(p.definition), p.variables, p.source.workspace)
                    buildJsonObject {
                        put("executionId", started.executionId); put("accepted", started.accepted)
                        put("note", if (started.accepted) "原生受理，非完成；请 status 查询，节点审批仍走原生 UI" else "原生拒绝启动；可能已记录 FAILED 历史，请 status 核对")
                    }.toString()
                }
                "stop" -> {
                    port.stop(adminText(p.operation, "executionId"))
                    "已请求取消当前运行，保留历史；status 核对最终状态，不自动重跑"
                }
                else -> error("不支持的操作")
            }
        } catch (e: Exception) {
            // NonCancellable only for bounded local audit; preserve cancellation semantics.
            recordOutcome(session, if (e is CancellationException) "cancelled_or_partial" else "failed_or_partial", p)
            if (e is CancellationException) throw e
            error("提案已消费；可能存在部分副作用，请 list/get/status 核对，不得重放")
        }
        val audited = recordOutcome(session, "applied", p)
        output + if (audited) "" else "\n变更已执行，但完成审计写入失败；请人工核对，禁止重放。"
    }
    private suspend fun recordOutcome(session: String, action: String, p: Proposal): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            try { audit.record(session, action, auditDetail(p)); true } catch (_: Exception) { false }
        }
    private fun auditDetail(p: Proposal): String = buildJsonObject {
        put("proposalId", p.id); put("operation", adminText(p.operation, "operation"))
        put("operationHash", adminHash(p.operation.toString())); put("snapshotHash", adminHash(p.before))
        put("sourceHash", adminHash(p.source.toString())); put("reasonHash", adminHash(p.reason))
    }.toString()

    private suspend fun snapshot(op: JsonObject): String = when (adminText(op, "operation")) {
        "save" -> port.definition(definition(op).id)?.let(::definitionHash) ?: "absent"
        "delete", "run" -> definitionHash(requireNotNull(port.definition(adminText(op, "workflowId"))) { "工作流不存在" })
        "stop" -> requireNotNull(port.status(adminText(op, "executionId"))) { "运行不存在" }.toString()
        else -> error("未知操作")
    }
    private fun definition(op: JsonObject): WorkflowDefinition = Json.decodeFromString(
        (op["definition"] as? JsonObject ?: error("definition 必须为对象")).toString())
    private suspend fun validate(op: JsonObject, source: AppAdminSource): Pair<WorkflowDefinition?, Map<String, String>> {
        require(source.build) { "PLAN 只读" }
        when (adminText(op, "operation")) {
            "save" -> {
                adminKeys(op, setOf("operation", "definition"))
                val next = definition(op)
                require(!next.isBuiltin && top.wkbin.taixu.core.model.workflow.BuiltinWorkflows.all.none { it.id == next.id } && port.definition(next.id)?.isBuiltin != true) { "内置工作流不可由对话覆盖；请另存新 ID" }
                AppAdminSafety.definition(next)
                return next to emptyMap()
            }
            "delete" -> {
                adminKeys(op, setOf("operation", "workflowId"))
                require(!requireNotNull(port.definition(adminText(op, "workflowId"))) { "工作流不存在" }.isBuiltin) { "禁止删除内置工作流" }
            }
            "run" -> {
                adminKeys(op, setOf("operation", "workflowId", "workspace", "variables"))
                require(source.runSupported) { "当前原生执行目标/模型尚不支持工作流运行" }
                require(adminText(op, "workspace") == source.workspace && source.workspace.startsWith("/") &&
                    source.workspace.split('/').none { it == ".." || it == "." } && !source.workspace.contains('\u0000')) { "工作区必须显式匹配原生来源" }
                val definition = requireNotNull(port.definition(adminText(op, "workflowId"))) { "工作流不存在" }
                AppAdminSafety.runnableDefinition(definition)
                port.validateRun(definition)
                val raw = op["variables"] as? JsonObject ?: error("variables 必须为字符串对象")
                val supplied = raw.mapValues { (_, v) ->
                    require(v is JsonPrimitive && v.isString) { "变量必须为字符串" }; v.content
                }
                require((definition.defaultVariables.keys + supplied.keys).none {
                    it.startsWith("WORKFLOW_MODEL_")
                }) { "模型变量只能由原生来源冻结，禁止模型覆盖" }
                definition.nodes.forEach { node ->
                    node.config["modelId"]?.let { require(it == source.modelId) { "节点模型与来源不一致" } }
                    node.config["modelVariant"]?.let { require(it == source.modelVariant) { "节点模型变体与来源不一致" } }
                }
                val variables = definition.defaultVariables + supplied
                AppAdminSafety.variables(variables)
                val frozenModel = buildMap<String, String> {
                    source.modelId?.let { put("WORKFLOW_MODEL_ID", it) }
                    source.modelVariant?.let { put("WORKFLOW_MODEL_VARIANT", it) }
                }
                return definition to (variables + frozenModel)
            }
            "stop" -> {
                adminKeys(op, setOf("operation", "executionId"))
                require(requireNotNull(port.status(adminText(op, "executionId"))) { "运行不存在" }.active) { "运行不再具有活动原生句柄，不能声称已停止" }
            }
            else -> error("不支持的 operation")
        }
        return null to emptyMap()
    }
    companion object { const val TTL = 5 * 60_000L }
}
