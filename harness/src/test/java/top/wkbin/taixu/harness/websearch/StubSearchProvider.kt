package top.wkbin.taixu.harness.websearch

/** 测试桩：永不返回结果的搜索 Provider（测试构造 ToolExecutor 时占位）。 */
class StubSearchProvider : WebSearchProvider {
    override val id = "stub"
    override suspend fun search(query: String, count: Int): List<SearchHit> = emptyList()
}
