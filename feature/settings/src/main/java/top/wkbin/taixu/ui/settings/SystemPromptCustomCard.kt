package top.wkbin.taixu.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.wkbin.taixu.ui.components.RuntimeAlertDialog
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeFilledTextField
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeSwitch as Switch
import top.wkbin.taixu.ui.components.RuntimeTextButton as TextButton
import top.wkbin.taixu.ui.settings.LocalizedText as Text

/**
 * 系统提示词与人设卡片。自 [AgentSettingsScreen] 抽出，避免顶格文件越过尺寸棘轮。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SystemPromptCustomCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    prompt: String,
    onPromptChange: (String) -> Unit,
    charName: String,
    onCharNameChange: (String) -> Unit,
    userName: String,
    onUserNameChange: (String) -> Unit,
) {
    // 仅在首次进入时以持久化值初始化缓冲，避免 remember(prompt) 键在每次 Datastore 回写时重置输入；
    // 输入经 400ms 防抖后再写入 Datastore，避免每次按键都落盘
    var textBuffer by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(prompt) }
    androidx.compose.runtime.LaunchedEffect(textBuffer) {
        if (textBuffer != prompt) {
            delay(400)
            onPromptChange(textBuffer)
        }
    }

    // 称呼输入同样走 400ms 防抖落盘；留空即回退默认值
    var charNameBuffer by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(charName) }
    androidx.compose.runtime.LaunchedEffect(charNameBuffer) {
        if (charNameBuffer != charName) {
            delay(400)
            onCharNameChange(charNameBuffer)
        }
    }
    var userNameBuffer by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(userName) }
    androidx.compose.runtime.LaunchedEffect(userNameBuffer) {
        if (userNameBuffer != userName) {
            delay(400)
            onUserNameChange(userNameBuffer)
        }
    }

    var showDefaultPromptDialog by remember { mutableStateOf(false) }
    var defaultPromptAsset by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val androidContext = androidx.compose.ui.platform.LocalContext.current
    if (showDefaultPromptDialog) {
        androidx.compose.runtime.LaunchedEffect(showDefaultPromptDialog) {
            if (defaultPromptAsset.isBlank()) {
                defaultPromptAsset = runCatching {
                    androidContext.assets.open("prompts/system/core.md").bufferedReader().use { it.readText() }
                }.getOrDefault("（默认系统提示词加载失败）")
            }
        }
        RuntimeAlertDialog(
            onDismissRequest = { showDefaultPromptDialog = false },
            title = { Text("默认系统提示词 (core.md)", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        defaultPromptAsset,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDefaultPromptDialog = false }) { Text("知道了") }
            },
        )
    }

    val defaultPromptTemplate = """
You are a helpful and expert AI assistant called {{char}}, based on model {{model_name}}.

## Persona
- You refer to yourself as {{char}} in conversations.
- You address the user as {{user}}.

## System & Device Context
- Time: {{cur_datetime}}
- Locale: {{locale}}
- Timezone: {{timezone}}
- Device: {{device_info}}
- System: {{system_version}}
- Battery: {{battery_level}}

## Instructions
- Always think carefully before answering.
- Follow user instructions precisely and write clean code.
    """.trimIndent()

    val availableVariables = listOf(
        "{{cur_date}}" to "日期",
        "{{cur_time}}" to "时间",
        "{{cur_datetime}}" to "日期时间",
        "{{model_name}}" to "模型名称",
        "{{model_id}}" to "模型ID",
        "{{locale}}" to "语言环境",
        "{{timezone}}" to "时区",
        "{{device_info}}" to "设备信息",
        "{{system_version}}" to "系统版本",
        "{{battery_level}}" to "电池电量",
        "{{char}}" to "助手名称",
        "{{user}}" to "用户名称",
    )

    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    RuntimeIcon(RuntimeIconName.Prompt, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text("自定义系统提示词 (System Prompt)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("覆盖全局默认 System Prompt，支持动态宏变量注入", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                )
            }

            // 称呼配置独立于自定义提示词开关：默认提示词同样生效
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("称呼与人设", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                TextButton(
                    onClick = { showDefaultPromptDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        RuntimeIcon(RuntimeIconName.Visibility, Modifier.size(12.dp))
                        Text("查看默认系统提示词", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RuntimeFilledTextField(
                    value = charNameBuffer,
                    onValueChange = { charNameBuffer = it },
                    label = { Text("模型自称") },
                    placeholder = { Text("太墟智枢") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                RuntimeFilledTextField(
                    value = userNameBuffer,
                    onValueChange = { userNameBuffer = it },
                    label = { Text("对用户的称呼") },
                    placeholder = { Text("用户") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            }

            Text(
                "修改后立即对所有对话生效（留空恢复默认）；自定义提示词中的 {{char}} / {{user}} 宏变量也将使用这两个值。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (enabled) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("提示词模板内容：", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    TextButton(
                        onClick = {
                            textBuffer = defaultPromptTemplate
                            onPromptChange(defaultPromptTemplate)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(12.dp))
                            Text("重置模板", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                RuntimeFilledTextField(
                    value = textBuffer,
                    onValueChange = { textBuffer = it },
                    placeholder = { Text("在此输入自定义系统提示词，支持 {{cur_datetime}} 等宏变量...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 10,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )

                Text("点击快捷插入动态宏变量：", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    availableVariables.forEach { (variable, label) ->
                        SuggestionChip(
                            onClick = {
                                val updated = if (textBuffer.isBlank()) variable else "$textBuffer $variable"
                                textBuffer = updated
                                onPromptChange(updated)
                            },
                            label = {
                                Text("$label: $variable", style = MaterialTheme.typography.labelSmall)
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            ),
                        )
                    }
                }
            }
        }
    }
}
