package top.wkbin.taixu.harness.workflow

import java.security.MessageDigest
import kotlinx.serialization.json.*
import top.wkbin.taixu.core.model.workflow.*

/** Binds the entire addition, frozen target/source, graph chain and installed plugin identities. */
internal fun expansionAdmissionDigest(
    binding: WorkflowExecutionBinding, executionId: String, nodeId: String,
    parent: WorkflowDefinition, next: WorkflowDefinition, patch: WorkflowExpansion,
    stamps: Map<String, String>,
): String {
    val snapshot = buildJsonObject {
        put("binding", binding.encode())
        put("executionId", executionId)
        put("nodeId", nodeId)
        put("parentHash", WorkflowExecutionBinding.hash(parent))
        put("definitionHash", WorkflowExecutionBinding.hash(next))
        put("patch", WorkflowExpansions.encode(patch))
        put("pluginStamps", JsonObject(stamps.toSortedMap().mapValues { JsonPrimitive(it.value) }))
    }
    return MessageDigest.getInstance("SHA-256").digest(snapshot.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
