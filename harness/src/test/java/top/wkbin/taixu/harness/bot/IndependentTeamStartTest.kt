package top.wkbin.taixu.harness.bot

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.wkbin.taixu.core.model.bot.*

class IndependentTeamStartTest {
    private val published = TeamPublishedVersion("team", 1, "Team", listOf("admin", "one", "two"), BotTeamDefinition("admin"), 1)
    private fun request(members: List<IndependentTeamStart.Member> = listOf(
        IndependentTeamStart.Member("one", "Implement", listOf("src/one.kt")),
        IndependentTeamStart.Member("two", "Review"),
    )) = IndependentTeamStart.Request("group", "trusted-user-session", "team-start:approved-proposal", published, members)
    private class Port : IndependentTeamStart.Port {
        var validations = 0
        val admitted = mutableListOf<String>()
        val requests = mutableListOf<IndependentTeamStart.Request>()
        var cancelled: String? = null
        var failAt: String? = null
        var rejectValidation = false
        var failCancel = false
        var duplicate = false
        var onAdmitted: (String) -> Unit = {}
        override suspend fun validate(sourceSessionId: String, published: TeamPublishedVersion, members: List<IndependentTeamStart.Member>) {
            validations++
            check(!rejectValidation) { "invalid source/path/budget" }
            assertEquals("trusted-user-session", sourceSessionId)
        }
        override suspend fun admit(request: IndependentTeamStart.Request, member: IndependentTeamStart.Member): IndependentTeamStart.Receipt {
            requests += request
            if (member.botId == failAt) error("lost acknowledgement")
            admitted += member.botId
            onAdmitted(member.botId)
            val id = if (duplicate) "same" else member.botId
            return IndependentTeamStart.Receipt(member.botId, "assignment-$id", "session-$id")
        }
        override suspend fun cancel(groupId: String) {
            cancelled = groupId
            check(!failCancel) { "cancel unavailable" }
        }
    }
    private suspend fun failure(block: suspend () -> Unit): Exception = try {
        block(); throw AssertionError("expected failure")
    } catch (e: IllegalArgumentException) { e } catch (e: IllegalStateException) { e }

    @Test fun nativeReceiptsHaveIndependentIdsAndPassTrustedSourceAndExactPaths() = runBlocking {
        val port = Port(); val req = request()
        val result = IndependentTeamStart(port).start(req)
        assertEquals(listOf("one", "two"), port.admitted)
        assertEquals(2, result.receipts.map { it.assignmentId }.distinct().size)
        assertEquals(2, result.receipts.map { it.sessionId }.distinct().size)
        assertEquals(listOf(req, req), port.requests)
        assertTrue(req.members[1].writePaths.isEmpty())
        assertNull(port.cancelled)
    }

    @Test fun admittingSecondMemberDoesNotAwaitFirstMembersExecution() = runBlocking {
        coroutineScope {
            val port = Port()
            val firstRunning = CompletableDeferred<Unit>()
            val secondRunning = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val jobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()
            port.onAdmitted = { bot -> jobs += async {
                if (bot == "one") firstRunning.complete(Unit) else secondRunning.complete(Unit)
                release.await()
            } }
            val result = IndependentTeamStart(port).start(request())
            firstRunning.await(); secondRunning.await()
            assertEquals(2, result.receipts.size)
            assertTrue(jobs.all { it.isActive })
            release.complete(Unit); jobs.awaitAll(); Unit
        }
    }

    @Test fun validatesAllBeforeAnyAdmission() = runBlocking {
        val port = Port(); port.rejectValidation = true
        failure { IndependentTeamStart(port).start(request()) }
        assertTrue(port.admitted.isEmpty()); assertNull(port.cancelled)
        val clean = Port()
        failure { IndependentTeamStart(clean).start(request(listOf(
            IndependentTeamStart.Member("one", "valid"), IndependentTeamStart.Member("missing", "invalid")
        ))) }
        assertTrue(clean.admitted.isEmpty())
    }

    @Test fun governedExecutorNeverBypassesReviewEvenWithReadOnlyPaths() = runBlocking {
        val port = Port()
        val governed = published.copy(definition = BotTeamDefinition("admin", governance = GroupGovernance(listOf(
            GroupReviewRule("rule", "Review", "admin", "one", "two")
        ))))
        val error = failure { IndependentTeamStart(port).start(request().copy(published = governed)) }
        assertTrue(error.message!!.contains("审议"))
        assertEquals(0, port.validations); assertTrue(port.admitted.isEmpty())
    }

    @Test fun partialAdmissionReportsIdsCancelsGroupAndDoesNotAdmitRemainder() = runBlocking {
        val port = Port(); port.failAt = "two"
        val error = failure { IndependentTeamStart(port).start(request(request().members + IndependentTeamStart.Member("admin", "third"))) }
        assertEquals(listOf("one"), port.admitted)
        assertEquals("group", port.cancelled)
        assertEquals(2, port.requests.size)
        assertTrue(error.message!!.contains("assignment-one"))
        assertTrue(error.message!!.contains("session-one"))
        assertTrue(error.message!!.contains("禁止自动重放"))
    }

    @Test fun cleanupFailureAndMalformedReceiptNeverReportSuccess() = runBlocking {
        val port = Port(); port.failAt = "two"; port.failCancel = true
        val error = failure { IndependentTeamStart(port).start(request()) }
        assertTrue(error.message!!.contains("停止未确认"))
        val duplicate = Port(); duplicate.duplicate = true
        failure { IndependentTeamStart(duplicate).start(request()) }
        assertEquals("group", duplicate.cancelled)
    }

    @Test fun cancellationStillStopsAcceptedWorkAndCarriesReceipt() = runBlocking {
        val port = Port(); port.onAdmitted = { if (it == "two") throw CancellationException("cancelled") }
        try {
            IndependentTeamStart(port).start(request()); fail("expected cancellation")
        } catch (e: CancellationException) {
            assertTrue(e.message!!.contains("assignment-one"))
            assertEquals("group", port.cancelled)
        }
    }
}
