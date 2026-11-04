package top.wkbin.taixu.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import java.security.MessageDigest

/** Deliberately contains no provider, endpoint, headers, key reference, or arbitrary profile text. */
data class ManagedModel(
    val id: String,
    val globalDefault: Boolean,
    val settings: ModelSettings,
)

data class ModelSettings(
    val temperature: Float?, val maxTokens: Int?, val topP: Float?,
    val reasoningMode: String?, val reasoningEffort: String?,
) {
    fun validate() {
        require(temperature == null || temperature.isFinite() && temperature in 0f..2f)
        require(topP == null || topP.isFinite() && topP in 0f..1f)
        require(maxTokens == null || maxTokens in 1..2_000_000)
        require(reasoningMode == null || reasoningMode in setOf("enabled", "disabled"))
        require(reasoningEffort == null || reasoningEffort in setOf("low", "medium", "high"))
    }
}

data class ModelManagementSnapshot(
    val models: List<ManagedModel>, val sessionModelId: String?, val sessionVariant: String?,
    val digest: String,
) {
    override fun toString() = "ModelManagementSnapshot(redacted)"
}

sealed interface ModelMutation {
    val modelId: String
    data class Settings(override val modelId: String, val value: ModelSettings) : ModelMutation
    data class GlobalDefault(override val modelId: String) : ModelMutation
    /** Only the controlled source session, never a model-supplied destination session. */
    data class SessionSelection(override val modelId: String) : ModelMutation
}

/** Repository boundary. Implement in core/database, never inject a DAO into harness. */
interface ModelManagementRepository {
    suspend fun snapshot(sourceSession: String): ModelManagementSnapshot
    suspend fun compareAndSet(sourceSession: String, expectedDigest: String, mutation: ModelMutation): Boolean
}

/** Mix into existing AiModelDao via `interface AiModelDao : ModelManagementCas`.
 * Room generates the transaction wrapper on the concrete DAO; no schema migration or raw SQLite.
 * The full entity digest also detects native edits to fields excluded from the safe projection.
 */
@Dao
interface ModelManagementCas {
    @Query("SELECT * FROM harness_models ORDER BY id")
    suspend fun managementModels(): List<AiModelEntity>

    @Query("SELECT * FROM harness_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun managementSession(sessionId: String): HarnessSessionEntity?

    @Query("UPDATE harness_models SET temperature=:temperature, maxTokens=:maxTokens, topP=:topP, reasoningMode=:reasoningMode, reasoningEffort=:reasoningEffort WHERE id=:id")
    suspend fun managementUpdateSettings(id: String, temperature: Float?, maxTokens: Int?, topP: Float?, reasoningMode: String?, reasoningEffort: String?): Int

    @Query("UPDATE harness_models SET isActive = CASE WHEN id = :id THEN 1 ELSE 0 END")
    suspend fun managementActivate(id: String): Int

    @Query("UPDATE harness_sessions SET modelId=:id, modelVariant=CASE WHEN modelId=:id THEN modelVariant ELSE NULL END, updatedAt=:updatedAt WHERE id=:sessionId")
    suspend fun managementSelectSession(sessionId: String, id: String, updatedAt: Long): Int

    @Transaction
    suspend fun modelManagementSnapshot(sessionId: String): ModelManagementSnapshot {
        val session = requireNotNull(managementSession(sessionId))
        val models = managementModels()
        // Hash private full rows inside database layer. No raw values reach harness, errors, or UI.
        val sha = MessageDigest.getInstance("SHA-256")
        fun field(value: Any?) {
            if (value == null) { sha.update(byteArrayOf(0)); return }
            sha.update(byteArrayOf(1))
            val bytes = value.toString().toByteArray(Charsets.UTF_8)
            sha.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            sha.update(bytes)
        }
        field(models.size)
        models.forEach { m ->
            listOf(m.id, m.name, m.provider, m.model, m.baseUrl, m.secretRef, m.isActive,
                m.createdAt, m.temperature, m.maxTokens, m.topP, m.reasoningMode, m.reasoningEffort,
                m.toolCallMode, m.contextTokens, m.customHeaders, m.pureChatMode, m.visionEnabled,
                m.imageGenerationEnabled, m.responseApiEnabled, m.promptCachingEnabled,
                m.promptCacheTtl1h, m.apiKeyCount, m.requestsPerMinutePerKey,
                m.compactionKeepRecentTokens, m.compactionReserveTokens).forEach(::field)
        }
        listOf(session.id, session.title, session.createdAt, session.updatedAt, session.modelId,
            session.modelVariant, session.workspace, session.projectType, session.approvalMode,
            session.runMode, session.executionTargetJson, session.pinnedAt).forEach(::field)
        val projection = models.map {
            require(it.id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
            val settings = ModelSettings(it.temperature, it.maxTokens, it.topP, it.reasoningMode, it.reasoningEffort)
            // Invalid legacy metadata is rejected, never reflected as arbitrary text.
            settings.validate()
            ManagedModel(it.id, it.isActive, settings)
        }
        require(session.modelId == null || projection.any { it.id == session.modelId })
        return ModelManagementSnapshot(projection, session.modelId, session.modelVariant,
            sha.digest().joinToString("") { "%02x".format(it) })
    }

    @Transaction
    suspend fun modelManagementCompareAndSet(sessionId: String, expectedDigest: String, mutation: ModelMutation): Boolean {
        val current = modelManagementSnapshot(sessionId)
        if (current.digest != expectedDigest || current.models.none { it.id == mutation.modelId }) return false
        when (mutation) {
            is ModelMutation.Settings -> {
                mutation.value.validate()
                val v = mutation.value
                check(managementUpdateSettings(mutation.modelId, v.temperature, v.maxTokens, v.topP, v.reasoningMode, v.reasoningEffort) == 1)
            }
            is ModelMutation.GlobalDefault -> check(managementActivate(mutation.modelId) > 0)
            is ModelMutation.SessionSelection -> check(managementSelectSession(sessionId, mutation.modelId, System.currentTimeMillis()) == 1)
        }
        return true
    }
}

/** Uses the generated Room DAO transaction, not a harness-side read/write emulation. */
class RoomModelManagementRepository(private val dao: ModelManagementCas) : ModelManagementRepository {
    override suspend fun snapshot(sourceSession: String) = dao.modelManagementSnapshot(sourceSession)
    override suspend fun compareAndSet(sourceSession: String, expectedDigest: String, mutation: ModelMutation) =
        dao.modelManagementCompareAndSet(sourceSession, expectedDigest, mutation)
}
