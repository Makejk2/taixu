package top.wkbin.taixu.harness

import top.wkbin.taixu.harness.effects.OutputRetention
import top.wkbin.taixu.harness.effects.TerminalOutputCompressor
import top.wkbin.taixu.harness.effects.ToolOutputRetention
import top.wkbin.taixu.harness.effects.foldOverlongLines
import top.wkbin.taixu.harness.effects.hasIncompleteCommandOutput
import top.wkbin.taixu.harness.effects.keepHeadWholeLines
import top.wkbin.taixu.runtime.environment.ExecutionArtifacts

    /**
     * 读取类输出保留头部；终端输出归一化后再次脱敏并采样首尾。
     * 已脱敏输入沿 `.taixu-outputs/` 落盘，标记完整性并可用 read 分页回读。
     */
internal suspend fun truncateToolOutput(
    output: String,
    toolName: String?,
    sessionId: String,
    workspace: String,
    fileAccess: top.wkbin.taixu.runtime.environment.ExecutionArtifacts?,
    secretRedactor: top.wkbin.taixu.core.security.SecretRedactor,
    environments: top.wkbin.taixu.harness.environment.SessionExecutionEnvironments?,
    linuxEnvironmentManager: top.wkbin.taixu.runtime.LinuxEnvironmentManager?,
    settingsDataStore: top.wkbin.taixu.core.datastore.AgentPreferences?,
    metadata: MutableMap<String, String>? = null,
): String {
        // 超长单行先折叠（混淆/压缩文件）：否则单行预算退化成保留 60k 字符的整行，
        // 一条 tool result 就可能超出单条消息的传输上限导致连接被重置。归档保留脱敏后的未采样文本。
        val retention = ToolOutputRetention.forTool(toolName)
        // Removing controls can join fragments into a secret: redact again BEFORE sampling.
        val archived = if (retention == OutputRetention.TAIL) top.wkbin.taixu.harness.environment.redactExecutionOutput(
            TerminalOutputCompressor.normalize(output), secretRedactor, environments, linuxEnvironmentManager, settingsDataStore, sessionId, workspace) else output
        val folded = if (retention == OutputRetention.TAIL) TerminalOutputCompressor.compressNormalized(archived) else if (toolName == "history_read") archived else foldOverlongLines(archived)
        if (output.length <= ToolExecutor.MAX_OUTPUT_LENGTH && folded == output) return folded
        if (retention == OutputRetention.HEAD && output.length <= ToolExecutor.MAX_OUTPUT_LENGTH) return folded
        val spillPath = fileAccess?.let { ToolOutputSpillStore.spill(it, toolName, archived) }
        if (spillPath != null) metadata?.set(ToolOutputArtifact.METADATA_KEY, kotlinx.serialization.json.Json.encodeToString(
            ToolOutputArtifact.serializer(), ToolOutputArtifact.capture(spillPath, archived).let {
                if (hasIncompleteCommandOutput(output)) it.copy(complete = false) else it }))
        val recoverHint = if (spillPath != null) {
            // 落盘字节量随定位符给出（DSH spill 语义）：模型据此决定 read 分页的步长。
            "${if (hasIncompleteCommandOutput(output)) "可用输出" else "完整输出"}已保存至 $spillPath（约 ${(archived.toByteArray(Charsets.UTF_8).size + 1023) / 1024}KB），可用 read 工具（offset/limit 分页）查看其余部分。"
        } else {
            "需要其余部分请用 grep 过滤关键字、head/tail 取首尾、或 sed -n 'N,Mp' 取指定行段，不要原样重复执行同一命令。"
        }
        if (retention == OutputRetention.TAIL) return "$folded\n\n[$recoverHint]"
        val kept = keepHeadWholeLines(folded, ToolExecutor.TRUNCATE_KEEP_LENGTH)
        val totalLines = output.count { it == '\n' } + 1
        val keptLines = kept.count { it == '\n' } + 1
        return buildString {
            append(kept)
            append("\n\n[输出已截断：完整输出共 ")
            append(totalLines)
            append(" 行 / ")
            append(output.length)
            append(" 字符，以上仅显示前 ")
            append(keptLines)
            append(" 行。")
            append(recoverHint)
            append("]")
        }
 }
