package top.wkbin.taixu.runtime.terminal

import com.termux.terminal.TerminalSession
import top.wkbin.taixu.core.database.TerminalSessionEntity
import top.wkbin.taixu.core.database.TerminalSessionRepository
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.checkRuntimeDisposal
import top.wkbin.taixu.runtime.withRuntimeDisposal
import top.wkbin.taixu.runtime.shell.SessionConfig
import top.wkbin.taixu.runtime.environment.ExecutionEnvironmentFactory
import top.wkbin.taixu.runtime.environment.LocalTerminalLaunch
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TerminalSessionHandle internal constructor(
    val id: String,
    val label: String,
    val workingDirectory: String,
    val distributionId: String = "ubuntu",
    internal val process: TerminalProcess,
    /** 人类可读的环境标识（远程=主机名）；本地终端为 null。 */
    val environmentLabel: String? = null,
) {
    val termuxSession: TerminalSession get() = process.session
    val isAlive: Boolean get() = process.isAlive
}

data class TerminalRestoreFailure(val id: String, val label: String, val message: String)

/** Multi-session Termux console; creation, rollback and disposal retain explicit resource ownership. */
class TerminalSessionManager(
    private val linuxRuntime: LinuxRuntime,
    private val terminalSessionDao: TerminalSessionRepository,
    private val sessionClientRouter: TerminalSessionClientRouter,
    private val environmentFactory: ExecutionEnvironmentFactory? = null,
    private val processFactory: TerminalProcessFactory = TermuxTerminalProcessFactory(),
    private val remoteProcessFactory: RemoteTerminalProcessFactory = TermuxRemoteTerminalProcessFactory(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateLock = Any()
    private val resources = mutableMapOf<String, TerminalOwnedResources>()
    private val closeAllMutex = Mutex()
    private val restoreMutex = Mutex()
    private var adminReserved = false
    // Only private restore call sites receive this token; Job ancestry is not authority.
    private var recoveryToken: Any? = null
    private var closingAll = false
    private var nativeClosures = 0
    private var generation = 0L
    private var fallbackPending = false
    private val _handles = MutableStateFlow<List<TerminalSessionHandle>>(emptyList())
    val handles: StateFlow<List<TerminalSessionHandle>> = _handles.asStateFlow()
    private val _restoreFailures = MutableStateFlow<List<TerminalRestoreFailure>>(emptyList())
    val restoreFailures: StateFlow<List<TerminalRestoreFailure>> = _restoreFailures.asStateFlow()
    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()
    val activeHandle: StateFlow<TerminalSessionHandle?> =
        combine(_handles, _activeId) { handles, id -> handles.firstOrNull { it.id == id } }
            .stateIn(scope, SharingStarted.Eagerly, null)
    @Volatile private var restoredOnce = false

    suspend fun ensureActive(initialWorkingDirectory: String = DEFAULT_CWD) = withRestoration { token ->
        ensureActiveInternal(initialWorkingDirectory, token)
    }

    private suspend fun <T> withRestoration(
        expected: top.wkbin.taixu.runtime.admin.TerminalResourceSnapshot? = null,
        block: suspend (Any) -> T,
    ): T = restoreMutex.withLock {
        val token = Any()
        synchronized(stateLock) {
            if (expected != null) checkApprovedLocked(expected)
            check(!adminReserved && !closingAll && nativeClosures == 0 && resources.values.none { !it.creation.isCompleted }) {
                "终端生命周期操作正在进行，请稍后重试"
            }
            adminReserved = true; recoveryToken = token; generation++
        }
        try { block(token) }
        finally { synchronized(stateLock) { recoveryToken = null; adminReserved = false; generation++ } }
    }

    private suspend fun ensureActiveInternal(initialWorkingDirectory: String, token: Any) {
        val failures = mutableListOf<Throwable>()
        _handles.value.filterNot { it.isAlive }.forEach { dead ->
            val owned = synchronized(stateLock) { resources[dead.id] } ?: return@forEach
            try { release(owned, deleteRow = false) } catch (t: Throwable) { failures += t }
        }
        if (failures.isNotEmpty()) throw TerminalCleanupException(failures)
        if (_handles.value.isEmpty()) restoredOnce = false
        if (!restoredOnce) {
            try {
                val rows = terminalSessionDao.listAll()
                _restoreFailures.value = emptyList()
                for (row in rows) {
                    if (_handles.value.any { it.id == row.id }) continue
                    try {
                        synchronized(stateLock) { resources[row.id] }?.takeIf { it.failedCreation }?.let { release(it, deleteRow = false) }
                        check(!row.distributionId.startsWith("ssh:") || row.executionTargetJson != null) { "旧终端未记录主机身份，请移除记录后新建" }
                        createSessionOwned(row.label, row.workingDirectory, row.distributionId, row.id, recovery = token)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        _restoreFailures.update { it + TerminalRestoreFailure(row.id, row.label, e.message?.take(180) ?: "恢复失败") }
                        // Unconfirmed disposal must remain blocking and reachable for retry.
                        if (synchronized(stateLock) { resources.containsKey(row.id) }) throw e
                    }
                }
                restoredOnce = true
                if (_handles.value.isNotEmpty()) {
                    _activeId.value = _handles.value.firstOrNull()?.id ?: _activeId.value
                    return
                }
            } catch (t: Throwable) { restoredOnce = false; throw t }
        }
        if (_handles.value.isEmpty()) createSessionOwned("主终端", initialWorkingDirectory, null, UUID.randomUUID().toString(), recovery = token)
    }

    suspend fun createSession(
        label: String = "会话 ${_handles.value.size + 1}",
        workingDirectory: String = DEFAULT_CWD,
        distributionId: String? = null,
        id: String = UUID.randomUUID().toString(),
    ): TerminalSessionHandle = createSessionOwned(label, workingDirectory, distributionId, id)

    private suspend fun createSessionOwned(label: String, workingDirectory: String, distributionId: String?,
        id: String, expected: top.wkbin.taixu.runtime.admin.TerminalResourceSnapshot? = null,
        recovery: Any? = null): TerminalSessionHandle = coroutineScope {
        val capturedTarget = environmentFactory?.capture("", distributionId)
        val caller = currentCoroutineContext().job
        val owned = TerminalOwnedResources(id, caller)
        synchronized(stateLock) {
            val recovering = recovery != null && recovery === recoveryToken
            check(!closingAll && (!adminReserved || recovering)) { "终端生命周期操作正在进行，请稍后重试" }
            require(id !in resources) { "Terminal session already exists or awaits cleanup: $id" }
            if (expected != null) {
                checkApprovedLocked(expected)
                adminReserved = true
            }
            resources[id] = owned
            generation++
        }
        try {
            val previous = terminalSessionDao.listAll().firstOrNull { it.id == id }
            check(previous?.distributionId?.startsWith("ssh:") != true || previous.executionTargetJson != null) {
                "旧远程终端未记录主机身份，请关闭后新建终端"
            }
            val targetJson = previous?.executionTargetJson ?: capturedTarget
            if (targetJson != null) environmentFactory?.restore("terminal:$id", "", targetJson)
            owned.environment = environmentFactory?.open("terminal:$id", "", distributionId)
            checkCreation(owned)
            val environment = owned.environment
            val targetDistro = environment?.id?.distribution ?: distributionId ?: linuxRuntime.activeDistroId.value
            val cwd = if (environment != null && environment !is LocalTerminalLaunch)
                environment.workingDirectory(workingDirectory.takeUnless { it == DEFAULT_CWD }) else workingDirectory
            val storedDirectory = if (workingDirectory == DEFAULT_CWD) cwd else workingDirectory
            val config = SessionConfig(workingDirectory = cwd, showBanner = true)
            checkCreation(owned)
            owned.process = when (environment) {
                null -> processFactory.create(linuxRuntime.buildInteractiveLaunch(config, targetDistro), sessionClientRouter)
                is LocalTerminalLaunch -> processFactory.create(environment.terminalLaunch(config), sessionClientRouter)
                else -> remoteProcessFactory.create(
                    LinuxSessionTerminalBackend(environment.openTerminal(config), scope), label, sessionClientRouter)
            }
            // Install before publishing the session or allowing TerminalView to start it.
            owned.process!!.session.bindInputOwner { session, enqueue ->
                // Admit under the owner lock, but never block on the PTY queue while holding it:
                // a full queue must not stall close/cleanup of other sessions.
                val admitted = synchronized(stateLock) {
                    if (!adminReserved && !closingAll && resources[id] === owned &&
                        owned.published && !owned.closing && owned.process?.session === session) {
                        generation++ // Invalidate approval before any partial enqueue.
                        true
                    } else false
                }
                if (admitted) enqueue.run()
            }
            checkCreation(owned)
            owned.rollback = {
                if (previous == null) terminalSessionDao.delete(id) else terminalSessionDao.upsert(previous)
            }
            terminalSessionDao.upsert(TerminalSessionEntity(id = id, label = label, workingDirectory = storedDirectory,
                distributionId = targetDistro, createdAt = System.currentTimeMillis(), sortOrder = terminalSessionDao.nextOrder(),
                executionTargetJson = targetJson))
            checkCreation(owned)
            val handle = TerminalSessionHandle(id, label, storedDirectory, targetDistro, owned.process!!,
                environment?.takeUnless { it is LocalTerminalLaunch }?.label)
            synchronized(stateLock) {
                check(!owned.closing && !closingAll && resources[id] === owned) { "Terminal session was disposed during creation" }
                _handles.update { it + handle }
                _activeId.value = id
                owned.published = true
                owned.rollback = null
                generation++
            }
            handle
        } catch (t: Throwable) {
            synchronized(stateLock) { owned.failedCreation = true; owned.closing = true; generation++ }
            try { owned.release(drainCreation = false); forget(owned) }
            catch (cleanup: Throwable) { t.addSuppressed(cleanup) }
            throw t
        } finally {
            if (expected != null) synchronized(stateLock) { adminReserved = false }
        }
    }

    private suspend fun checkCreation(owned: TerminalOwnedResources) {
        currentCoroutineContext().ensureActive()
        check(!owned.closing) { "Terminal session was disposed during creation" }
    }

    suspend fun openOrSwitchToProject(project: String, workingDirectory: String, distributionId: String? = null): TerminalSessionHandle {
        ensureActive()
        val existing = _handles.value.firstOrNull { it.workingDirectory == workingDirectory &&
            (distributionId == null || it.distributionId == distributionId) }
        if (existing != null) { switchTo(existing.id); return existing }
        return createSession(project.ifBlank { "工作区" }, workingDirectory, distributionId)
    }

    fun switchTo(id: String) = synchronized(stateLock) {
        check(!adminReserved && !closingAll) { "终端管理事务正在进行" }
        if (resources[id]?.closing == false && _handles.value.any { it.id == id }) { _activeId.value = id; generation++ }
    }

    suspend fun removeFailedRestoration(id: String) = withRestoration { token ->
        require(_restoreFailures.value.any { it.id == id } && _handles.value.none { it.id == id }) { "此记录不是恢复失败的终端" }
        closeSessionOwned(id, token)
        terminalSessionDao.delete(id)
        _restoreFailures.update { failures -> failures.filterNot { it.id == id } }
    }

    suspend fun retryFailedRestorations() = withRestoration { token ->
        restoredOnce = false
        ensureActiveInternal(DEFAULT_CWD, token)
    }

    suspend fun closeSession(id: String) = closeSessionOwned(id)

    private suspend fun closeSessionOwned(id: String, recovery: Any? = null) {
        val owned = synchronized(stateLock) { resources[id] } ?: return
        checkRuntimeDisposal(owned)
        checkRuntimeDisposal(owned.disposalMutex)
        val caller = currentCoroutineContext().job
        val version = synchronized(stateLock) {
            check(!closingAll && (!adminReserved || (recovery != null && recovery === recoveryToken))) {
                "终端管理事务正在进行"
            }
            if (resources[id] !== owned) return
            check(!owned.owns(caller)) { "Cannot close terminal from its own creation" }
            owned.closing = true
            nativeClosures++
            ++generation
        }
        try { release(owned, deleteRow = true) }
        finally { synchronized(stateLock) { nativeClosures-- } }
        val fallback = synchronized(stateLock) {
            (owned.published && _handles.value.isEmpty() && !closingAll && !adminReserved && generation == version + 1 && !fallbackPending)
                .also { if (it) fallbackPending = true }
        }
        if (fallback) try { createSession(label = "主终端", workingDirectory = DEFAULT_CWD) }
        finally { synchronized(stateLock) { fallbackPending = false } }
    }

    private suspend fun release(owned: TerminalOwnedResources, deleteRow: Boolean) = withRuntimeDisposal(owned.disposalMutex) {
        owned.disposalMutex.withLock {
            if (synchronized(stateLock) { resources[owned.id] !== owned }) return@withLock
            owned.release()
            if (deleteRow && !owned.failedCreation) terminalSessionDao.delete(owned.id)
            forget(owned)
        }
    }

    private fun forget(owned: TerminalOwnedResources) = synchronized(stateLock) {
        if (resources[owned.id] === owned) {
            resources.remove(owned.id)
            generation++
            _handles.update { handles -> handles.filterNot { it.id == owned.id } }
            if (_activeId.value == owned.id) _activeId.value = _handles.value.lastOrNull()?.id
        }
    }

    suspend fun closeAllSessions() {
        synchronized(stateLock) { resources.values.toList() }.forEach {
            checkRuntimeDisposal(it); checkRuntimeDisposal(it.disposalMutex)
        }
        val caller = currentCoroutineContext().job
        synchronized(stateLock) {
            check(resources.values.none { it.owns(caller) }) { "Cannot close terminals from their own creation" }
        }
        withContext(NonCancellable) { closeAllMutex.withLock {
            val snapshot = synchronized(stateLock) {
                check(!adminReserved) { "终端管理事务正在进行" }
                closingAll = true; generation++
                resources.values.toList().also { all -> all.forEach { it.closing = true; it.creation.cancel() } }
            }
            try {
                val failures = mutableListOf<Throwable>()
                for (owned in snapshot) {
                    try { release(owned, deleteRow = true) } catch (t: Throwable) { failures += t }
                }
                if (failures.isNotEmpty()) throw TerminalCleanupException(failures)
                // Only the captured generation is authorized for deletion. A late repository
                // row or newly published handle must never be swept by a global deleteAll.
                val capturedIds = snapshot.map { it.id }.toSet()
                _restoreFailures.update { rows -> rows.filterNot { it.id in capturedIds } }
                synchronized(stateLock) {
                    if (_handles.value.isEmpty()) { _activeId.value = null; restoredOnce = false }
                }
            } finally { synchronized(stateLock) { closingAll = false } }
        } }
    }

    /** Explicit UI writes also validate identity, never route a stale view by active id. */
    fun writeNative(session: TerminalSession, data: ByteArray) = synchronized(stateLock) {
        if (data.isEmpty() || adminReserved || closingAll) return@synchronized
        val owned = resources.values.firstOrNull { it.published && !it.closing && it.process?.session === session }
            ?: return@synchronized
        // The final session's enqueue guard below is authoritative for every hidden View path.
        owned.process?.write(data)
        Unit
    }

    fun write(id: String, data: ByteArray) = synchronized(stateLock) {
        check(!adminReserved && !closingAll) { "终端管理事务正在进行" }
        val owned = resources[id] ?: return@synchronized
        if (owned.closing || !owned.published) return@synchronized
        generation++ // Consume before dispatch: a failed/partial write must not preserve approval.
        owned.process?.write(data)
        Unit
    }
    suspend fun managementSnapshot(): top.wkbin.taixu.runtime.admin.TerminalResourceSnapshot = synchronized(stateLock) {
        managementSnapshotLocked()
    }
    private fun managementSnapshotLocked() = top.wkbin.taixu.runtime.admin.TerminalResourceSnapshot(
        generation, _activeId.value, resources.keys.toSet(),
        resources.values.filter { !it.published }.map { it.id }.toSet(), generation)

    suspend fun createApproved(expected: top.wkbin.taixu.runtime.admin.TerminalResourceSnapshot,
        label: String, cwd: String, distribution: String): TerminalSessionHandle =
        createSessionOwned(label, cwd, distribution, UUID.randomUUID().toString(), expected)

    suspend fun recoverApproved(expected: top.wkbin.taixu.runtime.admin.TerminalResourceSnapshot) = withRestoration(expected) { token ->
        restoredOnce = false
        ensureActiveInternal(DEFAULT_CWD, token)
    }

    private fun checkApprovedLocked(expected: top.wkbin.taixu.runtime.admin.TerminalResourceSnapshot) {
        if (adminReserved || closingAll || nativeClosures != 0 || resources.values.any { !it.creation.isCompleted } ||
            expected != managementSnapshotLocked()) throw top.wkbin.taixu.runtime.admin.AdminSnapshotChanged()
    }

    /** Manager-routed switching/input share this boundary; raw Termux UI input must also route here. */
    fun switchApproved(expected: top.wkbin.taixu.runtime.admin.TerminalResourceSnapshot, id: String) = synchronized(stateLock) {
        checkApprovedLocked(expected)
        require(id in expected.resourceIds && resources[id]?.closing == false && _handles.value.any { it.id == id })
        _activeId.value = id; generation++
    }

    fun inputApproved(expected: top.wkbin.taixu.runtime.admin.TerminalResourceSnapshot, id: String, text: String) = synchronized(stateLock) {
        checkApprovedLocked(expected)
        require(id in expected.resourceIds && text.length <= 65536)
        val row = requireNotNull(resources[id]); check(!row.closing)
        generation++ // Consume even when dispatch partially fails.
        requireNotNull(row.process).write(text.toByteArray(Charsets.UTF_8))
    }

    /** Reserve captured resources before suspending cleanup. No JVM monitor is held across IO. */
    suspend fun closeApproved(expected: top.wkbin.taixu.runtime.admin.TerminalResourceSnapshot, ids: Set<String>) {
        val caller = currentCoroutineContext().job
        val captured = synchronized(stateLock) {
            checkApprovedLocked(expected)
            require(ids.all { it in expected.resourceIds })
            val rows = ids.map { requireNotNull(resources[it]) }
            rows.forEach { check(!it.owns(caller)) { "Cannot close own creation" } }
            adminReserved = true; generation++
            rows.forEach { it.closing = true }
            rows
        }
        try {
            val failures = mutableListOf<Throwable>()
            for (row in captured) try { release(row, deleteRow = true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { failures += failure }
            if (failures.isNotEmpty()) throw TerminalCleanupException(failures)
        } finally { synchronized(stateLock) { adminReserved = false } }
    }

    fun paste(id: String, text: String) = write(id, text.toByteArray(Charsets.UTF_8))
    fun interrupt(id: String) = write(id, byteArrayOf(3))

    private companion object { const val DEFAULT_CWD = "/root" }
}
