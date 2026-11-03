package top.wkbin.taixu.ui.settings

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import top.wkbin.taixu.core.datastore.AiAdminPreferences
import top.wkbin.taixu.core.datastore.SettingsDataStore
import top.wkbin.taixu.ui.components.RuntimeIconName

/** Belongs to AgentSettingsCategory.EXECUTION; no model/tool callback can toggle this. */
@Composable
internal fun AiAdminSettingsRow() {
    val store = koinInject<SettingsDataStore>()
    val prefs = remember(store) { AiAdminPreferences(store) }
    val enabled by prefs.enabled.collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()
    AgentToggleRow(icon = RuntimeIconName.Shield, title = "AI 超管（受控管理）",
        subtitle = "开启后允许在对话中提议修改配置、创建持久独立团队和代码审议规则；仍须说明原因并由你授权。不是 Root，AI 不能自行开启或修改审批权限。关闭即拒绝后续管理写入，不自动取消已启动团队；可用停止操作终止工作。",
        checked = enabled, onCheckedChange = { value -> scope.launch { prefs.setEnabledByUser(value) } })
}

@Composable
internal fun AiAdminAndApprovalRows(mode: top.wkbin.taixu.core.model.ApprovalMode,
    onModeChange: (top.wkbin.taixu.core.model.ApprovalMode) -> Unit) {
    AiAdminSettingsRow()
    androidx.compose.material3.HorizontalDivider(color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant)
    ApprovalModeSelectorRow(mode = mode, onModeChange = onModeChange)
}
