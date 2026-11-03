package top.wkbin.taixu.harness

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import top.wkbin.taixu.harness.core.ToolBackend
import top.wkbin.taixu.harness.websearch.SearchHit
import top.wkbin.taixu.harness.websearch.WebSearchProviders

data class SearchWebToolRequest(
    val args: JsonObject,
    val sessionId: String,
    val workspace: String,
    val progressReporter: (suspend (String) -> Unit)? = null,
    val metadata: MutableMap<String, String>? = null,
)

/**
 * Agent 工具后端：联网搜索（search_web 工具）。
 *
 * 职责边界：
 * - 选择 [WebSearchProviders] 中的默认源发起只读查询，把结果格式化为带序号引用的文本；
 * - 输出截断、脱敏等横切关注点由 ToolExecutor 管道层处理；
 * - 无结果/请求失败都以失败 ToolResult 返回，错误文案给模型可行动的下一步建议。
 */
class SearchWebToolBackend(
    private val providers: WebSearchProviders,
    private val defaultProviderId: String = "bing",
) : ToolBackend<SearchWebToolRequest, Pair<Boolean, String>> {

    override suspend fun execute(request: SearchWebToolRequest): Pair<Boolean, String> {
        // query 空白 / count 越界由 JsonArgs 校验直接抛 IllegalArgumentException，管道层转失败结果。
        val query = JsonArgs.requireString(request.args, "query").trim()
        val count = JsonArgs.optionalLong(request.args, "count", DEFAULT_COUNT, MIN_COUNT, MAX_COUNT).toInt()
        val provider = providers.default(defaultProviderId)
        if (request.progressReporter != null) request.progressReporter("正在联网搜索：${query.take(40)}")
        val hits = try {
            provider.search(query, count)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            return false to "搜索失败（${provider.id}）：${failure.message ?: failure::class.simpleName}。" +
                "可稍后重试；若怀疑是网络环境限制，请告知用户检查代理或网络。"
        }
        if (hits.isEmpty()) {
            return false to "搜索「$query」没有返回结果。可尝试换更具体的关键词重试。"
        }
        return true to formatResults(query, hits)
    }

    private fun formatResults(query: String, hits: List<SearchHit>): String = buildString {
        appendLine("搜索「$query」共 ${hits.size} 条结果：")
        hits.forEachIndexed { index, hit ->
            appendLine("[${index + 1}] ${hit.title}")
            appendLine("    ${hit.url}")
            if (hit.snippet.isNotBlank()) appendLine("    ${hit.snippet}")
        }
        append("回答引用这些结果时使用 [n](url) 标注来源；摘要信息不足以回答时，可用 download 抓取对应链接的全文再读。")
    }

    private companion object {
        const val DEFAULT_COUNT = 8L
        const val MIN_COUNT = 1L
        const val MAX_COUNT = 20L
    }
}
