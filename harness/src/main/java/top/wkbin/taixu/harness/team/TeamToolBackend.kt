package top.wkbin.taixu.harness.team

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** Host-only adapter. There is deliberately no userConfirmed/approvalMode argument in the schema. */
class TeamToolBackend internal constructor(internal val service: TeamService) {
    suspend fun disableManagement() = service.disableManagement()

    suspend fun audit(sessionId: String, action: String, detail: String) = service.audit(sessionId, action, detail)

    /** Reads and proposals only. Applying a proposal is exclusively an ExecutionGate operation. */
    suspend fun execute(sessionId: String, args: JsonObject): Pair<Boolean, String> = try {
        true to service.execute(sessionId, args)
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { false to "团队操作未完成：${e.message}" }
}

/** Provider contract; the main tool registry can expose this without depending on enum additions. */
object TeamToolSpec {
    const val name = "team"
    val schema: String = """{"type":"object","additionalProperties":false,"properties":{
      "action":{"type":"string","enum":["list","get","status","proposal","propose","apply"]},
      "teamId":{"type":"string"},"groupId":{"type":"string"},"proposalId":{"type":"string"},
      "reason":{"type":"string"},"operation":{"type":"object","description":"op=create|draft|publish|start|stop|communication-policy. communication-policy: groupId, expectedRevision, policy{groupLimit,memberLimit,maxHop,rateLimit,rateWindowMs,queueLimit,unlimitedExplicit}. Cumulative limits are absolute ceilings (defaults 64 group/8 per member), not permanent caps: increase by native approval to resume held messages in the ORIGINAL group and tasks. maxHop is also increasable through native approval. 0 groupLimit/memberLimit means unlimited ONLY with unlimitedExplicit=true and explicit user approval; rateLimit, rateWindowMs and queueLimit must remain >0 for backpressure; maxHop must be >0 (0 is rejected, never unlimited). Over-limit messages persist as held and are not dropped. status shows policy/revision/charged/held. create: name, memberIds and/or members[{key,name,role,systemPrompt,modelId?,modelVariant?}], definition{adminBotId,supervisors,governance{rules}}; definition may reference local member keys. draft: teamId, expectedRevision, name?, memberIds?, definition?. publish: teamId, expectedRevision. start: teamId,name?,task,members?[{botId,instruction,writePaths?}]. If members omitted, every published member receives a role-specific overall task. Default writePaths=[] (read-only); only approve explicit workspace paths. Native independent assignment/session IDs are returned for each admitted member; admission is not completion. Governed executors require native review and cannot bypass it via start approval. Partial failure cancels the new group and never automatically replays. Trusted user source and native root admission must be installed; unsupported roots fail before any admission. stop: groupId. All writes need propose then apply with proposalId; no publication upgrades are implicit."}
    },"required":["action"]}"""
}
