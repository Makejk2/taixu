package top.wkbin.taixu.harness.team

import java.util.UUID
import kotlinx.serialization.json.*
import top.wkbin.taixu.core.database.TeamSuggestionRepository
import top.wkbin.taixu.core.model.bot.TeamSuggestion

/** Requests carry no authority. Only the persisted native group owner may turn one into a proposal. */
internal class TeamSuggestions(
    private val repository: TeamSuggestionRepository?,
    private val port: TeamPort,
    private val clock: () -> Long,
    private val enabled: suspend () -> Boolean,
    private val validate: suspend (JsonObject, String) -> Unit,
    private val snapshot: suspend (JsonObject, String) -> String,
    private val audit: suspend (String, String, String) -> Unit,
) {
    private fun text(args: JsonObject, key: String) = (args[key] as? JsonPrimitive)?.content.orEmpty()
    private fun required(args: JsonObject, key: String) = text(args, key).also { require(it.isNotBlank()) { "缺少 $key" } }
    private suspend fun scoped(group: String, op: JsonObject) {
        val current = requireNotNull(port.group(group)) { "群聊不存在" }
        when (required(op, "op")) {
            "communication-policy", "stop" -> require(required(op, "groupId") == group) { "建议仅限本组" }
            "draft", "publish" -> require(required(op, "teamId") == current.teamGroupId) { "建议仅限本组团队" }
            else -> error("成员建议仅支持 draft/publish/stop/communication-policy；不会创建或启动其他群")
        }
    }
    suspend fun execute(session: String, args: JsonObject, propose: suspend (TeamSuggestion) -> Pair<String, String>): String {
        val repo = requireNotNull(repository) { "持久建议仓库尚未装配" }
        val action = required(args, "action")
        val allowed = when (action) {
            "suggest" -> setOf("action", "groupId", "idempotencyKey", "reason", "operation")
            "suggestions" -> setOf("action", "groupId", "suggestionId")
            else -> setOf("action", "groupId", "suggestionId")
        }
        require(args.keys.all { it in allowed }) { "不支持的建议参数；身份与批准不可自报" }
        val group = required(args, "groupId")
        if (action == "suggestions") {
            val id = text(args, "suggestionId")
            return if (id.isBlank()) Json.encodeToString(repo.list(session, group))
            else Json.encodeToString(requireNotNull(repo.get(session, group, id)) { "建议不可用" })
        }
        require(enabled()) { "AI 超级管理已关闭，建议管理写入被拒绝" }
        if (action == "suggest") {
            repo.requireMember(session, group)
            val key = required(args, "idempotencyKey").also { require(it.length <= 128) }
            val reason = required(args, "reason").also { require(it.length <= 4000) }
            val op = requireNotNull(args["operation"]).jsonObject.also { require(it.toString().length <= 100_000) }
            scoped(group, op)
            validate(op, session)
            val row = repo.suggest(session, group, UUID.randomUUID().toString(), key, reason,
                op.toString(), snapshot(op, session), clock())
            audit(session, "suggested", "${row.id}:$group:${row.sourceAssignmentId}")
            return Json.encodeToString(row)
        }
        repo.requireOwner(session, group)
        val row = requireNotNull(repo.get(session, group, required(args, "suggestionId"))) { "建议不可用" }
        require(row.status == "pending") { "建议已处理（${row.status}），不得重复采纳或重放" }
        if (action == "reject") {
            check(repo.decide(session, group, row.id, "rejected", null, clock())) { "建议已被处理" }
            audit(session, "suggestion_rejected", row.id)
            return "建议已拒绝：${row.id}"
        }
        require(action == "adopt") { "未知建议操作" }
        val op = Json.parseToJsonElement(row.operationJson).jsonObject
        scoped(group, op)
        validate(op, session)
        require(snapshot(op, session) == row.beforeSnapshot) { "建议后的团队/策略快照已变化；须由成员重新建议" }
        // Callback creates a fresh owner-bound immutable proposal. It is never an apply bypass.
        val (proposalId, description) = propose(row)
        if (!repo.decide(session, group, row.id, "adopted", proposalId, clock())) error("建议已被处理")
        audit(session, "suggestion_adopted", "${row.id}:$proposalId")
        return description
    }
}
