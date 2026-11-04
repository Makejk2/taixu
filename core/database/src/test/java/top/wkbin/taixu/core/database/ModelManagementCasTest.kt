package top.wkbin.taixu.core.database

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Algorithm tests; transaction atomicity is supplied by generated Room wrapper, not this fake. */
class ModelManagementCasTest {
    private class Fake : ModelManagementCas {
        var rows = listOf(AiModelEntity("a", "private-name", "private-provider", "private-model",
            "https://private-endpoint", "private-ref", true, 1, customHeaders = "private-header"),
            AiModelEntity("b", "other", "other", "other", createdAt = 2))
        var session = HarnessSessionEntity("user", "user", 1, 1, null)
        override suspend fun managementModels() = rows
        override suspend fun managementSession(sessionId: String) = session.takeIf { it.id == sessionId }
        override suspend fun managementUpdateSettings(id: String, temperature: Float?, maxTokens: Int?, topP: Float?, reasoningMode: String?, reasoningEffort: String?): Int {
            rows = rows.map { if (it.id == id) it.copy(temperature = temperature, maxTokens = maxTokens,
                topP = topP, reasoningMode = reasoningMode, reasoningEffort = reasoningEffort) else it }
            return 1
        }
        override suspend fun managementActivate(id: String): Int {
            rows = rows.map { it.copy(isActive = it.id == id) }; return rows.size
        }
        override suspend fun managementSelectSession(sessionId: String, id: String, updatedAt: Long): Int {
            session = session.copy(modelId = id, modelVariant = null, updatedAt = updatedAt); return 1
        }
    }
    @Test fun hiddenNativeChangesInvalidateDigestAndPatchRetainsCredentials() = runBlocking {
        val db = Fake(); val before = db.modelManagementSnapshot("user")
        assertFalse(before.models.toString().contains("private"))
        db.rows = db.rows.map { it.copy(customHeaders = "changed-private") }
        val edit = ModelMutation.Settings("a", ModelSettings(1f, 99, null, null, null))
        assertFalse(db.modelManagementCompareAndSet("user", before.digest, edit))
        val current = db.modelManagementSnapshot("user")
        val privateRow = db.rows.first()
        assertTrue(db.modelManagementCompareAndSet("user", current.digest, edit))
        assertEquals(privateRow.customHeaders, db.rows.first().customHeaders)
        assertEquals(privateRow.secretRef, db.rows.first().secretRef)
        assertEquals(privateRow.baseUrl, db.rows.first().baseUrl)
    }
    @Test fun globalDefaultUniqueAndSessionSelectionIsSeparate() = runBlocking {
        val db = Fake()
        assertTrue(db.modelManagementCompareAndSet("user", db.modelManagementSnapshot("user").digest,
            ModelMutation.GlobalDefault("b")))
        assertEquals(listOf("b"), db.rows.filter { it.isActive }.map { it.id })
        assertTrue(db.modelManagementCompareAndSet("user", db.modelManagementSnapshot("user").digest,
            ModelMutation.SessionSelection("a")))
        assertEquals("a", db.session.modelId)
        assertEquals(listOf("b"), db.rows.filter { it.isActive }.map { it.id })
    }
}

// ROOM_CI_TESTS -- excluded only by the explicitly stubbed focused compiler runner.
/** Real generated Room transactions/SQL, pending CI; not executed by run-focused.sh. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class ModelManagementRoomTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: RoomModelManagementRepository
    private val original = AiModelEntity("a", "private-name", "private-provider", "private-model",
        "https://private-endpoint", "private-key-ref", true, 1, customHeaders = "private-header")
    @org.junit.Before fun setup() = runBlocking {
        db = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = RoomModelManagementRepository(db.aiModelDao())
        db.aiModelDao().upsert(original)
        db.aiModelDao().upsert(original.copy(id = "b", isActive = false))
        db.harnessSessionDao().upsert(HarnessSessionEntity("user", "user", 1, 1, "a", "PRIVATE_VARIANT"))
        db.harnessSessionDao().upsert(HarnessSessionEntity("other", "other", 1, 1, "a", "OTHER_VARIANT"))
    }
    @org.junit.After fun close() { db.close() }
    @Test fun sameSelectionPreservesVariantAndRealSwitchResetsOnlySource() = runBlocking {
        assertTrue(repo.compareAndSet("user", repo.snapshot("user").digest, ModelMutation.SessionSelection("a")))
        assertEquals("PRIVATE_VARIANT", db.aiModelDao().managementSession("user")!!.modelVariant)
        assertTrue(repo.compareAndSet("user", repo.snapshot("user").digest, ModelMutation.SessionSelection("b")))
        assertNull(db.aiModelDao().managementSession("user")!!.modelVariant)
        assertEquals("OTHER_VARIANT", db.aiModelDao().managementSession("other")!!.modelVariant)
        assertEquals("a", db.aiModelDao().managementModels().single { it.isActive }.id)
    }
    @Test fun variantAndHiddenNativeChangesInvalidateRealRoomCas() = runBlocking {
        val before = repo.snapshot("user")
        db.openHelper.writableDatabase.execSQL("UPDATE harness_sessions SET modelVariant='CHANGED_PRIVATE' WHERE id='user'")
        assertFalse(repo.compareAndSet("user", before.digest, ModelMutation.GlobalDefault("b")))
        val current = repo.snapshot("user")
        assertEquals("CHANGED_PRIVATE", current.sessionVariant)
        assertFalse(current.toString().contains("CHANGED_PRIVATE"))
        db.aiModelDao().upsert(original.copy(customHeaders = "CHANGED_PRIVATE_HEADER"))
        assertFalse(repo.compareAndSet("user", current.digest, ModelMutation.GlobalDefault("b")))
    }
    @Test fun patchPreservesSecretsAndGlobalDefaultLeavesSessionSelectionAlone() = runBlocking {
        assertTrue(repo.compareAndSet("user", repo.snapshot("user").digest,
            ModelMutation.Settings("a", ModelSettings(1f, 99, null, null, null))))
        val row = db.aiModelDao().managementModels().single { it.id == "a" }
        assertEquals(original.secretRef, row.secretRef)
        assertEquals(original.baseUrl, row.baseUrl)
        assertEquals(original.customHeaders, row.customHeaders)
        assertTrue(repo.compareAndSet("user", repo.snapshot("user").digest, ModelMutation.GlobalDefault("b")))
        assertEquals("a", db.aiModelDao().managementSession("user")!!.modelId)
        assertEquals("PRIVATE_VARIANT", db.aiModelDao().managementSession("user")!!.modelVariant)
        assertEquals(listOf("b"), db.aiModelDao().managementModels().filter { it.isActive }.map { it.id })
    }
    @Test fun nativeSqlFailureRollsBackGlobalActivation() = runBlocking {
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_model_activation BEFORE UPDATE ON harness_models
            WHEN NEW.id='b' BEGIN SELECT RAISE(ABORT, 'PRIVATE_DB_ERROR'); END""")
        val snapshot = repo.snapshot("user")
        assertTrue(runCatching { repo.compareAndSet("user", snapshot.digest, ModelMutation.GlobalDefault("b")) }.isFailure)
        assertEquals("a", db.aiModelDao().managementModels().single { it.isActive }.id)
    }
}
