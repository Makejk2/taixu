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
      "reason":{"type":"string"},"operation":{"type":"object","description":"op=create|draft|publish|start|stop. create: name, memberIds and/or members[{key,name,role,systemPrompt,modelId?,modelVariant?}], definition{adminBotId,supervisors,governance{rules}}; definition may reference local member keys. draft: teamId, expectedRevision, name?, memberIds?, definition?. publish: teamId, expectedRevision. start: teamId,name?,task. stop: groupId. All writes need propose then apply with proposalId; no publication upgrades are implicit."}
    },"required":["action"]}"""
}
