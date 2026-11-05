package top.wkbin.taixu.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.koin.compose.koinInject
import top.wkbin.taixu.harness.admin.model.NativeModelEditorRequests

/**
 * 原生模型表单请求的导航效果：监听可信协调器的当前请求，
 * 把 (modelId, requestId) 交给顶层导航栈打开原生编辑器。
 * 放在 settings 模块以保持 feature.navigation 不直依赖 harness（架构白名单）。
 */
@Composable
fun NativeModelEditorRouteEffects(onOpen: (String?, String) -> Unit) {
    val editor = koinInject<NativeModelEditorRequests>()
    val request by editor.current.collectAsState()
    LaunchedEffect(request?.requestId) {
        request?.let { onOpen(it.modelId, it.requestId) }
    }
}
