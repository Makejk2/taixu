package top.wkbin.taixu.harness.websearch

/** 一条联网搜索结果（标题/链接/摘要）。 */
data class SearchHit(
    val title: String,
    val url: String,
    val snippet: String,
)

/**
 * 联网搜索 Provider 抽象：实现方负责端点请求与结果解析。
 * 契约边界：只读网络查询，不得产生工作区副作用；失败以异常抛出，由工具后端统一转译。
 */
interface WebSearchProvider {
    /** Provider 标识（bing / tavily / …），用于日志与后续偏好选择。 */
    val id: String

    /** 返回至多 [count] 条结果；查询词语言由调用方决定（建议与主题一致）。 */
    suspend fun search(query: String, count: Int): List<SearchHit>
}
