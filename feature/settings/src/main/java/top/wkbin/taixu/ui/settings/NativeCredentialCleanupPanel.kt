package top.wkbin.taixu.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import top.wkbin.taixu.core.tools.AiProfileWriter
import top.wkbin.taixu.harness.session.SessionControl
import top.wkbin.taixu.harness.admin.nativeui.*
import top.wkbin.taixu.ui.components.RuntimeButton

/** Direct native user maintenance, never an agent-callable erasure endpoint. */
@Composable
fun NativeCredentialCleanupPanel() {
    val writer=koinInject<AiProfileWriter>()
    val control=koinInject<SessionControl>()
    val queue=koinInject<NativeManagementQueue>()
    val scope=rememberCoroutineScope()
    var notice by remember { mutableStateOf("") }
    Column {
        Text("已退役模型凭据清理")
        RuntimeButton(onClick={ scope.launch {
            val id=java.util.UUID.randomUUID().toString()
            queue.enqueueNative(ManagementPending(id,control.currentSessionId.value,ManagementAction.CREDENTIAL_CLEANUP,
                "清理已无档案引用的退役凭据","仅删除已被标记退役且当前Room档案不再引用的加密值。",
                listOf("不会删除仍被引用的凭据", "不会向AI显示Key或私有引用", "不会取消已解析配置的在途请求"),
                System.currentTimeMillis()+120_000),confirm={
                    val count=writer.cleanupRetiredCredentialsFromNative()
                    notice="已完成原生清理：$count 项。"
                    ManagementState.APPLIED
                },cancel={})
            notice="已创建原生维护待办，尚未清理。"
        } }) { Text("请求清理退役凭据") }
        Text(notice)
    }
}
