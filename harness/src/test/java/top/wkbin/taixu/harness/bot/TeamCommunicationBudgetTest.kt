package top.wkbin.taixu.harness.bot

import org.junit.Assert.*
import org.junit.Test
import top.wkbin.taixu.core.model.bot.TeamCommunicationPolicy

class TeamCommunicationBudgetTest {
    @Test fun defaultsAreRenewableNotPermanentCaps() {
        val initial = TeamCommunicationPolicy()
        initial.validate()
        val renewed = initial.copy(groupLimit=10_000, memberLimit=1_000, maxHop=128)
        renewed.validate()
        assertTrue(renewed.groupLimit > initial.groupLimit)
        assertTrue(renewed.memberLimit > initial.memberLimit)
        assertTrue(renewed.maxHop > initial.maxHop)
    }
    @Test fun unlimitedCumulativeStillSupportsRateAndQueueBackpressure() {
        val policy = TeamCommunicationPolicy(groupLimit=0, memberLimit=0, unlimitedExplicit=true, rateLimit=5, queueLimit=2)
        policy.validate()
        assertEquals(5, policy.rateLimit)
        assertEquals(2, policy.queueLimit)
    }
    @Test fun zeroWithoutExplicitUserChoiceIsRejected() {
        listOf(TeamCommunicationPolicy(groupLimit=0), TeamCommunicationPolicy(memberLimit=0)).forEach {
            try { it.validate(); fail("unlimited must be explicit") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun invalidLimitsAreRejectedRatherThanSilentlyDisablingControls() {
        listOf(TeamCommunicationPolicy(groupLimit=-1), TeamCommunicationPolicy(memberLimit=-1),
            TeamCommunicationPolicy(maxHop=0), TeamCommunicationPolicy(rateWindowMs=0),
            TeamCommunicationPolicy(rateLimit=-1), TeamCommunicationPolicy(queueLimit=-1),
            TeamCommunicationPolicy(rateLimit=0), TeamCommunicationPolicy(queueLimit=0),
            TeamCommunicationPolicy(groupLimit=0,memberLimit=0,rateLimit=0,queueLimit=0,unlimitedExplicit=true)).forEach {
            try { it.validate(); fail("invalid policy") } catch (_: IllegalArgumentException) { }
        }
    }
}
