package top.wkbin.taixu.harness.admin.runtime

import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.json.*

/** No commands, credentials, paths, archives or raw errors. Interrupted work is UNKNOWN, never replayed. */
class RuntimeOperationJournal(private val directory: File) {
    private val monitor = Any()
    init { check(directory.isDirectory || directory.mkdirs()) }
    fun write(operation: RuntimeOperation) = synchronized(monitor) {
        require(operation.id.matches(Regex("[a-f0-9-]{36}")))
        val atomic = AtomicFile(File(directory, operation.id + ".json"))
        val output = atomic.startWrite()
        try {
            val text = buildJsonObject {
                put("id", operation.id); put("state", operation.state)
                put("cleanupPending", operation.cleanupPending)
            }.toString()
            output.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output)
        } catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }
    fun recover(): List<RuntimeOperation> = synchronized(monitor) {
        directory.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9-]{36}\\.json")) }.map { file ->
            val row = Json.parseToJsonElement(AtomicFile(file).readFully().toString(Charsets.UTF_8)).jsonObject
            val state = row.getValue("state").jsonPrimitive.content
            RuntimeOperation(row.getValue("id").jsonPrimitive.content,
                if (state in setOf("queued", "running")) "unknown_partial" else state,
                cleanupPending = state in setOf("queued", "running") || row["cleanupPending"]?.jsonPrimitive?.booleanOrNull == true)
        }
    }
}
