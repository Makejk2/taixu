package top.wkbin.taixu.harness.admin

import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import top.wkbin.taixu.core.model.workflow.*

internal fun adminHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun definitionHash(value: WorkflowDefinition) = adminHash(Json.encodeToString(value))
internal fun adminText(obj: JsonObject, key: String): String {
    val value = obj[key] as? JsonPrimitive ?: error("缺少字符串字段：$key")
    require(value.isString && value.content.isNotBlank()) { "字段必须为非空字符串：$key" }
    return value.content
}
internal fun adminKeys(obj: JsonObject, allowed: Set<String>, required: Set<String> = allowed) {
    require(obj.keys.all { it in allowed } && required.all { it in obj }) { "参数未知或缺失；禁止自证批准和附加权限字段" }
}
internal object AppAdminSafety {
    private val sensitiveKey = Regex("(?i)(secret|token|password|passwd|credential|authorization|cookie|api[_-]?key|private[_-]?key|access[_-]?key)")
    private val sensitiveValue = Regex("(?i)(-----BEGIN .*PRIVATE KEY|bearer\\s+|(?:sk|ghp|github_pat|AKIA)[-_A-Za-z0-9]{12,}|https?://[^/\\s]+:[^/\\s]+@|(?:password|token|secret|api[_-]?key)\\s*[:=])")
    fun variables(values: Map<String, String>) {
        require(values.size <= 64) { "变量过多" }
        values.forEach { (key, value) ->
            require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) && !sensitiveKey.containsMatchIn(key)) { "禁止凭据变量" }
            require(value.length <= 8192 && !sensitiveValue.containsMatchIn(value)) { "变量包含敏感值或过长；请改用原生工作流界面" }
        }
    }
    fun definition(value: WorkflowDefinition) {
        require(value.nodes.size <= 100 && value.edges.size <= 400) { "工作流过大" }
        require(Json.encodeToString(value).length <= 128_000) { "定义过大" }
        val issues = WorkflowValidator.validate(value)
        require(issues.isEmpty()) { "定义未通过内置验证器（${issues.size} 项）；请在原生编辑器核对" }
        // Plugin discovery/expansion requires separate native admission; this surface does not claim it.
        require(value.nodes.none { it.type == WorkflowNodeType.PLUGIN_ACTION || WorkflowExpansions.SOURCE_KEY in it.config }) { "此控制面不支持插件动作或动态展开定义" }
        variables(value.defaultVariables)
        fun inspect(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, child) ->
                    require(!sensitiveKey.containsMatchIn(key)) { "定义含敏感字段" }
                    inspect(child)
                }
                is JsonArray -> element.forEach(::inspect)
                is JsonPrimitive -> require(!sensitiveValue.containsMatchIn(element.content)) { "定义含疑似凭据，拒绝保存或运行" }
                else -> Unit
            }
        }
        inspect(Json.encodeToJsonElement(value))
    }
    /** Current native Agent port creates FULL_ACCESS sessions and cannot inherit source policy.
     * Refuse execution here rather than silently escalating. Saving a definition is not running it.
     */
    fun runnableDefinition(value: WorkflowDefinition) {
        definition(value)
        rejectUnboundGui(value)
        require(value.nodes.none { it.type in setOf(WorkflowNodeType.AGENT_INFERENCE, WorkflowNodeType.SUBAGENT_DELEGATE) }) {
            "此控制面暂不支持 Agent/子智能体节点运行：原生执行器不能可信继承来源审批权限"
        }
    }
    /** GUI pilot resolves its own LLM and cannot attest the caller's policy. Static host actions
     * still run in the native workflow executor; node approvals remain native-UI-only.
     */
    private fun rejectUnboundGui(value: WorkflowDefinition) {
        require(value.nodes.none { it.type == WorkflowNodeType.HOST_ACTION &&
            it.config["action"]?.trim() == "gui_pilot" }) {
            "此控制面暂不支持 GUI Agent：来源模型和审批权限不可可信继承"
        }
    }
    /** Deliberately no configs, variable names/values, prompts, commands, logs, context or errors. */
    fun summary(value: WorkflowDefinition): JsonObject = buildJsonObject {
        put("id", value.id); put("name", value.name); put("isBuiltin", value.isBuiltin)
        put("definitionHash", definitionHash(value)); put("updatedAt", value.updatedAt)
        put("nodes", buildJsonArray { value.nodes.forEach { node -> add(buildJsonObject {
            put("id", node.id); put("type", node.type.name)
        }) } })
        put("edgeCount", value.edges.size)
        put("redacted", "config/defaultVariables/prompts/commands/credentials omitted")
    }
    fun status(value: AppAdminRunStatus): JsonObject = buildJsonObject {
        put("executionId", value.executionId); put("workflowId", value.workflowId)
        put("definitionHash", value.definitionHash); put("status", value.status); put("active", value.active)
        value.startedAt?.let { put("startedAt", it) }; value.finishedAt?.let { put("finishedAt", it) }
        put("note", "持久状态/活动句柄快照；不返回日志、错误、变量或审批令牌。节点审批仍由原生 UI 处理。")
    }
}

/** Shared pure boundary used by the production binding and tests. Parsing is strict and never
 * captures the active local distro as a replacement for a missing/remote session execution target.
 */
internal fun appAdminBoundNativeSource(
    workspace: String,
    targetJson: String?,
    distribution: String,
    ready: Boolean,
    modelFingerprint: String,
    modelId: String?,
    modelVariant: String?,
): AppAdminNativeSourceState {
    val native = AppAdminNativeSourceState(
        modelFingerprint = modelFingerprint,
        environmentFingerprint = adminHash(buildJsonObject {
            put("distribution", distribution); put("ready", ready)
            put("target", targetJson); put("workspace", workspace)
        }.toString()),
        supportsRun = ready && distribution.isNotBlank() && !distribution.startsWith("ssh:"),
        resolvedModelId = modelId?.takeIf { it.isNotBlank() },
        resolvedModelVariant = modelVariant?.takeIf { it.isNotBlank() },
        localDistributionId = distribution.takeIf { it.isNotBlank() && !it.startsWith("ssh:") },
    )
    return native.copy(supportsRun = appAdminLocalRunSupported(workspace, targetJson, native))
}
