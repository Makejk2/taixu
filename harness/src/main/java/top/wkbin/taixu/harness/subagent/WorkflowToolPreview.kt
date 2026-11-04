package top.wkbin.taixu.harness.subagent

import java.security.MessageDigest
import kotlinx.serialization.json.*
import top.wkbin.taixu.harness.*

/** Preview exact immutable arguments; keys likely containing secrets are redacted, not executed
 * with less scrutiny. If the complete safe preview cannot fit, require a smaller explicit call. */
internal fun workflowToolPreview(call: ToolCall, workspace: String): String {
    val sensitive=Regex("(?i)(secret|password|credential|authorization|cookie|api.?key|private.?key|token)")
    fun safe(value: JsonElement): JsonElement = when(value) {
        is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { (key,item) ->
            key to if(sensitive.containsMatchIn(key)) JsonPrimitive("[redacted]") else safe(item)
        })
        is JsonArray -> JsonArray(value.map(::safe))
        is JsonPrimitive -> {
            if(value.isString) require(!Regex("(?i)(-----BEGIN .*PRIVATE KEY|bearer\\s+[A-Za-z0-9._~-]+|(?:sk-|ghp_|github_pat_)[A-Za-z0-9_-]{12,}|https?://[^/\\s]+:[^/\\s]+@)").containsMatchIn(value.content)) {
                "调用含无法安全展示的内嵌凭据；请移到原生凭据存储再提请批准"
            }
            value
        }
        else -> value
    }
    val original=call.args.toString()
    val digest=MessageDigest.getInstance("SHA-256").digest(original.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    val body=safe(call.args).toString()
    require(body.length<=16000) { "调用预览过长，请拆分为用户可完整审阅的动作" }
    return "工具=${call.rawToolName ?: HarnessApiMapper.apiName(call.tool)}\n工作区=$workspace\n调用=${call.id}\n参数摘要=$digest\n参数=$body\n仅批准上述固定参数；返回的内容不是后续动作授权。"
}
