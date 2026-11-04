package top.wkbin.taixu.core.tools

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.wkbin.taixu.core.database.AiModelEntity
import top.wkbin.taixu.core.database.AiModelRepository
import top.wkbin.taixu.core.model.AiModelProfileExport
import top.wkbin.taixu.core.model.AiProfileImportMode
import java.util.UUID

/**
 * AI 模型档案的统一写入入口：负责 secretRef 生成、Key 持久化、活跃档案维护。
 * Settings / Chat / Onboarding 的模型保存与删除都应经由本类，避免各处自行拼装实体。
 */
class AiProfileWriter(
    private val aiModelDao: AiModelRepository,
    private val providerRepository: ModelCredentialStore,
) {
    private suspend fun <T> lifecycle(block: suspend () -> T): T = ModelCredentialLifetime.serialized(block)

    suspend fun importProfiles(
        profiles: List<AiModelProfileExport>,
        mode: AiProfileImportMode,
    ): Int = lifecycle { importModelProfiles(aiModelDao, providerRepository, profiles, mode) }

    /** Explicit native cleanup only. Materialized in-flight ModelConfig values retain their
     * key strings; stale profile resolution rechecks Room under this very same lock. */
    suspend fun cleanupRetiredCredentialsFromNative(): Int = lifecycle {
        val references=aiModelDao.observeAll().first().mapTo(mutableSetOf()) { it.secretRef }
        providerRepository.eraseRetiredModelKeys(references)
    }

    /** 解析多行 Key 文本为去重的 Key 列表 */
    fun parseApiKeys(raw: String): List<String> = raw
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()

    data class UpsertRequest(
        val id: String? = null,
        val name: String,
        val provider: String,
        /** 单模型或逗号分隔的多模型字符串 */
        val model: String,
        val baseUrl: String,
        /** 多行 Key 文本；为空时保留该档案已有的 Key */
        val apiKey: String = "",
        val requestsPerMinutePerKey: Int = 0,
        val temperature: Float? = null,
        val maxTokens: Int? = null,
        val topP: Float? = null,
        val reasoningMode: String? = null,
        val reasoningEffort: String? = null,
        val toolCallMode: String? = null,
        val contextTokens: Int? = null,
        /** 每模型压缩预算覆盖：压缩触发时保留的最近 token 上限（null = 不启用）。 */
        val compactionKeepRecentTokens: Int? = null,
        /** 每模型压缩预算覆盖：为 LLM 响应预留的 token（null = 内置默认）。 */
        val compactionReserveTokens: Int? = null,
        val customHeaders: String = "",
        val pureChatMode: Boolean = false,
        val visionEnabled: Boolean = true,
        val imageGenerationEnabled: Boolean = false,
        val responseApiEnabled: Boolean = false,
        /** Anthropic Prompt Caching 开关（默认开）。 */
        val promptCachingEnabled: Boolean = true,
        /** 是否使用 1 小时缓存 TTL。 */
        val promptCacheTtl1h: Boolean = false,
    )

    suspend fun upsertProfile(request: UpsertRequest) = lifecycle {
        upsertLocked(request)
    }

    private suspend fun upsertLocked(request: UpsertRequest) {
        val existing = aiModelDao.observeAll().first()
        val old = request.id?.let { aiModelDao.findById(it) }
        val modelId = request.id ?: UUID.randomUUID().toString()
        val submittedSecret = parseApiKeys(request.apiKey)
        // A changed endpoint/provider must never inherit the previous provider's credentials.
        require(old == null || (old.provider.trim() == request.provider.trim() && old.baseUrl.trim() == request.baseUrl.trim()) || submittedSecret.isNotEmpty()) {
            "更换模型来源必须在原生表单重新填写凭据，不能复用旧密钥"
        }
        val secretRef = "model_${modelId.replace("-", "")}_${UUID.randomUUID().toString().replace("-", "")}"
        val submittedKeys = parseApiKeys(request.apiKey)
        val existingKeys = old?.secretRef?.takeIf { it.isNotBlank() }?.let { providerRepository.readModelApiKeys(it) }.orEmpty()
        val keys = submittedKeys.ifEmpty { existingKeys }
        // New reference first: old readers keep valid credentials until the Room CAS commits.
        providerRepository.setModelApiKeys(secretRef, keys)
        val replacement = AiModelEntity(
                id = modelId,
                name = request.name.trim(),
                provider = request.provider.trim(),
                model = request.model.trim(),
                baseUrl = request.baseUrl.trim(),
                secretRef = secretRef,
                isActive = old?.isActive ?: existing.none { it.isActive },
                createdAt = old?.createdAt ?: System.currentTimeMillis(),
                temperature = request.temperature,
                maxTokens = request.maxTokens,
                topP = request.topP,
                reasoningMode = request.reasoningMode?.ifBlank { null },
                reasoningEffort = request.reasoningEffort?.ifBlank { null },
                toolCallMode = request.toolCallMode?.ifBlank { null },
                contextTokens = request.contextTokens,
                compactionKeepRecentTokens = request.compactionKeepRecentTokens,
                compactionReserveTokens = request.compactionReserveTokens,
                customHeaders = request.customHeaders.trim(),
                pureChatMode = request.pureChatMode,
                visionEnabled = request.visionEnabled,
                imageGenerationEnabled = request.imageGenerationEnabled,
                responseApiEnabled = request.responseApiEnabled,
                promptCachingEnabled = request.promptCachingEnabled,
                promptCacheTtl1h = request.promptCacheTtl1h,
                apiKeyCount = submittedKeys.ifEmpty { existingKeys }.size,
                requestsPerMinutePerKey = request.requestsPerMinutePerKey.coerceAtLeast(0),

            )
        try {
            aiModelDao.importBatch(existing, listOf(replacement))
        } catch (failure: Throwable) {
            // A journal/transport failure may be ambiguous: do not delete a possibly referenced key.
            val committed = runCatching { aiModelDao.findById(modelId)?.secretRef == secretRef }.getOrNull()
            if (committed == false) runCatching { providerRepository.removeModelApiKey(secretRef) }
            throw failure
        }
        old?.secretRef?.takeIf { it.isNotBlank() && it != secretRef }?.let { providerRepository.retireModelApiKey(it) }
        // Retain the old secret reference until native cleanup can prove there are no in-flight
        // model requests. It is never exposed to AI and never silently reused for another source.
    }

    suspend fun deleteProfile(id: String) = lifecycle {
        val expected = aiModelDao.observeAll().first()
        require(expected.any { it.id == id }) { "模型档案不存在" }
        aiModelDao.deleteSnapshot(expected,id)
        expected.single { it.id==id }.secretRef.takeIf { it.isNotBlank() }?.let { providerRepository.retireModelApiKey(it) }
        // Credential reference retirement is deliberately separate from deletion: in-flight
        // approved requests may still hold it. Native cleanup must verify liveness first.
    }
}
