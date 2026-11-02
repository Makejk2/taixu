package top.wkbin.taixu.harness.config

/** Explicit allowlist; adding a preference requires a real DataStore adapter and review. */
internal enum class AppConfigRisk { LOW, HIGH }
internal data class AppConfigSpec(
    val key: String,
    val title: String,
    val impact: String,
    val risk: AppConfigRisk = AppConfigRisk.LOW,
    val values: List<String> = emptyList(),
    val selfPrivilege: Boolean = false,
) {
    fun validate(value: String): String? = when {
        selfPrivilege -> "权限项只能由用户在原生设置中手动修改。"
        values.isNotEmpty() && value !in values -> "允许值：${values.joinToString()}"
        key == "appearance.font_scale" && (value.toFloatOrNull()?.let { it.isFinite() && it in 0.8f..1.3f } != true) -> "字体比例必须是 0.8–1.3 的有限数值。"
        key == "terminal.font_size" && (value.toIntOrNull() !in 10..24) -> "终端字号必须是 10–24 的整数。"
        else -> null
    }
}
internal object AppConfigCatalog {
    val All = listOf(
        AppConfigSpec("appearance.theme", "主题模式", "影响整个应用的明暗主题。", values = listOf("system", "light", "dark")),
        AppConfigSpec("appearance.dynamic_color", "动态取色", "影响整个应用配色。", values = listOf("true", "false")),
        AppConfigSpec("appearance.font_scale", "应用字体比例", "影响全部原生页面文字大小；过大可能影响布局。", AppConfigRisk.HIGH),
        AppConfigSpec("terminal.font_size", "终端字号", "影响原生终端显示密度，不影响终端命令。"),
        AppConfigSpec("terminal.haptics", "终端触觉反馈", "影响终端输入时的振动。", values = listOf("true", "false")),
        AppConfigSpec("approval.mode", "审批模式", "改变工具执行权限；请用户手动前往设置。", selfPrivilege = true),
        AppConfigSpec("run.mode", "运行模式", "改变 PLAN/BUILD 执行边界；请用户手动切换。", selfPrivilege = true),
    )
    fun find(key: String?): AppConfigSpec? = All.firstOrNull { it.key == key }
    fun isPrivilegeKey(key: String?): Boolean = key?.lowercase()?.let { k ->
        listOf("approval", "run", "permission", "security", "skill", "grant").any { k == it || k.startsWith("$it.") || k.startsWith("${it}_") }
    } ?: false
    fun writable() = All.filterNot { it.selfPrivilege }
}
