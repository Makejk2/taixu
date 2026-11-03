package top.wkbin.taixu.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.wkbin.taixu.core.model.workflow.*

/** Real Room tests, not the admin fake port. Requires normal database Robolectric test runtime. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkflowDefinitionCasTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: WorkflowRepository
    private val definition = WorkflowDefinition("cas-custom", "Original", createdAt = 1, updatedAt = 1,
        nodes = listOf(WorkflowNode("start", WorkflowNodeType.TRIGGER, "Start")))

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RoomWorkflowRepository(database.workflowDao(),
            Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false })
    }
    @After fun tearDown() = database.close()

    @Test fun absentCreateNeverOverwritesNativeCreate(): Unit = runBlocking {
        assertTrue(repository.upsertCustomIfUnchanged(definition, WorkflowDefinitionCas.ABSENT))
        assertFalse(repository.upsertCustomIfUnchanged(definition.copy(name = "Stale"), WorkflowDefinitionCas.ABSENT))
        assertEquals(definition, repository.findById(definition.id))
    }

    @Test fun nativeEditsRejectBothSaveAndDeleteWithOldApprovalHash(): Unit = runBlocking {
        repository.upsert(definition)
        val approvedHash = WorkflowDefinitionCas.hash(requireNotNull(repository.findById(definition.id)))
        val nativeEdit = definition.copy(description = "Native editor", updatedAt = 2)
        repository.upsert(nativeEdit)
        assertFalse(repository.upsertCustomIfUnchanged(definition.copy(name = "Approved"), approvedHash))
        assertFalse(repository.deleteCustomIfUnchanged(definition.id, approvedHash))
        assertEquals(nativeEdit, repository.findById(definition.id))
    }

    @Test fun nativeDeleteCannotBeResurrectedWithAnExistingApprovalHash(): Unit = runBlocking {
        repository.upsert(definition)
        assertTrue(repository.deleteCustom(definition.id))
        assertFalse(repository.upsertCustomIfUnchanged(definition, WorkflowDefinitionCas.hash(definition)))
        assertFalse(repository.deleteCustomIfUnchanged(definition.id, WorkflowDefinitionCas.ABSENT))
        assertNull(repository.findById(definition.id))
    }

    @Test fun competingApprovalsHaveExactlyOneWinnerInRoom(): Unit = runBlocking {
        repository.upsert(definition)
        val hash = WorkflowDefinitionCas.hash(definition)
        val release = CompletableDeferred<Unit>()
        val attempts = (1..2).map { index ->
            async(Dispatchers.Default) {
                release.await()
                repository.upsertCustomIfUnchanged(definition.copy(name = "Writer $index"), hash)
            }
        }
        release.complete(Unit)
        assertEquals(1, attempts.awaitAll().count { it })
        assertTrue(repository.findById(definition.id)?.name in setOf("Writer 1", "Writer 2"))
    }

    @Test fun savePreservesHistoryAndDeleteKeepsNativeCascadeSemantics(): Unit = runBlocking {
        repository.saveExecution(WorkflowRuntimeState.initial("cas-log", definition))
        val approvedHash = WorkflowDefinitionCas.hash(requireNotNull(repository.findById(definition.id)))
        val next = definition.copy(name = "Updated")
        assertTrue(repository.upsertCustomIfUnchanged(next, approvedHash))
        assertNotNull(repository.findExecutionById("cas-log"))
        assertTrue(repository.deleteCustomIfUnchanged(definition.id, WorkflowDefinitionCas.hash(next)))
        assertNull(repository.findExecutionById("cas-log"))
    }

    @Test fun persistedSerializationOptionsDoNotChangeApprovalHash(): Unit = runBlocking {
        repository.upsert(definition)
        val stored = requireNotNull(database.workflowDao().findById(definition.id))
        // Repository encodes defaults; approval hash uses Json.Default and hashes the decoded model.
        assertEquals(WorkflowDefinitionCas.hash(definition), WorkflowDefinitionCas.hash(
            requireNotNull(repository.findById(definition.id))))
        assertTrue(WorkflowDefinitionCas.matches(stored, WorkflowDefinitionCas.hash(definition)))
        database.workflowDao().upsert(stored.copy(jsonContent = "not valid JSON"))
        assertFalse(repository.upsertCustomIfUnchanged(definition, WorkflowDefinitionCas.ABSENT))
        assertFalse(repository.upsertCustomIfUnchanged(definition, WorkflowDefinitionCas.hash(definition)))
        assertEquals("not valid JSON", database.workflowDao().findById(definition.id)?.jsonContent)
    }

    @Test fun saveRejectsBuiltinsAndDeleteDoesNotRemoveThem(): Unit = runBlocking {
        val builtin = BuiltinWorkflows.all.first()
        // Check reserved ID even before built-ins are seeded.
        rejects { repository.upsertCustomIfUnchanged(builtin.copy(isBuiltin = false), WorkflowDefinitionCas.ABSENT) }
        rejects { repository.upsertCustomIfUnchanged(definition.copy(isBuiltin = true), WorkflowDefinitionCas.ABSENT) }
        repository.ensureBuiltins()
        val seeded = requireNotNull(repository.findById(builtin.id)) // repository normalizes timestamps
        val seededHash = WorkflowDefinitionCas.hash(seeded)
        rejects { repository.upsertCustomIfUnchanged(seeded.copy(isBuiltin = false), seededHash) }
        assertFalse(repository.deleteCustomIfUnchanged(builtin.id, seededHash))
        assertEquals(seeded, repository.findById(builtin.id))
    }

    private suspend fun rejects(block: suspend () -> Unit) {
        try { block(); fail("Expected builtin protection") } catch (_: IllegalArgumentException) { }
    }
}
