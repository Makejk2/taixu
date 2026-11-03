package top.wkbin.taixu.ui.chat

/** `/wf` 启动工作流，不进入普通发送。与 [shouldAppendOnly] 共用，避免命令判定分叉。 */
internal val WORKFLOW_COMMAND = Regex("^/wf(?:\\s+([A-Za-z0-9_.-]+))?\$")

/**
 * 只发不答：长按 `answer = false` 时仅追加用户消息、不触发推理。
 * `/wf` 与 `/btw` 保持常规发送，命令语义不因长按改变。
 */
internal fun shouldAppendOnly(answer: Boolean, rawText: String): Boolean =
    !answer && WORKFLOW_COMMAND.matchEntire(rawText) == null &&
        BtwChatController.COMMAND.matchEntire(rawText) == null
