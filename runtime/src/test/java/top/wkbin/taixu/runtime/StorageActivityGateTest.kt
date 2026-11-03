package top.wkbin.taixu.runtime

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class StorageActivityGateTest {
    @Test fun explicitPinIsIdempotentAndProtectsOtherOwners() = runBlocking {
        val gate = StorageActivityGate()
        val first = gate.acquire()
        val second = gate.acquire()
        first.close(); first.close()
        assertTrue(runCatching { gate.cleanup { fail("second pin was lost") } }.isFailure)
        second.close(); second.close()
        gate.cleanup { assertTrue(runCatching { gate.acquire() }.isFailure) }
        assertTrue(runCatching { gate.acquireTarget("missing") { false } }.isFailure)
        gate.cleanup { }
    }

    @Test fun maintenanceReturnsResultAndRejectsNewCommandsUntilItFinishes() = runBlocking {
        val gate = StorageActivityGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val maintenance = async { gate.cleanup { entered.complete(Unit); release.await(); "done" } }
        entered.await()
        assertTrue(runCatching { gate.activity { fail("launch was admitted") } }.exceptionOrNull() is IllegalStateException)
        release.complete(Unit)
        assertEquals("done", maintenance.await())
        assertEquals("launched", gate.activity { "launched" })
    }
    @Test fun existingLaunchPreventsDestructiveMaintenanceFromStarting() = runBlocking {
        val gate = StorageActivityGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val launch = async { gate.activity { entered.complete(Unit); release.await() } }
        entered.await()
        assertTrue(runCatching { gate.cleanup { fail("cleanup started") } }.exceptionOrNull() is IllegalStateException)
        release.complete(Unit)
        launch.await()
        gate.cleanup { }
    }
    @Test fun failureOrCancellationReopensAdmissionAfterMaintenance() = runBlocking {
        val gate = StorageActivityGate()
        assertNotNull(runCatching { gate.cleanup { error("failed") } }.exceptionOrNull())
        gate.activity { }
        val entered = CompletableDeferred<Unit>()
        val maintenance = launch { gate.cleanup { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        maintenance.cancelAndJoin()
        gate.activity { }
    }
}
