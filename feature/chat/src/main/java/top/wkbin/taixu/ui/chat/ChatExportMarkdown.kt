package top.wkbin.taixu.ui.chat

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.wkbin.taixu.feature.chat.R
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.UserMessage

/** 会话导出 Markdown：消息流折叠为可分享的 MD 文档（用户/助手正文全量，工具往返折叠为摘要行）。 */
object ChatExportMarkdown {

    fun build(messages: List<HarnessMessage>, exportedAt: Long): String {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val stampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return buildString {
            appendLine("# 太墟会话导出")
            appendLine()
            appendLine("- 导出时间：${stampFormat.format(Date(exportedAt))}")
            appendLine("- 消息数：${messages.size}")
            appendLine()
            for (message in messages) {
                when (message) {
                    is UserMessage -> {
                        appendLine("---")
                        appendLine("## [${timeFormat.format(Date(message.createdAt))}] 用户")
                        appendLine()
                        appendLine(message.text.trim())
                        appendLine()
                    }
                    is AssistantText -> {
                        appendLine("---")
                        appendLine("## [${timeFormat.format(Date(message.createdAt))}] 助手")
                        appendLine()
                        appendLine(message.textContent.toString().trim())
                        appendLine()
                    }
                    is ToolCall -> {
                        val summary = message.args.toString().take(SUMMARY_CHARS).trim()
                        appendLine("> 🔧 [${timeFormat.format(Date(message.createdAt))}] 调用工具 `${message.tool.name.lowercase()}` $summary")
                        appendLine()
                    }
                    is ToolResult -> {
                        val firstLine = message.output.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(SUMMARY_CHARS)
                        appendLine("> ↳ ${if (message.success) "✓" else "✗"} $firstLine")
                        appendLine()
                    }
                    else -> Unit
                }
            }
        }.trimEnd() + "\n"
    }

    /** 写入 cacheDir/exports 并返回文件（FileProvider 分享用）。 */
    fun write(context: Context, messages: List<HarnessMessage>, exportedAt: Long): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val name = "taixu-export-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(exportedAt)) + ".md"
        return File(dir, name).apply { writeText(build(messages, exportedAt)) }
    }

    private const val SUMMARY_CHARS = 120
}

/** 顶栏「导出 Markdown」入口：生成文件并通过系统分享面板发出（FileProvider 只读授权）。 */
@Composable
internal fun rememberMarkdownExporter(messages: List<HarnessMessage>): () -> Unit {
    val context = LocalContext.current
    val latestMessages by rememberUpdatedState(messages)
    return remember {
        {
            runCatching {
                val file = ChatExportMarkdown.write(context, latestMessages, System.currentTimeMillis())
                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/markdown"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.chat_export_chooser)))
            }.onFailure {
                Toast.makeText(context, context.getString(R.string.chat_export_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
