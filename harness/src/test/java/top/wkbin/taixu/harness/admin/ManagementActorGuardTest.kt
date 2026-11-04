package top.wkbin.taixu.harness.admin

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import top.wkbin.taixu.core.database.*
import top.wkbin.taixu.harness.*

class ManagementActorGuardTest {
    private fun operation(lane: String, session: String = "owner") = HarnessOperationEntity(
        id="op", sessionId=session, laneName=lane, kind="run", status="running", phase="tool",
        startedAt=1, updatedAt=1, startLeafId=null, stateJson="{}")
    private fun guard(row: HarnessOperationEntity?, current: String? = "op"): ManagementActorGuard {
        val repo = Proxy.newProxyInstance(HarnessRuntimeRepository::class.java.classLoader,
            arrayOf(HarnessRuntimeRepository::class.java)) { _, method, _ -> when(method.name) {
                "findOperation" -> row
                "findLane" -> row?.let { HarnessLaneEntity(it.sessionId, it.laneName, null, currentOperationId=current, updatedAt=1) }
                else -> error("unexpected ${method.name}")
            } } as HarnessRuntimeRepository
        return ManagementActorGuard(repo)
    }
    private val call = ToolCall("call",1,HarnessTool.TEAM,buildJsonObject { put("action","adopt");put("suggestionId","s") })
    @Test fun sharedParentSessionDoesNotGrantSubLaneOwnerAuthority(): Unit = runBlocking {
        assertNotNull(guard(operation("subagent:one")).rejection(call,"owner","op",true))
        assertNotNull(guard(operation("main")).rejection(call,"owner","op",false))
        assertNotNull(guard(operation("main","other")).rejection(call,"owner","op",true))
        assertNotNull(guard(null).rejection(call,"owner",null,true))
        assertNotNull(guard(operation("main"),"replacement").rejection(call,"owner","op",true))
        assertNull(guard(operation("main")).rejection(call,"owner","op",true))
    }
    @Test fun memberOwnMainLaneMaySuggestButOwnershipRemainsRepositoryDecision(): Unit = runBlocking {
        val suggest = call.copy(args=buildJsonObject { put("action","suggest");put("groupId","g") })
        assertNull(guard(operation("main","member-session")).rejection(suggest,"member-session","op",true))
        assertNotNull(guard(operation("main","workflow:one")).rejection(call,"workflow:one","op",true))
    }
}
