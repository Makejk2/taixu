package top.wkbin.taixu.core.database

import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.wkbin.taixu.core.model.workflow.WorkflowDefinition

/** Same public-definition canonical serialization as the admin approval snapshot.
 * Decode persisted JSON first: Room's encodeDefaults/explicitNulls settings must not affect CAS.
 * An unreadable row is NOT absent and must never be overwritten by an absent-create proposal.
 */
object WorkflowDefinitionCas {
    const val ABSENT = "absent"
    private val decoder = Json { ignoreUnknownKeys = true }

    fun hash(definition: WorkflowDefinition): String = MessageDigest.getInstance("SHA-256")
        .digest(Json.encodeToString(definition).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    internal fun matches(entity: WorkflowEntity?, expectedHash: String): Boolean {
        if (entity == null) return expectedHash == ABSENT
        val definition = runCatching {
            decoder.decodeFromString<WorkflowDefinition>(entity.jsonContent)
        }.getOrNull() ?: return false
        return hash(definition) == expectedHash
    }
}
