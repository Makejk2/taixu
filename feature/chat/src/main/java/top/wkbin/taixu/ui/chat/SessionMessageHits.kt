package top.wkbin.taixu.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.wkbin.taixu.core.database.MessageSearchHitRow
import top.wkbin.taixu.feature.chat.R
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconName

/**
 * 会话侧栏「消息命中」：跨会话消息正文全文搜索结果（
 * 实现走 SQLite json_extract + LIKE，中文按子串匹配无需分词）。
 * 250ms 防抖在组件内完成；点击命中项切换到对应会话。
 */
@Composable
internal fun SessionMessageHits(
    query: String,
    search: suspend (String) -> List<MessageSearchHitRow>,
    onSwitch: (String) -> Unit,
) {
    var hits by remember { mutableStateOf<List<MessageSearchHitRow>>(emptyList()) }
    LaunchedEffect(query) {
        val term = query.trim()
        if (term.isEmpty()) {
            hits = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        hits = runCatching { search(term) }.getOrDefault(emptyList())
    }
    if (hits.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(R.string.chat_drawer_message_hits),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        hits.forEach { hit ->
            Row(
                Modifier.fillMaxWidth().clickable { onSwitch(hit.sessionId) }.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RuntimeIcon(if (hit.isUser) RuntimeIconName.Chat else RuntimeIconName.Bot, Modifier.width(24.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    val title = hit.sessionTitle?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_drawer_untitled)
                    Text(
                        title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        hit.excerpt.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
