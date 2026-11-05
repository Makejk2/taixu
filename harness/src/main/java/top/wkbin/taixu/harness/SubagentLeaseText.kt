package top.wkbin.taixu.harness

import top.wkbin.taixu.core.model.SubagentTaskSpec

/** 纯文案构造：从 SubagentOrchestrator 抽离以符合架构棘轮；行为不变。 */
internal fun routingFailureText(spec: SubagentTaskSpec): String = if (spec.role.isNotBlank()) {
    "精确角色 ${spec.role} 未配置或未启用。可改用 department + agentQuery 让本地索引派发。"
} else {
    "部门 ${spec.department} 中没有匹配 agentQuery=\"${spec.agentQuery}\" 的已启用角色。请保留部门并改用 2–5 个更具体的英文专业关键词。"
}

internal fun writeLeaseLine(spec: SubagentTaskSpec, readOnlyWriteIntent: Boolean): String = when {
        spec.writePaths.isEmpty() -> buildString {
            append("本任务为只读任务：禁止调用 write/edit/download，禁止执行会修改工作区的命令；只返回分析或数据。")
            append("write/edit/download 以及疑似 shell 写（重定向、sed -i、mv/rm/cp/tee 等）在本 Lane 内会被强制拦截。")
            if (readOnlyWriteIntent) {
                // 任务文字要求落盘却没有写租约：必须显式指出冲突，否则子智能体会在
                // "要写"与"不许写"之间自行猜测，并可能把"没法写"当成完成。
                append("\n注意：本任务文字提到了落盘/写入，但主智能体未声明 write_paths。")
                append("请把需要落盘的完整内容直接放进结论正文（含目标路径与完整文件内容），")
                append("由主智能体写入；不要声称文件已生成。")
            }
        }
        spec.writePaths.any(::isWholeWorkspaceWritePath) ->
            "本任务持有整工作区独占写租约；仅修改任务确实需要的文件，避免无关改动。"
        else -> buildString {
            append("限定写入范围（write/edit/download 会强制校验，越界写入直接拦截）：")
            append(spec.writePaths.joinToString("、"))
            append("。base/process 的 shell 写无法静态核验路径，必须遵守同一范围；越界疑似写入会在汇总中提示复核。")
        }
    }


internal fun waveLeaseDescription(wave: List<SubagentTaskSpec>): String = when {
    wave.all { it.writePaths.isEmpty() } -> "read-only"
    wave.any { it.writePaths.any(::isWholeWorkspaceWritePath) } -> "whole-workspace exclusive"
    else -> "scoped disjoint write paths"
}
