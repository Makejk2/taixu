package top.wkbin.taixu.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.core.model.ChunkedText
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.HarnessTool
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.UserMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 会话导出 Markdown：角色分段、工具折叠摘要与头部信息的契约测试（纯 JVM）。 */
class ChatExportMarkdownTest {

    private fun conversation() = listOf(
        UserMessage(id = "u1", createdAt = 1_000L, text = "帮我看看构建日志"),
        ToolCall(id = "t1", createdAt = 2_000L, tool = HarnessTool.BASE, args = JsonObject(mapOf("command" to JsonPrimitive("ls -la")))),
        ToolResult(id = "r1", createdAt = 3_000L, toolCallId = "t1", success = true, output = "build OK\n更多行"),
        AssistantText(id = "a1", createdAt = 4_000L, textContent = ChunkedText.of("构建通过了。")),
    )

    @Test
    fun `segments by role with tool summaries`() {
        val md = ChatExportMarkdown.build(conversation(), 9_000L)
        assertTrue(md.contains("# 太墟会话导出"))
        assertTrue(md.contains("## [") && md.contains("] 用户"))
        assertTrue(md.contains("帮我看看构建日志"))
        assertTrue(md.contains("调用工具 `base`"))
        assertTrue(md.contains("ls -la"))
        assertTrue(md.contains("↳ ✓ build OK"))
        assertTrue(md.contains("## [") && md.contains("] 助手"))
        assertTrue(md.contains("构建通过了。"))
        // 工具输出只保留首行摘要，不整段倾倒
        assertTrue(md.contains("更多行").not())
    }

    @Test
    fun `header carries exported time and message count`() {
        val md = ChatExportMarkdown.build(conversation(), 9_000L)
        assertTrue(md.contains("- 消息数：4"))
        assertTrue(md.contains("- 导出时间："))
    }
}
