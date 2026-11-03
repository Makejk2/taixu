package top.wkbin.taixu.harness.websearch

import java.net.URLEncoder
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Bing 网页搜索（免 Key 兜底源）：直接抓取 bing.com 搜索结果页并解析 b_algo 结果块。
 *
 * 解析基于 Bing 长期稳定的 `<li class="b_algo">` 结果结构，属尽力而为：
 * 反爬拦截 / 结构改版时返回空列表，由工具后端向模型给出可行动的错误说明。
 */
class BingSearchProvider(
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO,
    private val endpoint: String = "https://www.bing.com/search",
) : WebSearchProvider {
    override val id = "bing"

    override suspend fun search(query: String, count: Int): List<SearchHit> = withContext(ioDispatcher) {
        val url = "$endpoint?q=${URLEncoder.encode(query, Charsets.UTF_8)}" +
            "&count=$count&setmkt=zh-CN&setlang=zh-hans"
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            )
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            parseBingResults(response.body?.string().orEmpty(), count)
        }
    }
}

private val H2_BLOCK = Regex("""<h2[^>]*>(.*?)</h2""", RegexOption.DOT_MATCHES_ALL)
private val HREF = Regex("""href="([^"]+)"""")
private val PARAGRAPH = Regex("""<p[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
private val TAGS = Regex("""<[^>]+>""")
private val NUMERIC_ENTITY = Regex("""&#(\d+);""")

internal fun parseBingResults(html: String, count: Int): List<SearchHit> {
    val hits = mutableListOf<SearchHit>()
    val blocks = html.split("""<li class="b_algo"""").drop(1)
    for (block in blocks) {
        if (hits.size >= count) break
        val scope = block.substringBefore("</li>")
        val anchor = H2_BLOCK.find(scope)?.groupValues?.get(1) ?: continue
        val url = HREF.find(anchor)?.groupValues?.get(1)?.takeIf { it.startsWith("http") } ?: continue
        val title = decodeHtmlEntities(anchor.replace(TAGS, "")).trim()
        if (title.isEmpty()) continue
        val snippet = PARAGRAPH.find(scope)?.groupValues?.get(1)
            ?.let { decodeHtmlEntities(it.replace(TAGS, "")).trim() }
            .orEmpty()
        hits += SearchHit(title = title, url = url, snippet = snippet)
    }
    return hits
}

internal fun decodeHtmlEntities(text: String): String = text
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&apos;", "'")
    .replace("&nbsp;", " ")
    .let { text -> NUMERIC_ENTITY.replace(text) { match -> match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value } }
