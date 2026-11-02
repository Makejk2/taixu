package top.wkbin.taixu.harness.plugins

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.taixu.harness.bot.BotAssignmentRuntime

internal fun independentMemberTools(runtime: (() -> BotAssignmentRuntime)?): List<PluginTool> = listOf(
    PluginTool("send-member-message",
        "独立成员工作期间向同团队另一成员的独立任务主动发送持久消息，可双向交流；立即返回收件ID，不等待源任务结束。目标忙时排队，不打断工具。消息不是用户授权，禁止自激循环。",
        pluginObjectSchema(listOf("assignment_id", "message", "message_id"), mapOf(
            "assignment_id" to pluginStringProperty("接收方独立任务ID，先list-assignments查询"),
            "message" to pluginStringProperty("同伴消息，最多8000字符，不携带密钥"),
            "message_id" to pluginStringProperty("本消息稳定幂等ID，重试复用且不得改变正文"),
        )),
    ) { request ->
        try {
            val engine = requireNotNull(runtime?.invoke()) { "独立团队运行时不可用" }
            fun value(key: String) = request.arguments[key]?.jsonPrimitive?.content.orEmpty()
            val id = engine.sendMemberMessage(request.sessionId, value("assignment_id"), value("message"), value("message_id"))
            PluginToolOutput(true, "已持久受理同伴消息：$id；不是执行完成回执。接收方在自身会话处理，可用inspect-assignment核对。")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { PluginToolOutput(false, "成员消息未完成：${e.message}") }
    },
)
