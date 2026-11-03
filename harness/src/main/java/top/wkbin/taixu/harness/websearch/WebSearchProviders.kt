package top.wkbin.taixu.harness.websearch

/**
 * 搜索 Provider 集合载体：Koin verify 不支持泛型集合构造参数，
 * 用具体类型包装以便 DI 图校验，同时收敛「按 id 选默认源」的逻辑。
 */
class WebSearchProviders(val all: List<WebSearchProvider>) {
    init {
        require(all.isNotEmpty()) { "search_web 需要至少一个搜索 Provider" }
    }

    fun default(preferredId: String): WebSearchProvider = all.firstOrNull { it.id == preferredId } ?: all.first()
}
