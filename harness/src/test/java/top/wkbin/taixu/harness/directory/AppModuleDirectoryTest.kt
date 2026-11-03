package top.wkbin.taixu.harness.directory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.harness.ApiFunctionDefinition
import top.wkbin.taixu.harness.ApiToolDefinition
import top.wkbin.taixu.harness.ProviderClient
import top.wkbin.taixu.harness.plugins.HarnessPlugin
import top.wkbin.taixu.harness.plugins.HarnessPluginRegistry
import top.wkbin.taixu.harness.plugins.PluginTool
import top.wkbin.taixu.plugin.api.PluginDescriptor
import top.wkbin.taixu.plugin.api.PluginExtension
import top.wkbin.taixu.plugin.api.PluginExtensionPoint

class AppModuleDirectoryTest {
    private val schema = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(emptyMap()))
    }
    private fun definition(id: String) = ApiToolDefinition(function = ApiFunctionDefinition(id, "not directory output", schema))
    private fun JsonObject.module(id: String): JsonObject = this["modules"]!!.jsonArray
        .map { it.jsonObject }.single { it["id"]!!.jsonPrimitive.content == id }
    private fun JsonObject.ids(): List<String> = this["toolIds"]!!.jsonArray.map { it.jsonPrimitive.content }
    private fun JsonObject.field(key: String) = this[key]!!.jsonPrimitive.content
    private fun args(action: String, module: String? = null) = buildJsonObject {
        put("action", action)
        module?.let { put("module", it) }
    }

    @Test fun missingBindingsFailClosed() = runBlocking {
        val result = AppModuleDirectory(nativeDefinitions = { listOf(definition("read")) }).modules()
        listOf("native", "host", "plugins", "mcp", "model_management").forEach {
            assertEquals("unsupported", result.module(it).field("status"))
            assertTrue(result.module(it).ids().isEmpty())
        }
        assertEquals("true", result.field("readOnly"))
    }

    @Test fun nativeVisibilityIsIntersectionWithRealDefinitionsNotEnumOrCallbackGuesses() = runBlocking {
        val directory = AppModuleDirectory(visibleNativeToolNames = { setOf("read", "not_a_real_tool") })
        val result = directory.inspectModule("native").module("native")
        assertEquals(listOf("read"), result.ids())
        assertEquals(ProviderClient.TOOLS.single { it.function.name == "read" }.function.parameters,
            result["tools"]!!.jsonArray.single().jsonObject["schema"])
        assertFalse(result.toString().contains("not_a_real_tool"))
    }

    @Test fun modulesIsCompactAndOnlyInspectIncludesSchema() = runBlocking {
        val directory = AppModuleDirectory(visibleNativeToolNames = { setOf("read") },
            nativeDefinitions = { listOf(definition("read")) })
        assertFalse(directory.modules().module("workspace").containsKey("tools"))
        assertEquals("known", directory.inspectModule("workspace").module("workspace")["tools"]!!
            .jsonArray.single().jsonObject.field("schemaStatus"))
        assertEquals("unsupported", directory.modules().module("model_management").field("status"))
    }

    @Test fun currentVisibilityIsReadAgainWithoutCaching() = runBlocking {
        var names = setOf("read")
        var reads = 0
        val directory = AppModuleDirectory(visibleNativeToolNames = { reads++; names },
            nativeDefinitions = { listOf(definition("read")) })
        assertEquals(listOf("read"), directory.modules().module("native").ids())
        names = emptySet()
        assertTrue(directory.modules().module("native").ids().isEmpty())
        assertEquals(2, reads)
    }

    @Test fun hostNeedsBothBindingAndVisibleCapabilityProxy() = runBlocking {
        val bound = AppModuleDirectory(visibleNativeToolNames = { setOf("use_capability") },
            hostBackendBound = { true }, nativeDefinitions = { listOf(definition("use_capability")) })
        val host = bound.inspectModule("host").module("host")
        assertEquals(HostCapabilityDirectory.DEFERRED_ACTIONS.filterNot { HostCapabilityDirectory.isHidden(it.name) }
            .map { it.name }, host.ids())
        assertTrue(host["tools"]!!.jsonArray.all {
            val tool = it.jsonObject
            tool["schema"] == HostCapabilityDirectory.schemaFor(tool.field("id"))
        })
        val notVisible = AppModuleDirectory(hostBackendBound = { true }, nativeDefinitions = { emptyList() })
        assertEquals("unsupported", notVisible.modules().module("host").field("status"))
        val notBound = AppModuleDirectory(visibleNativeToolNames = { setOf("use_capability") },
            nativeDefinitions = { listOf(definition("use_capability")) })
        assertEquals("unsupported", notBound.modules().module("host").field("status"))
    }

    @Test fun registryToolIdsAreRealAndDirectoryNeverExecutesPlugin() = runBlocking {
        var executions = 0
        val descriptor = PluginDescriptor("test.safe", "1.0.0", extensions = listOf(PluginExtension("ping", PluginExtensionPoint.TOOL)))
        val registry = HarnessPluginRegistry(listOf(HarnessPlugin(descriptor, tools = listOf(
            PluginTool("ping", "secret description is not returned", schema) {
                executions++
                error("directory must not call tools")
            },
        ))))
        val directory = AppModuleDirectory(visibleNativeToolNames = { setOf("use_capability") },
            registeredPluginsSnapshot = { registry.descriptors.map {
                AppModuleDirectory.RegisteredPlugin.fromRegisteredDescriptor(it,
                    registry.registeredToolSchemas(it.id))
            } },
            nativeDefinitions = { listOf(definition("use_capability")) })
        directory.modules()
        val plugin = directory.inspectModule("plugin:test.safe").module("plugin:test.safe")
        assertEquals(listOf("ping"), plugin.ids())
        assertEquals("plugin.test.safe", plugin.field("server"))
        assertEquals("unknown", plugin["tools"]!!.jsonArray.single().jsonObject.field("schemaStatus"))
        assertEquals(0, executions)
        assertFalse(plugin.toString().contains("secret description"))
    }

    @Test fun onlyToolExtensionsAndVerifiedSchemasAreListed() = runBlocking {
        val descriptor = PluginDescriptor("test.safe", "1.0.0", extensions = listOf(
            PluginExtension("real", PluginExtensionPoint.TOOL), PluginExtension("page", PluginExtensionPoint.PAGE),
            PluginExtension("context", PluginExtensionPoint.CONTEXT), PluginExtension("provider", PluginExtensionPoint.PROVIDER)))
        val registered = AppModuleDirectory.RegisteredPlugin.fromRegisteredDescriptor(descriptor,
            mapOf("real" to schema, "guessed" to schema))
        val directory = AppModuleDirectory(visibleNativeToolNames = { setOf("use_capability") },
            registeredPluginsSnapshot = { listOf(registered) }, nativeDefinitions = { listOf(definition("use_capability")) })
        val plugin = directory.inspectModule("plugin:test.safe").module("plugin:test.safe")
        assertEquals(listOf("real"), plugin.ids())
        assertEquals(schema, plugin["tools"]!!.jsonArray.single().jsonObject["schema"])
        assertFalse(plugin.toString().contains("guessed"))
        assertFalse(plugin.toString().contains("provider"))
    }

    @Test fun mcpListsOnlySuppliedEnabledServiceIdsWithNoDiscoveryOrCachedTools() = runBlocking {
        var reads = 0
        val directory = AppModuleDirectory(visibleNativeToolNames = { setOf("use_capability") },
            enabledMcpServicesSnapshot = { reads++; listOf(AppModuleDirectory.EnabledMcpService("enabled")) },
            nativeDefinitions = { listOf(definition("use_capability")) })
        val service = directory.inspectModule("mcp:enabled").module("mcp:enabled")
        assertEquals(1, reads)
        assertEquals("enabled_uninspected", service.field("status"))
        assertTrue(service.ids().isEmpty())
        assertEquals(JsonArray(emptyList()), service["tools"])
        assertFalse(service.containsKey("url"))
        assertFalse(service.containsKey("cachedToolCount"))
    }

    @Test fun failingCallbacksDegradeIndependentlyAndDoNotLeakErrorText() = runBlocking {
        val directory = AppModuleDirectory(visibleNativeToolNames = { setOf("read") },
            registeredPluginsSnapshot = { error("credential-do-not-emit") },
            enabledMcpServicesSnapshot = { error("credential-do-not-emit") },
            nativeDefinitions = { listOf(definition("read")) })
        val output = directory.modules()
        assertEquals("available", output.module("native").field("status"))
        assertEquals("unsupported", output.module("plugins").field("status"))
        assertEquals("unsupported", output.module("mcp").field("status"))
        assertFalse(output.toString().contains("credential-do-not-emit"))
    }

    @Test fun unknownModuleAndMutationActionsAreRejected() = runBlocking {
        val backend = AppModulesToolBackend(AppModuleDirectory(nativeDefinitions = { emptyList() }))
        val unknown = backend.execute(args("inspect_module", "missing"))
        assertFalse(unknown.first)
        assertEquals("unknown_module", Json.parseToJsonElement(unknown.second).jsonObject.field("error"))
        listOf("call", "apply", "connect", "discover", "propose", "list").forEach {
            assertFalse(backend.handles(it))
            assertFalse(backend.execute(args(it)).first)
        }
        assertFalse(backend.execute(args("inspect_module")).first)
        assertFalse(backend.execute(buildJsonObject { put("action", "modules"); put("userConfirmed", true) }).first)
        assertFalse(backend.execute(buildJsonObject { put("action", "inspect_module"); put("module", true) }).first)
        assertTrue(backend.execute(args("modules")).first)
    }

    @Test fun rejectsUnsupportedArgumentsBeforeReadingSnapshots() = runBlocking {
        var reads = 0
        val directory = AppModuleDirectory(visibleNativeToolNames = { reads++; emptySet() }, nativeDefinitions = { emptyList() })
        val result = AppModulesToolBackend(directory).execute(buildJsonObject {
            put("action", "modules"); put("arguments", JsonObject(emptyMap()))
        })
        assertFalse(result.first)
        assertEquals(0, reads)
    }

    @Test fun cancellationIsNotConvertedIntoMissingBinding() = runBlocking {
        val directory = AppModuleDirectory(visibleNativeToolNames = { throw CancellationException("cancelled") })
        try {
            directory.modules()
            throw AssertionError("cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected; no generic failure response.
        }
    }
}
