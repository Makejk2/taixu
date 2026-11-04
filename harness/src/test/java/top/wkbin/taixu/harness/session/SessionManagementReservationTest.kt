package top.wkbin.taixu.harness.session

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SessionManagementReservationTest {
    @Test fun reservationRejectsConcurrentEditAndAllowsNestedCleanup() = runBlocking {
        val fence = SessionManagementReservation()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val job = launch {
            fence.reserve("target", { it() }) {
                fence.checkEdit("target")
                fence.reserve("target", { error("must inherit") }) { fence.checkEdit("target") }
                entered.complete(Unit); finish.await()
            }
        }
        entered.await(); assertTrue(fence.blocked("target"))
        try { fence.checkEdit("target"); fail("edit admitted") } catch (_: IllegalStateException) { }
        finish.complete(Unit); job.join(); assertFalse(fence.blocked("target"))
    }
    @Test fun leaseDoesNotHoldAdmissionMutexDuringCleanup() = runBlocking {
        val fence = SessionManagementReservation()
        val mutex = kotlinx.coroutines.sync.Mutex()
        val admitted = CompletableDeferred<Unit>()
        val runner = launch {
            admitted.await()
            mutex.lock()
            try { assertTrue(fence.blocked("target")) } finally { mutex.unlock() }
        }
        fence.reserve("target", { acquire ->
            mutex.lock()
            try { acquire() } finally { mutex.unlock() }
        }) {
            admitted.complete(Unit)
            withTimeout(1000) { runner.join() }
        }
        assertFalse(fence.blocked("target"))
    }
    @Test fun nativeBranchEditAndManagementUseSameFence() = runBlocking {
        val fence = SessionManagementReservation()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val branch = launch { fence.reserve("target", { it() }) { entered.complete(Unit); finish.await() } }
        entered.await()
        try { fence.reserve("target", { it() }) { fail("management overtook branch") }; fail("no conflict") }
        catch (_: IllegalStateException) { }
        finish.complete(Unit); branch.join()
    }
    @Test fun cancellationReleasesFence() = runBlocking {
        val fence = SessionManagementReservation()
        val entered = CompletableDeferred<Unit>()
        val job = launch { fence.reserve("target", { it() }) { entered.complete(Unit); awaitCancellation() } }
        entered.await(); job.cancelAndJoin(); assertFalse(fence.blocked("target"))
    }
}
