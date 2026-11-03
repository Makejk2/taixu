package top.wkbin.taixu.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** 粘贴文本超过该长度时自动转为 txt 附件（避免大段日志/代码刷屏）。 */
internal const val PASTE_ATTACHMENT_THRESHOLD = 2_000

/**
 * 输入框的粘贴拦截器（超长文本转附件）：
 * 单次粘贴文本条目超过 [PASTE_ATTACHMENT_THRESHOLD] 字符时转为 txt 附件并消费掉该条目；
 * 未超限的条目原样放行（默认插入输入框）。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun rememberPasteLongTextReceiver(onPasteLongText: (String) -> Unit): ReceiveContentListener {
    val latestHandler by rememberUpdatedState(onPasteLongText)
    return remember {
        ReceiveContentListener { transferable ->
            if (!transferable.hasMediaType(MediaType.Text)) return@ReceiveContentListener transferable
            transferable.consume { item ->
                val text = item.text?.toString() ?: return@consume false
                if (text.length < PASTE_ATTACHMENT_THRESHOLD) return@consume false
                latestHandler(text)
                true
            }
        }
    }
}
