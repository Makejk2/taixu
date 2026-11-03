package top.wkbin.taixu.harness.config

import java.util.UUID
import top.wkbin.taixu.core.datastore.AgentConfigPreferences
import top.wkbin.taixu.core.database.AgentApprovalAuditEntity
import top.wkbin.taixu.core.database.AgentApprovalAuditRepository

/** Production wiring: real DataStore and durable Room audit, not an in-memory settings shadow. */
fun createAppConfigBackend(store: AgentConfigPreferences, audit: AgentApprovalAuditRepository): AppConfigToolBackend =
    AppConfigToolBackend(AppConfigService(object : AppConfigPort {
        override suspend fun read(key: String) = store.readAgentConfig(key)
        override suspend fun compareAndSet(key: String, expected: String, value: String) =
            store.compareAndSetAgentConfig(key, expected, value)
    }, AppConfigGrantStore(), AppConfigAuditSink { entry ->
        audit.record(AgentApprovalAuditEntity(
            id = UUID.randomUUID().toString(), sessionId = entry.sessionId,
            kind = "app_config_${entry.action}", toolName = "app_config",
            detail = entry.detail, createdAt = entry.createdAt,
        ))
    }))
