package top.wkbin.taixu.harness.admin.model

import kotlinx.coroutines.flow.first
import top.wkbin.taixu.core.database.HarnessRuntimeRepository
import top.wkbin.taixu.core.database.HarnessSessionRepository
import top.wkbin.taixu.core.database.repository.BotAssignmentRepository
import top.wkbin.taixu.core.database.repository.PersonaBotRepository

/** Production authority reads native journal + controlled user session ownership, never model JSON.
 * Integrator also runs ManagementActorGuard(nativeLane=true) before dispatch, rejecting background
 * callers borrowing parent identity. Actor values are taken from executor context only.
 */
class NativeModelAdminAuthority(
    private val runtime: HarnessRuntimeRepository,
    private val sessions: HarnessSessionRepository,
    private val bots: PersonaBotRepository,
    private val assignments: BotAssignmentRepository,
) : ModelAdminAuthority {
    override suspend fun isControlledUserSession(actor: ModelAdminActor): Boolean {
        if (actor.sessionId.startsWith("workflow:") || actor.sessionId.startsWith("subagent:")) return false
        val session = sessions.findById(actor.sessionId) ?: return false
        if (session.runMode !in setOf("build", "plan")) return false
        val operation = runtime.findOperation(actor.operationId) ?: return false
        if (operation.sessionId != session.id || operation.laneName != "main") return false
        if (runtime.findLane(session.id, "main")?.currentOperationId != operation.id) return false
        return assignments.assignmentForSession(session.id) == null && bots.getBotBySessionId(session.id) == null &&
            session.id !in bots.observeGroupSessionRefs().first()
    }
    override suspend fun isPlan(actor: ModelAdminActor): Boolean =
        sessions.findById(actor.sessionId)?.runMode != "build"
}
