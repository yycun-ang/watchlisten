package com.watchlisten.music

import com.watchlisten.music.data.AccountProtocol
import com.watchlisten.music.model.*
import com.watchlisten.music.playback.PlaybackRecovery
import org.junit.Assert.*
import org.junit.Test

class AccountProtocolTest {
    @Test fun rsaMatchesIndependentNodeRawRsaVector() {
        assertEquals("3C703100CEC173A0BFF33BAD5D28503E298270F8A2173C373B99C4F865CEEAD623D1E067C17308CD419B4CC9E9CD24BF5A090DB153E87D89132341D4A665719366A5331C425F659CC2AEFB138734003FB2AF943D19562A69D4E9FA5B4A037D7BD4CA68249CEA91363FDA5BC4373043DCEFD8DD6F2365EC799667E1A73C7EFE3B",
            AccountProtocol.encryptPayload("fixture"))
    }

    @Test fun rsaMeasuresUtf8BytesAndRejectsOversizedPayload() {
        val error = runCatching { AccountProtocol.encryptPayload("汉".repeat(43)) }.exceptionOrNull()
        assertEquals(MusicError.InvalidResponse, (error as? MusicFailure)?.error)
    }

    @Test fun androidSignatureIncludesTheExactPostedBodyAndSortedQuery() {
        assertEquals("fb0d45164eba4453fadfeb1ca28532ce",
            AccountProtocol.signature(linkedMapOf("z" to "2", "a" to "1"), "{\"test\":true}"))
        assertNotEquals(AccountProtocol.signature(mapOf("a" to "1"), "{}"),
            AccountProtocol.signature(mapOf("a" to "1"), "{ }"))
    }

    @Test fun expiryAcceptsSecondsMillisecondsAndBeijingDates() {
        assertEquals(1793433600000L, AccountProtocol.parseExpiry("1793433600"))
        assertEquals(1793433600000L, AccountProtocol.parseExpiry("1793433600000"))
        assertEquals(1793462399999L, AccountProtocol.parseExpiry("2026-10-31"))
        assertEquals(1793376000000L, AccountProtocol.parseExpiry("2026-10-31 00:00:00"))
        assertEquals(0, AccountProtocol.parseExpiry("unknown"))
        assertEquals(0, AccountProtocol.parseExpiry(""))
    }

    @Test fun cachedMembershipExpiresAtTheActualDeadline() {
        val membership = Membership("概念版 VIP", MembershipStatus.ACTIVE, expiresAtMs = 1000)
        assertEquals(MembershipStatus.ACTIVE, membership.effectiveStatus(999))
        assertEquals(MembershipStatus.INACTIVE, membership.effectiveStatus(1000))
        assertEquals(MembershipStatus.UNKNOWN, Membership().effectiveStatus(1000))
    }

    @Test fun withdrawingConsentNeverTriggersPlaybackRetriesOrSkipping() {
        assertEquals(PlaybackRecovery.Decision.GiveUp, PlaybackRecovery.decide(
            MusicError.ConsentRequired, refreshUsed = false, consecutiveSkips = 0, queueSize = 200, hasNext = true))
    }
}
