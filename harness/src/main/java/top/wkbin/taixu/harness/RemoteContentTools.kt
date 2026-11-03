package top.wkbin.taixu.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * download / search_web 对模型暴露的工具契约。
 *
 * 单独成文件的原因与 [A2uiSurfaceContract] 相同：ProviderClient.TOOLS 在尺寸棘轮内只许缩减，
 * 定义收敛为常量后列表只留一行引用。
 */
internal object RemoteContentTools {
    val DOWNLOAD: ApiToolDefinition = ApiToolDefinition(
        function = ApiFunctionDefinition(
            name = "download",
            description = "使用内置 HTTPS 下载器把远程文件保存到工作区。支持 HTTP Range 断点续传、自动重试、最大文件大小限制和可选 SHA-256 校验；当前为单连接续传，不是多线程分片。destination 必须位于当前工作区内，不要填写宿主机绝对路径。",
            parameters = Json.parseToJsonElement(
                """{"type":"object","properties":{"url":{"type":"string","description":"HTTPS 下载地址"},"destination":{"type":"string","description":"工作区内目标路径，例如 dist/tool.tar.gz"},"sha256":{"type":"string","description":"可选 SHA-256 十六进制摘要"},"max_attempts":{"type":"integer","description":"可选最大尝试次数，1-10，默认 3"},"max_bytes":{"type":"integer","description":"可选最大文件大小（字节），默认 1 GiB，最大 4 GiB"}},"required":["url","destination"]}""",
            ).jsonObject,
        ),
    )

    val SEARCH_WEB: ApiToolDefinition = ApiToolDefinition(
        function = ApiFunctionDefinition(
            name = "search_web",
            description = "联网搜索：把查询词发送到外部搜索引擎，返回带序号的结果列表（标题/链接/摘要）。用于获取训练知识之外的时效信息、官方文档与事实核对；只读工具，不修改工作区。查询词使用与主题一致的语言；引用结果时用 [n](url) 标注来源；摘要不足以回答时用 download 抓取链接全文后再读。",
            parameters = Json.parseToJsonElement(
                """{"type":"object","properties":{"query":{"type":"string","description":"搜索关键词；越具体越好，可附时间限定"},"count":{"type":"integer","minimum":1,"maximum":20,"description":"返回条数，默认 8"}},"required":["query"]}""",
            ).jsonObject,
        ),
    )
}
