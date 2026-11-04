package top.wkbin.taixu.harness.admin.model

import kotlinx.serialization.json.*

/** Strict decoder: no credential/provider/endpoint/header fields, even nested or unknown ones. */
class ModelAdminBackend(val service: ModelAdminService) {
    suspend fun execute(actor: ModelAdminActor, args: JsonObject): ModelAdminResult = try {
        when (text(args, "action")) {
            "list" -> { keys(args, "module", "action"); service.list(actor) }
            "get" -> { keys(args, "module", "action", "modelId"); service.get(actor, text(args, "modelId")) }
            "propose" -> {
                keys(args, "module", "action", "operation", "modelId", "patch")
                val id = text(args, "modelId")
                val command = when (text(args, "operation")) {
                    "patch" -> ModelAdminCommand.Patch(id, patch(args["patch"] as JsonObject))
                    "global_default" -> { require("patch" !in args); ModelAdminCommand.SetGlobalDefault(id) }
                    "current_session" -> { require("patch" !in args); ModelAdminCommand.SelectForCurrentSession(id) }
                    else -> error("invalid")
                }
                service.propose(actor, command)
            }
            else -> ModelAdminResult.Rejected("MODEL_ADMIN_ACTION")
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (_: Exception) { ModelAdminResult.Rejected("MODEL_ADMIN_ARGUMENTS") }

    companion object {
        internal fun keys(args: JsonObject, vararg allowed: String) {
            require(args.keys.all { it in allowed })
            if ("module" in args) require(text(args, "module") == "model")
        }
        internal fun text(args: JsonObject, key: String): String {
            val value = args[key] as JsonPrimitive
            require(value.isString)
            return value.content
        }
        private fun patch(args: JsonObject): ModelProfilePatch {
            keys(args, "temperature", "maxTokens", "topP", "reasoningMode", "reasoningEffort")
            require(args.isNotEmpty())
            fun <T> field(key: String, parse: (JsonPrimitive) -> T): FieldEdit<T> {
                if (key !in args) return FieldEdit.Keep
                val v = args[key]
                return FieldEdit.Set(if (v == JsonNull) null else parse(v as JsonPrimitive))
            }
            return ModelProfilePatch(
                field("temperature") { require(!it.isString); it.float },
                field("maxTokens") { require(!it.isString); it.int },
                field("topP") { require(!it.isString); it.float },
                field("reasoningMode") { require(it.isString); it.content },
                field("reasoningEffort") { require(it.isString); it.content },
            )
        }
    }
}

/** A serializable output projection only. Snapshot digests and stored profile text are not output. */
fun ModelAdminResult.toSafeJson(): JsonObject = buildJsonObject {
    fun model(value: top.wkbin.taixu.core.database.ManagedModel) = buildJsonObject {
        put("modelId", value.id); put("globalDefault", value.globalDefault)
        put("temperature", value.settings.temperature?.let(::JsonPrimitive) ?: JsonNull)
        put("maxTokens", value.settings.maxTokens?.let(::JsonPrimitive) ?: JsonNull)
        put("topP", value.settings.topP?.let(::JsonPrimitive) ?: JsonNull)
        put("reasoningMode", value.settings.reasoningMode?.let(::JsonPrimitive) ?: JsonNull)
        put("reasoningEffort", value.settings.reasoningEffort?.let(::JsonPrimitive) ?: JsonNull)
    }
    fun proposal(value: ModelProposalView) {
        put("proposalId", value.proposalId); put("operation", value.operation)
        put("modelId", value.targetId); put("expiresAt", value.expiresAt)
        put("willResetVariant", value.willResetVariant)
        value.proposedSettings?.let {
            put("proposedSettings", model(top.wkbin.taixu.core.database.ManagedModel(value.targetId, false, it)))
        }
    }
    when (val result = this@toSafeJson) {
        is ModelAdminResult.Catalog -> {
            put("status", "ok"); put("models", JsonArray(result.value.models.map(::model)))
            put("currentSessionModelId", result.value.currentSessionModelId?.let(::JsonPrimitive) ?: JsonNull)
        }
        is ModelAdminResult.Profile -> { put("status", "ok"); put("profile", model(result.value)) }
        is ModelAdminResult.Proposal -> { put("status", "proposed"); proposal(result.value) }
        is ModelAdminResult.ApprovalRequired -> { put("status", "approval_required"); proposal(result.value) }
        ModelAdminResult.Applied -> put("status", "applied")
        is ModelAdminResult.Rejected -> { put("status", "rejected"); put("code", result.code) }
    }
}
