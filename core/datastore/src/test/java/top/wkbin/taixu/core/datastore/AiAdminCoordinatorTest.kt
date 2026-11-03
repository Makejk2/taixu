package top.wkbin.taixu.core.datastore

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AiAdminCoordinatorTest {
    @Test fun closeBeforeQueuedWriterPreventsStartingNewMutation() = runBlocking {
        var enabled = true
        var revision = 1L
        var writes = 0
        val waitingWriter = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val writer = launch {
            waitingWriter.complete(Unit)
            releaseWriter.await() // old approval reached outer executor but not management boundary
            AiAdminCoordinator.serialized { if (enabled && revision == 1L) writes++ }
        }
        waitingWriter.await()
        AiAdminCoordinator.serialized { enabled = false; revision++ }
        releaseWriter.complete(Unit)
        writer.join()
        assertEquals(0, writes)
        AiAdminCoordinator.serialized { enabled = true; revision++ }
        AiAdminCoordinator.serialized { if (enabled && revision == 1L) writes++ }
        assertEquals(0, writes)
    }
    @Test fun closingWaitsForAlreadyStartedManagementAndDoesNotRaceIt() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var closed = false
        val writer = launch { AiAdminCoordinator.serialized { started.complete(Unit); finish.await(); assertFalse(closed) } }
        started.await()
        val closing = launch { AiAdminCoordinator.serialized { closed = true } }
        yield()
        assertFalse(closed)
        finish.complete(Unit)
        writer.join(); closing.join()
        assertTrue(closed)
    }
}
