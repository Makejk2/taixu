package top.wkbin.taixu.harness.directory

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.wkbin.taixu.harness.ApiToolDefinition
import top.wkbin.taixu.harness.ProviderClient
import top.wkbin.taixu.plugin.api.PluginDescriptor
import top.wkbin.taixu.plugin.api.PluginExtensionPoint

/**
 * Read-only, request-scoped catalogue; NOT an invocation, discovery or authorization API.
 * Callbacks must read already registered/configured state only. They must never connect, discover,
 * start/stop a service, execute a tool, or return settings values, credentials or conversation data.
 * Construct with callbacks rather than ToolExecutor/ProviderClient instances to avoid DI cycles.
 * A missing/failed callback fails closed for its own domain. No snapshots are retained between calls.
 */
class AppModuleDirectory(
    private val visibleNativeToolNames: (suspend () -> Set<String>)? = null,
    private val registeredPluginsSnapshot: (suspend () -> List<RegisteredPlugin>)? = null,
    private val hostBackendBound: (suspend () -> Boolean)? = null,
    private val enabledMcpServicesSnapshot: (suspend () -> List<EnabledMcpService>)? = null,
    /** Real provider definitions, not HarnessTool enum entries. Visibility is intersected below. */
    private val nativeDefinitions: () -> List<ApiToolDefinition> = { ProviderClient.TOOLS },
) {
    /** Make from registry.descriptors, not packaged manifests or an unverified external catalogue. */
    class RegisteredPlugin private constructor(
        val id: String,
        val toolIds: List<String>,
        val schemas: Map<String, JsonObject>,
    ) {
        companion object {
            /**
             * Schemas may only come from the owning registered HarnessPlugin.tools snapshot
             * (see HarnessPluginRegistry.registeredToolSchemas). Absent schemas stay explicitly
             * unknown. Never parse registry.inspect(): it is truncated, human-readable text.
             */
            fun fromRegisteredDescriptor(
                descriptor: PluginDescriptor,
                verifiedToolSchemas: Map<String, JsonObject> = emptyMap(),
            ): RegisteredPlugin {
                val ids = descriptor.extensions.filter { it.point == PluginExtensionPoint.TOOL }
                    .map { it.id }.distinct().sorted()
                return RegisteredPlugin(descriptor.id, ids, verifiedToolSchemas.filterKeys { it in ids }.toMap())
            }
        }
    }

    /** Only safe metadata: no URL, command, environment, headers, token, cached tools or config. */
    data class EnabledMcpService(val id: String)

    private data class Module(
        val id: String,
        val kind: String,
        val status: String,
        val reason: String,
        val tools: List<Tool> = emptyList(),
        val server: String? = null,
        val managementRoute: String? = null,
    )
    private data class Tool(val id: String, val schema: JsonObject?)
    private data class NativeModule(val id: String, val names: Set<String>, val route: String? = null)

    suspend fun modules(): JsonObject = envelope(snapshot(), includeSchemas = false)

    /** Unknown module IDs are rejected; there is no fallback to a tool call or MCP inspect. */
    suspend fun inspectModule(moduleId: String): JsonObject {
        require(moduleId.isNotBlank()) { "module is required" }
        val module = snapshot().singleOrNull { it.id == moduleId }
            ?: throw IllegalArgumentException("unknown module")
        return envelope(listOf(module), includeSchemas = true)
    }

    private suspend fun snapshot(): List<Module> {
        val visible = safely { visibleNativeToolNames?.invoke() }
        val definitions = safely { nativeDefinitions() }
        val tools = definitions.orEmpty().filter { it.function.name in visible.orEmpty() }
            .distinctBy { it.function.name }.map { Tool(it.function.name, it.function.parameters) }.sortedBy { it.id }
        val proxyVisible = tools.any { it.id == "use_capability" }
        val nativeReason = if (visible == null || definitions == null) "native_visibility_binding_unavailable" else "no_visible_native_tool"
        val result = mutableListOf(Module("native", "native", if (tools.isEmpty()) "unsupported" else "available",
            if (tools.isEmpty()) nativeReason else "current_visible_provider_definitions", tools))
        NATIVE_MODULES.forEach { spec ->
            val references = tools.filter { it.id in spec.names }
            result += Module(spec.id, "native_references", if (references.isEmpty()) "unsupported" else if (spec.route != null) "route_unverified" else "available",
                if (references.isEmpty()) nativeReason else if (spec.route != null)
                    "tool_reference_only_backend_and_actor_permissions_unverified" else "partial_tool_surface_not_full_module_api", references,
                managementRoute = spec.route)
        }

        val hostBound = safely { hostBackendBound?.invoke() } == true
        val hostTools = if (hostBound && proxyVisible) HostCapabilityDirectory.DEFERRED_ACTIONS
            .filterNot { HostCapabilityDirectory.isHidden(it.name) }
            .map { Tool(it.name, HostCapabilityDirectory.schemaFor(it.name)) } else emptyList()
        result += Module("host", "host", if (hostTools.isEmpty()) "unsupported" else "available",
            if (!hostBound) "host_binding_unavailable" else if (!proxyVisible) "capability_proxy_not_visible" else "registered_deferred_host_actions",
            hostTools, HostCapabilityDirectory.SERVER_ID)

        val plugins = safely { registeredPluginsSnapshot?.invoke() }
        result += Module("plugins", "plugin_catalog", if (plugins == null) "unsupported" else "catalog_only",
            if (plugins == null) "plugin_snapshot_binding_unavailable" else "registered_TOOL_extensions_only")
        // Do not infer TOOL support from PAGE/PROVIDER/WORKFLOW extensions, plugin names or manifests.
        plugins.orEmpty().filter { it.toolIds.isNotEmpty() }.distinctBy { it.id }.sortedBy { it.id }.forEach { plugin ->
            result += Module("plugin:${plugin.id}", "plugin", if (proxyVisible) "registered" else "unsupported",
                if (proxyVisible) "schema_unknown_unless_verified_snapshot_supplied" else "capability_proxy_not_visible",
                if (proxyVisible) plugin.toolIds.map { Tool(it, plugin.schemas[it]) } else emptyList(), "plugin.${plugin.id}")
        }

        val mcp = safely { enabledMcpServicesSnapshot?.invoke() }
        result += Module("mcp", "mcp_catalog", if (mcp == null) "unsupported" else "catalog_only",
            if (mcp == null) "enabled_mcp_snapshot_binding_unavailable" else "enabled_services_only_no_tool_discovery")
        mcp.orEmpty().distinctBy { it.id }.sortedBy { it.id }.forEach { service ->
            result += Module("mcp:${service.id}", "mcp", if (proxyVisible) "enabled_uninspected" else "unsupported",
                if (proxyVisible) "tool_schema_not_read_or_discovered" else "capability_proxy_not_visible", server = service.id)
        }
        UI_ONLY_MODULES.forEach { id -> result += Module(id, "ui_only", "unsupported", "no_dedicated_conversation_api") }
        return result.toList()
    }

    private fun envelope(modules: List<Module>, includeSchemas: Boolean): JsonObject = buildJsonObject {
        put("directoryVersion", 1)
        put("readOnly", true)
        put("authorization", "catalogue_is_not_permission_or_execution_guarantee")
        put("coverage", "partial_not_all_application_or_conversation_APIs")
        put("modules", JsonArray(modules.map { module -> buildJsonObject {
            put("id", module.id)
            put("kind", module.kind)
            put("status", module.status)
            put("reason", module.reason)
            module.server?.let { put("server", it) }
            module.managementRoute?.let { put("managementRoute", it) }
            put("toolIds", JsonArray(module.tools.map { JsonPrimitive(it.id) }))
            if (includeSchemas) put("tools", JsonArray(module.tools.map { tool -> buildJsonObject {
                put("id", tool.id)
                put("schemaStatus", if (tool.schema == null) "unknown" else "known")
                tool.schema?.let { put("schema", it) }
            } }))
        } }))
    }

    private suspend fun <T> safely(block: suspend () -> T?): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Do not echo exceptions: callbacks can contain credentials, URLs or private config values.
        null
    }

    companion object {
        private val NATIVE_MODULES = listOf(
            NativeModule("conversation_context", setOf("history_search", "history_read", "compress")),
            NativeModule("workspace", setOf("read", "write", "edit", "download", "build_script")),
            NativeModule("execution", setOf("base", "process")),
            NativeModule("memory", setOf("memory")),
            NativeModule("planning", setOf("plan", "scratchpad")),
            NativeModule("agents", setOf("invoke_subagent", "invoke_dual_agent", "team")),
            NativeModule("configuration", setOf("app_config"), "app_config (module omitted)"),
            NativeModule("workflow_management", setOf("app_config"), "app_config module=workflow"),
            NativeModule("model_management", setOf("app_config"), "app_config module=model"),
            NativeModule("session_management", setOf("app_config"), "app_config module=session"),
            NativeModule("rules_management", setOf("app_config"), "app_config module=rules"),
            NativeModule("distro_management", setOf("app_config"), "app_config module=distro"),
            NativeModule("terminal_management", setOf("app_config"), "app_config module=terminal"),
            NativeModule("plugin_management", setOf("app_config"), "app_config module=plugin"),
            NativeModule("process_plugin_management", setOf("app_config"), "app_config module=process_plugin"),
            NativeModule("web_plugin_management", setOf("app_config"), "app_config module=web_plugin"),
            NativeModule("skill_management", setOf("app_config"), "app_config module=skill"),
            NativeModule("bot_management", setOf("team"), "team (separate tool)"),
            NativeModule("capabilities", setOf("use_capability", "host")),
            NativeModule("skills", setOf("load_skill", "load_rule")),
            NativeModule("interaction", setOf("ask_user", "render_surface")),
            NativeModule("web_search", setOf("search_web")),
        )
        private val UI_ONLY_MODULES = listOf(
            "git_management", "browser_management", "terminal_ui", "diagnostics_ui", "permission_management",
        )
    }
}
