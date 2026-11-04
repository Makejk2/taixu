package top.wkbin.taixu.core.tools.admin

import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.json.*

/** Private metadata journal, never scripts, document URIs, payloads or raw installer output. */
class PackageOperationJournal(private val directory: File) {
    private val lock = Any()
    init { check(directory.isDirectory || directory.mkdirs()) }
    fun save(row: PackageOperation) = synchronized(lock) {
        require(row.id.matches(Regex("[a-f0-9-]{36}")))
        val file = AtomicFile(File(directory, row.id + ".json"))
        val out = file.startWrite()
        try {
            out.write(buildJsonObject {
                put("id", row.id); put("owner", row.owner); put("state", row.state)
                put("partial", row.partial)
            }.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (failure: Throwable) { file.failWrite(out); throw failure }
    }
    fun restore(): List<PackageOperation> = synchronized(lock) {
        directory.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9-]{36}\\.json")) }.map {
            val obj = Json.parseToJsonElement(AtomicFile(it).readFully().toString(Charsets.UTF_8)).jsonObject
            val state = obj.getValue("state").jsonPrimitive.content
            val interrupted = state in setOf("queued", "running")
            PackageOperation(obj.getValue("id").jsonPrimitive.content, obj.getValue("owner").jsonPrimitive.content,
                if (interrupted) "unknown_partial" else state, true,
                interrupted || obj.getValue("partial").jsonPrimitive.boolean)
        }
    }
}
