package top.wkbin.taixu.harness.admin.model

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import top.wkbin.taixu.core.database.*

class ModelAdminServiceTest {
    private class Fake : ModelManagementRepository {
        var version = 0
        var global = "a"
        val selection = mutableMapOf<String, String>()
        var settings = ModelSettings(0.5f, 100, null, null, null)
        var fail = false
        override suspend fun snapshot(sourceSession: String): ModelManagementSnapshot {
            if (fail) error("apiKey=PRIVATE baseUrl=https://PRIVATE header=PRIVATE secretRef=PRIVATE")
            return ModelManagementSnapshot(listOf("a", "b").map {
                ManagedModel(it, it == global, settings)
            }, selection[sourceSession], null, version.toString())
        }
        override suspend fun compareAndSet(sourceSession: String, expectedDigest: String, mutation: ModelMutation): Boolean {
            if (expectedDigest != version.toString()) return false
            when (mutation) {
                is ModelMutation.Settings -> settings = mutation.value
                is ModelMutation.GlobalDefault -> global = mutation.modelId
                is ModelMutation.SessionSelection -> selection[sourceSession] = mutation.modelId
            }
            version++
            return true
        }
    }
    private class Authority : ModelAdminAuthority {
        var plan = false
        var controlled = true
        override suspend fun isControlledUserSession(actor: ModelAdminActor) = controlled
        override suspend fun isPlan(actor: ModelAdminActor) = plan
    }
    private val actor = ModelAdminActor("user", "native-op", "apply-call")
    private fun proposal(r: ModelAdminResult) = (r as ModelAdminResult.Proposal).value.proposalId

    @Test fun concurrentEditRejectsAndConsumesApproval() = runBlocking {
        val fake = Fake(); val service = ModelAdminService(fake, Authority(), audit = { _, _, _ -> })
        val id = proposal(service.propose(actor, ModelAdminCommand.SetGlobalDefault("b")))
        assertTrue(service.prepare(actor, id) is ModelAdminResult.ApprovalRequired)
        fake.version++
        assertEquals(ModelAdminResult.Rejected("MODEL_SNAPSHOT_CHANGED"), service.applyNative(actor, id))
        assertTrue(service.applyNative(actor, id) is ModelAdminResult.Rejected)
        assertEquals("a", fake.global)
    }
    @Test fun sessionSelectionNeverChangesGlobalAndNeedsNativeApproval() = runBlocking {
        val fake = Fake(); val service = ModelAdminService(fake, Authority(), audit = { _, _, _ -> })
        val id = proposal(service.propose(actor, ModelAdminCommand.SelectForCurrentSession("b")))
        assertTrue(service.applyNative(actor, id) is ModelAdminResult.Rejected)
        service.prepare(actor, id)
        assertTrue(service.applyNative(actor.copy(callId = "foreign-call"), id) is ModelAdminResult.Rejected)
        assertTrue(service.applyNative(actor.copy(sessionId = "other"), id) is ModelAdminResult.Rejected)
        assertEquals(ModelAdminResult.Applied, service.applyNative(actor, id))
        assertEquals("b", fake.selection["user"])
        assertEquals("a", fake.global)
        assertNull(fake.selection["other"])
    }
    @Test fun planActorAndExpiryAreFailClosed() = runBlocking {
        val auth = Authority(); val fake = Fake(); var now = 0L
        val service = ModelAdminService(fake, auth, { now }, 10, audit = { _, _, _ -> })
        auth.plan = true
        assertTrue(service.list(actor) is ModelAdminResult.Catalog)
        assertTrue(service.propose(actor, ModelAdminCommand.SetGlobalDefault("b")) is ModelAdminResult.Rejected)
        auth.plan = false
        val id = proposal(service.propose(actor, ModelAdminCommand.SetGlobalDefault("b")))
        service.prepare(actor, id); now = 11
        assertTrue(service.applyNative(actor, id) is ModelAdminResult.Rejected)
        auth.controlled = false
        assertTrue(service.list(actor) is ModelAdminResult.Rejected)
        assertEquals("a", fake.global)
    }
    @Test fun unknownCredentialFieldsAndRawFailuresNeverLeak() = runBlocking {
        val fake = Fake(); val backend = ModelAdminBackend(ModelAdminService(fake, Authority(), audit = { _, _, _ -> }))
        for (field in listOf("apiKey", "baseUrl", "provider", "customHeaders", "secretRef", "sessionId")) {
            val args = buildJsonObject {
                put("module", "model"); put("action", "propose"); put("operation", "patch"); put("modelId", "a")
                put("patch", buildJsonObject { put(field, "PRIVATE") })
            }
            val output = executeModelAdmin(backend, actor, args).toSafeJson().toString()
            assertTrue(output.contains("rejected")); assertFalse(output.contains("PRIVATE"))
        }
        fake.fail = true
        val output = backend.service.list(actor).toSafeJson().toString()
        assertFalse(output.contains("PRIVATE")); assertFalse(output.contains("apiKey"))
        fake.fail = false
        val catalog = backend.service.list(actor).toSafeJson().toString()
        for (field in listOf("apiKey", "baseUrl", "header", "secretRef", "digest", "provider")) assertFalse(catalog.contains(field))
    }
    @Test fun patchKeepsAbsentAndCanClearExplicitNull() = runBlocking {
        val fake = Fake(); val service = ModelAdminService(fake, Authority(), audit = { _, _, _ -> })
        val command = ModelAdminCommand.Patch("a", ModelProfilePatch(temperature = FieldEdit.Set(null)))
        val id = proposal(service.propose(actor, command)); service.prepare(actor, id)
        assertEquals(ModelAdminResult.Applied, service.applyNative(actor, id))
        assertNull(fake.settings.temperature); assertEquals(100, fake.settings.maxTokens)
        assertTrue(service.propose(actor, ModelAdminCommand.Patch("a",
            ModelProfilePatch(topP = FieldEdit.Set(Float.NaN)))) is ModelAdminResult.Rejected)
    }
}
