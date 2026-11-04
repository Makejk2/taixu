package top.wkbin.taixu.runtime.admin

import java.io.File

/** Must be implemented INSIDE owner services: compare and mutate under the same lifecycle lock.
 * An adapter-local mutex or a check followed by a public API call is NOT a valid implementation.
 * Missing owner integration intentionally prevents production construction (no unsafe default).
 */
interface RuntimeAdminTransactions {
    suspend fun <T> distro(expected: DistroResourceSnapshot, mutation: suspend () -> T): T
    suspend fun <T> terminal(expected: TerminalResourceSnapshot, mutation: suspend () -> T): T
    /** Owner must pin registry manifest, download and activate exactly approvedDigest under CAS. */
    suspend fun updatePinned(expected: DistroResourceSnapshot, target: String, approvedDigest: String): Any
    suspend fun distroSnapshot(): DistroResourceSnapshot
    suspend fun terminalSnapshot(): TerminalResourceSnapshot
}
data class DistroResourceSnapshot(
    val generation: Long, val activeId: String, val sourceId: String, val targetId: String,
    val workspaceId: String, val workspaceGeneration: Long,
    val rootfsDigests: Map<String, String>, val installedIds: Set<String>,
)
data class TerminalResourceSnapshot(
    val generation: Long, val activeId: String?, val resourceIds: Set<String>,
    val restoringIds: Set<String>, val environmentGeneration: Long,
)
class AdminSnapshotChanged : IllegalStateException("ADMIN_SNAPSHOT_CHANGED")

/** Native picker stages an immutable private copy. Never constructed from model path/URI. */
interface NativeImportHandle : AutoCloseable {
    val id: String
    val sha256: String
    val sizeBytes: Long
    suspend fun <T> withVerifiedArchive(block: suspend (File) -> T): T
}
sealed interface NativeImportCompletion {
    data class Selected(val handle: NativeImportHandle) : NativeImportCompletion
    data object Cancelled : NativeImportCompletion
    data class Failed(val code: String) : NativeImportCompletion
}
fun interface NativeImportPicker {
    /** Queue on main UI. Request is pending, NOT imported, until callback and approved execution. */
    fun request(requestId: String, completion: (NativeImportCompletion) -> Unit)
}
