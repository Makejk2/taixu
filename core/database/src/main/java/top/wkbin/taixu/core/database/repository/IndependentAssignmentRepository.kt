package top.wkbin.taixu.core.database.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import top.wkbin.taixu.core.database.BotAssignmentDao
import top.wkbin.taixu.core.database.BotAssignmentRequestEntity
import top.wkbin.taixu.core.database.HarnessSessionEntity
import top.wkbin.taixu.core.database.toEntity
import top.wkbin.taixu.core.database.toModel
import top.wkbin.taixu.core.model.bot.BotAssignment
import top.wkbin.taixu.core.model.bot.BotAssignmentRequest
import top.wkbin.taixu.core.model.bot.BotWorkBatch

interface IndependentAssignmentRepository {
    suspend fun createIndependent(
        task: BotAssignment,
        session: HarnessSessionEntity,
        request: BotAssignmentRequest,
        batch: BotWorkBatch? = null,
    )
    /** Native identity is derived from sourceSession; callers cannot supply source member/group IDs. */
    suspend fun sendCommunication(sourceSession: String, targetId: String, body: String, messageId: String): top.wkbin.taixu.core.model.bot.TeamCommunicationReceipt =
        error("持久通信仓库尚未装配；未发送消息")
    suspend fun communicationSnapshot(groupId: String): top.wkbin.taixu.core.model.bot.TeamCommunicationSnapshot =
        error("持久通信仓库尚未装配")
    suspend fun approveCommunicationPolicy(groupId: String, expectedRevision: Int,
        policy: top.wkbin.taixu.core.model.bot.TeamCommunicationPolicy, sourceSession: String, approvalId: String) {
        error("持久通信仓库尚未装配")
    }
    suspend fun resumeCommunications(groupId: String? = null) {}
    /** Read-only keyset page for the currently running session's own assignment. Never claims queued work. */
    suspend fun readCommunicationInbox(sourceSession: String, after: Long = 0, limit: Int = 50): List<top.wkbin.taixu.core.model.bot.TeamCommunicationMessage> =
        error("持久通信仓库尚未装配")
    suspend fun enqueueRequest(request: BotAssignmentRequest)
    /** Atomic get-or-create; never creates a session/request for a duplicate work item.
     * trustedNativeTeamStart is exclusively for the internally approved native TeamFactory path,
     * NEVER for model-supplied arguments. sourceSessionId is the verified main user session.
     */
    suspend fun createIndependentOnce(task: BotAssignment, session: HarnessSessionEntity,
        request: BotAssignmentRequest, batch: BotWorkBatch? = null, operationId: String? = null, nativeTaskId: String? = null,
        trustedNativeTeamStart: Boolean = false, sourceSessionId: String? = null): String {
        require(!trustedNativeTeamStart) { "此存储实现不支持可信原生团队启动" }
        createIndependent(task, session, request, batch)
        return task.id
    }
    suspend fun dispatchedBots(groupId: String, operationId: String, nativeTaskId: String? = null): Set<String> = emptySet()
    fun pendingRequests(): Flow<List<BotAssignmentRequest>>
    fun observeRequest(id: String): Flow<BotAssignmentRequest?>
    suspend fun getRequest(id: String): BotAssignmentRequest?
    suspend fun requestsFor(id: String): List<BotAssignmentRequest>
    suspend fun assignmentForSession(sessionId: String): BotAssignment?
    suspend fun assignmentsForBot(botId: String): List<BotAssignment>
    suspend fun claimRequest(id: String, now: Long): Boolean
    suspend fun admitRequest(id: String, nativeTaskId: String?): Boolean
    suspend fun finishRequest(id: String, success: Boolean, uncertain: Boolean, summary: String?, error: String?)
    suspend fun reportBlock(sessionId: String, reason: String?, artifacts: List<String>): Boolean
    suspend fun recoverRequests()
    suspend fun cancelAssignment(id: String)
    suspend fun acknowledgeSynchronousResult(id: String, revision: Int, status: String, sourceSessionId: String) {}
}

internal class RoomIndependentAssignmentRepository(private val dao: BotAssignmentDao, private val json: Json) : IndependentAssignmentRepository {
    override suspend fun sendCommunication(sourceSession: String, targetId: String, body: String, messageId: String) =
        dao.sendCommunication(sourceSession, targetId, body, messageId, System.currentTimeMillis())
    override suspend fun communicationSnapshot(groupId: String) = dao.communicationSnapshot(groupId)
    override suspend fun approveCommunicationPolicy(groupId: String, expectedRevision: Int,
        policy: top.wkbin.taixu.core.model.bot.TeamCommunicationPolicy, sourceSession: String, approvalId: String) {
        dao.approveCommunicationPolicy(groupId, expectedRevision, policy, sourceSession, approvalId, System.currentTimeMillis())
        resumeCommunications(groupId)
    }
    override suspend fun resumeCommunications(groupId: String?) {
        (groupId?.let { listOf(it) } ?: dao.heldCommunicationGroups()).forEach { group ->
            var cursor = 0L
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                cursor = dao.resumeCommunicationPage(group, cursor, System.currentTimeMillis()) ?: break
            }
        }
    }
    override suspend fun readCommunicationInbox(sourceSession: String, after: Long, limit: Int) =
        dao.readCommunicationInbox(sourceSession, after, limit)

    override suspend fun createIndependent(
        task: BotAssignment,
        session: HarnessSessionEntity,
        request: BotAssignmentRequest,
        batch: BotWorkBatch?,
    ) { dao.createIndependent(task.toEntity(json), session, request.toEntity(), batch?.toEntity()) }
    override suspend fun createIndependentOnce(task: BotAssignment, session: HarnessSessionEntity,
        request: BotAssignmentRequest, batch: BotWorkBatch?, operationId: String?, nativeTaskId: String?, trustedNativeTeamStart: Boolean, sourceSessionId: String?): String =
        dao.createIndependent(task.toEntity(json), session, request.toEntity(), batch?.toEntity(), operationId, nativeTaskId, trustedNativeTeamStart, sourceSessionId)
    override suspend fun dispatchedBots(groupId: String, operationId: String, nativeTaskId: String?) = dao.dispatchedBots(groupId, operationId, nativeTaskId).toSet()
    override suspend fun enqueueRequest(request: BotAssignmentRequest) = dao.enqueueRequest(request.toEntity())
    override fun pendingRequests(): Flow<List<BotAssignmentRequest>> = channelFlow {
        launch {
            while (true) {
                try { resumeCommunications() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Durable holds survive transient failures; never discard them. */ }
                delay(1_000)
            }
        }
        dao.pendingAssignmentRequests().collect { rows -> send(rows.map { it.model() }) }
    }
    override fun observeRequest(id: String) = dao.observeAssignmentRequest(id).map { it?.model() }
    override suspend fun getRequest(id: String) = dao.findAssignmentRequest(id)?.model()
    override suspend fun requestsFor(id: String) = dao.assignmentRequests(id).map { it.model() }
    override suspend fun assignmentForSession(sessionId: String) = dao.assignmentForSession(sessionId)?.toModel(json)
    override suspend fun assignmentsForBot(botId: String) = dao.assignmentsForBot(botId).map { it.toModel(json) }
    override suspend fun claimRequest(id: String, now: Long) = dao.claimRequest(id, now)
    override suspend fun admitRequest(id: String, nativeTaskId: String?) = dao.admitAssignmentRequest(id, nativeTaskId) == 1
    override suspend fun finishRequest(id: String, success: Boolean, uncertain: Boolean, summary: String?, error: String?) =
        dao.finishRequest(id, success, uncertain, summary, error, System.currentTimeMillis())
    override suspend fun reportBlock(sessionId: String, reason: String?, artifacts: List<String>): Boolean {
        val task = dao.assignmentForSession(sessionId) ?: return false
        return dao.declareAssignmentBlock(task.activeRequestId ?: return false, reason, json.encodeToString(artifacts)) == 1
    }
    override suspend fun recoverRequests() = dao.recoverRequests(System.currentTimeMillis())
    override suspend fun cancelAssignment(id: String) = dao.cancelAssignment(id, System.currentTimeMillis())
    override suspend fun acknowledgeSynchronousResult(id: String, revision: Int, status: String, sourceSessionId: String) =
        dao.acknowledgeSynchronousResult(id, revision, status, sourceSessionId)
    private fun BotAssignmentRequest.toEntity() = BotAssignmentRequestEntity(id, assignmentId, instruction, createdAt)
    private fun BotAssignmentRequestEntity.model() = BotAssignmentRequest(id, assignmentId, instruction, createdAt, status,
        nativeTaskId, summary, error, blockedReason, runCatching { json.decodeFromString<List<String>>(artifactsJson) }.getOrDefault(emptyList()))
}
