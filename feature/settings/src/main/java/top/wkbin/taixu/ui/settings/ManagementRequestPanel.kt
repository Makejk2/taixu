package top.wkbin.taixu.ui.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.wkbin.taixu.core.tools.admin.NativePackageCandidateStore
import top.wkbin.taixu.core.tools.admin.NativeReviewedPluginService
import top.wkbin.taixu.core.tools.admin.NativeReviewedSkillService
import top.wkbin.taixu.harness.admin.nativeui.ManagementAction
import top.wkbin.taixu.harness.admin.nativeui.ManagementPending
import top.wkbin.taixu.harness.admin.nativeui.ManagementState
import top.wkbin.taixu.harness.admin.nativeui.NativeManagementQueue
import top.wkbin.taixu.ui.components.RuntimeButton
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeTextButton
import java.util.UUID

/** Place inside the host's scrolling settings surface, not inside a chat/tool message. */
@Composable
fun ManagementRequestPanel(queue: NativeManagementQueue, modifier: Modifier = Modifier) {
    val items by queue.pending.collectAsState()
    val scope = rememberCoroutineScope()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (items.isNotEmpty()) Text("原生管理待办", style = MaterialTheme.typography.titleMedium)
        items.forEach { item ->
            RuntimeCard(Modifier.fillMaxWidth()) {
                Text(item.title, style = MaterialTheme.typography.titleSmall)
                Text(item.reason)
                Text("来源会话：${item.sourceSessionId}")
                item.risks.forEach { Text("风险：$it") }
                Text("状态：${item.state} · 有效期至 ${item.expiresAt}")
                if (item.state == ManagementState.PENDING) {
                    RuntimeButton(onClick = { scope.launch { queue.confirmFromNative(item.id) } }) {
                        Text(if (item.action == ManagementAction.MODEL_FORM) "打开原生编辑页" else "确认执行")
                    }
                    RuntimeTextButton(onClick = { scope.launch { queue.cancelFromNative(item.id) } }) { Text("取消") }
                } else if (item.state !in setOf(ManagementState.RUNNING, ManagementState.OPENING)) {
                    RuntimeTextButton(onClick = { scope.launch { queue.dismissFinishedFromNative(item.id) } }) { Text("移除记录") }
                }
                if (item.state == ManagementState.UNKNOWN_PARTIAL) Text("结果未知，请核对实际状态；不可自动重试。")
                if (item.state == ManagementState.OPENING) Text("已打开原生表单，尚未确认保存。")
            }
        }
    }
}

/** Owner/epoch authorization MUST come from the runtime's trusted native controller.
 * Invoke only with an ACTION_OPEN_DOCUMENT result; never accept a model URI or path.
 * Candidate bytes are copied privately before inspection, and commits use typed native services. */
class NativePackageDocumentController(
    private val resolver: ContentResolver,
    private val candidates: NativePackageCandidateStore,
    private val plugins: NativeReviewedPluginService,
    private val skills: NativeReviewedSkillService,
    private val queue: NativeManagementQueue,
    private val authorizeOwnerEpoch: suspend (String) -> Boolean,
    private val snapshot: suspend () -> Pair<Boolean,Long>,
) {
    suspend fun selectedDocumentFromNative(owner: String, module: String, uri: Uri) = withContext(Dispatchers.IO) {
        require(authorizeOwnerEpoch(owner))
        val approvedEpoch=snapshot().also { check(it.first) }.second
        val stillAuthorized: suspend () -> Boolean = {
            val current=snapshot()
            current.first && current.second==approvedEpoch && authorizeOwnerEpoch(owner)
        }
        require(uri.scheme == "content")
        val staged = candidates.stage(owner, module, 32L * 1024 * 1024) {
            requireNotNull(resolver.openInputStream(uri))
        }
        try {
            if (module == "skill") {
                val review = skills.inspect(owner, staged.token)
                queue.enqueueNative(ManagementPending(review.id, owner, ManagementAction.SKILL_INSTALL,
                    "安装技能 ${review.packageId} · ${review.version}", "本机所选文档已复制到私有候选存储。",
                    listOf("SHA-256: ${review.archiveDigest}", "权限：${review.permissions}", "替换已有：${review.replacesExisting}"), review.expiresAt),
                    confirm = {
                        val call = UUID.randomUUID().toString()
                        skills.arm(owner, review.id, call)
                        val result = skills.commitNative(owner, review.id, call) { stillAuthorized() }
                        check(result.state == "completed")
                        ManagementState.APPLIED
                    }, cancel = { candidates.discard(owner, staged.token) })
            } else {
                val review = plugins.inspect(owner, staged.token)
                queue.enqueueNative(ManagementPending(review.id, owner, ManagementAction.PLUGIN_INSTALL,
                    "安装插件 ${review.packageId} · ${review.version}", "本机所选文档已复制到私有候选存储。",
                    listOf("SHA-256: ${review.archiveDigest}", "新增权限：${review.addedPermissions}", "插件可执行代码或展示网页"), review.expiresAt),
                    confirm = {
                        val call = UUID.randomUUID().toString()
                        plugins.arm(owner, review.id, call)
                        val result = plugins.commitNative(owner, review.id, call) { stillAuthorized() }
                        check(result.state == "completed")
                        ManagementState.APPLIED
                    }, cancel = { candidates.discard(owner, staged.token) })
            }
        } catch (failure: Throwable) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { candidates.discard(owner, staged.token) }
            throw failure
        }
    }
}
