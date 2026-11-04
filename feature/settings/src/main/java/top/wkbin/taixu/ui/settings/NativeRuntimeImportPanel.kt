package top.wkbin.taixu.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.Card
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import top.wkbin.taixu.harness.admin.runtime.NativeRuntimeImportController
import top.wkbin.taixu.harness.session.SessionControl
import top.wkbin.taixu.runtime.DistributionCatalog
import top.wkbin.taixu.ui.components.RuntimeButton
import top.wkbin.taixu.ui.components.RuntimeTextButton

/** Release-native entry point; selection only creates a review, never reports import success. */
@Composable
fun NativeRuntimeImportPanel(
    controller: NativeRuntimeImportController = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val control = koinInject<SessionControl>()
    val reviews by controller.reviews.collectAsState()
    var selection by remember { mutableStateOf<NativeRuntimeImportController.Selection?>(null) }
    var busy by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf(DistributionCatalog.supported.first().id) }
    fun failure() { Toast.makeText(context, "导入请求未完成；请核对会话授权、目标快照或私有存储。不会自动重试。", Toast.LENGTH_LONG).show() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val frozen = selection
        selection = null
        if (uri != null && frozen != null) {
            scope.launch {
                busy = true
                try { controller.selectedDocumentFromNative(frozen, uri) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failure() }
                finally { busy = false }
            }
        } else busy = false // Cancelled picker / lost provenance after recreation: no import.
    }
    Column {
        Text("原生发行版导入：本机选择 → 私有归档摘要 → 原生确认 → 异步操作状态")
        Text("目标：${DistributionCatalog.supported.single { it.id == target }.displayWithVersion}")
        RuntimeTextButton(onClick = {
            if (!busy && selection == null) {
                val options = DistributionCatalog.supported
                target = options[(options.indexOfFirst { it.id == target } + 1) % options.size].id
            }
        }) { Text("切换导入目标") }
        RuntimeButton(onClick = {
            if (!busy && selection == null) {
                busy = true
                scope.launch {
                    try {
                        selection = controller.beginSelectionFromNative(control.currentSessionId.value, target)
                        picker.launch(arrayOf("*/*")) // Providers often omit MIME for tar/xz/zstd rootfs.
                    } catch (cancelled: CancellationException) {
                        selection = null; busy = false; throw cancelled
                    } catch (_: Exception) { selection = null; busy = false; failure() }
                }
            }
        }) { Text(if (busy) "选择或捕获中（尚未导入）" else "选择 RootFS 归档") }
        reviews.forEach { review ->
            key(review.id) {
                Card {
                    Column {
                        Text("来源会话：${review.sourceSessionId} · 授权 epoch：${review.epoch}")
                        Text("源发行版：${review.snapshot.sourceId} → 目标：${review.snapshot.targetId}")
                        Text("工作区：${review.snapshot.workspaceId} · 代际：${review.snapshot.generation}/${review.snapshot.workspaceGeneration}")
                        Text("目标已安装：${review.snapshot.targetId in review.snapshot.installedIds}")
                        Text("现有摘要：${review.snapshot.rootfsDigests[review.snapshot.targetId].orEmpty()}")
                        Text("私有归档：${review.sizeBytes} bytes\nSHA-256：${review.sha256}")
                        Text("RootFS 含可执行代码；可能替换目标数据并改变运行环境。仅导入可信 ARM64 归档。")
                        Text("状态：${review.state}")
                        review.operationId?.let { Text("operationId：$it（只有 finished 表示成功）") }
                        if (review.cleanupPending) Text("归档清理待核对；不可把此状态当作成功或自动重试。")
                        if (review.state == "pending_native_confirmation") {
                            RuntimeButton(onClick = { scope.launch { controller.confirmFromNative(review.id) } }) { Text("确认此摘要与目标并导入") }
                            RuntimeTextButton(onClick = { scope.launch { controller.cancelFromNative(review.id) } }) { Text("取消并释放归档") }
                        } else if (review.operationId == null && review.cleanupPending) {
                            RuntimeTextButton(onClick = { scope.launch { controller.cancelFromNative(review.id) } }) { Text("重试释放归档（不导入）") }
                        } else if (review.state !in setOf("queued", "running") && !review.cleanupPending) {
                            RuntimeTextButton(onClick = { scope.launch { controller.dismissFromNative(review.id) } }) { Text("移除记录") }
                        }
                    }
                }
            }
        }
    }
}
