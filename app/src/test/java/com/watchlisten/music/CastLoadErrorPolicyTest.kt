package com.watchlisten.music

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.watchlisten.music.playback.CastChunkedInputStream
import com.watchlisten.music.playback.CastHttpHead
import com.watchlisten.music.playback.CastLengthInputStream
import com.watchlisten.music.playback.CastLoadErrorPolicy
import com.watchlisten.music.playback.CastStreamException
import com.watchlisten.music.playback.CastStreamFailure
import com.watchlisten.music.playback.castHttpFailure
import com.watchlisten.music.playback.castRetryDelayMs
import com.watchlisten.music.playback.classifyCastFailure
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CastLoadErrorPolicyTest {
    @Test fun transientNetworkFailuresRemainRetryableWithoutExposingTheirCause() {
        val secret = "https://media.kugou.com/song?signature=private-token"
        listOf(SocketTimeoutException(secret), SocketException(secret), UnknownHostException(secret), IOException(secret)).forEach {
            val failure = classifyCastFailure(it)
            assertEquals(CastStreamFailure.NETWORK, failure.kind)
            assertTrue(failure.kind.retryable)
            assertNull(failure.cause)
            assertFalse(failure.toString().contains(secret))
            assertFalse(failure.toString().contains("private-token"))
        }
    }

    @Test fun partialBodyAndHeaderTruncationCanRetryTheCurrentRange() {
        val failures = listOf(
            assertThrows(CastStreamException::class.java) { CastLengthInputStream(input("abc"), 10).readBytes() },
            assertThrows(CastStreamException::class.java) { CastChunkedInputStream(input("10\r\nabc")).readBytes() },
            assertThrows(CastStreamException::class.java) { head("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n") },
            classifyCastFailure(EOFException("signed URL was here"))
        )
        failures.forEach {
            assertEquals(CastStreamFailure.TRUNCATED, it.kind)
            assertTrue(castRetryDelayMs(it, 1) >= 0)
        }
    }

    @Test fun corruptFramingIsDifferentFromAConnectionTruncatedInFlight() {
        val malformedHead = assertThrows(CastStreamException::class.java) { head("HTTP/1.1 malformed\r\n\r\n") }
        val malformedChunk = assertThrows(CastStreamException::class.java) { CastChunkedInputStream(input("xyz\r\nabc\r\n")).readBytes() }
        listOf(malformedHead, malformedChunk).forEach {
            assertEquals(CastStreamFailure.INVALID_RESPONSE, it.kind)
            assertEquals(C.TIME_UNSET, castRetryDelayMs(it, 1))
        }
    }

    @Test fun temporaryHttpStatusesRetryAndPermanentStatusesDoNot() {
        listOf(408, 429, 500, 502, 503, 504, 599).forEach {
            val failure = castHttpFailure(it)
            assertEquals(it, failure.httpStatus)
            assertEquals(CastStreamFailure.HTTP_TEMPORARY, failure.kind)
            assertTrue(failure.kind.retryable)
        }
        mapOf(400 to CastStreamFailure.HTTP_PERMANENT, 401 to CastStreamFailure.AUTHORIZATION,
            403 to CastStreamFailure.AUTHORIZATION, 404 to CastStreamFailure.UNAVAILABLE,
            410 to CastStreamFailure.UNAVAILABLE, 416 to CastStreamFailure.POSITION_OUT_OF_RANGE).forEach { (status, kind) ->
            val failure = castHttpFailure(status)
            assertEquals(kind, failure.kind)
            assertEquals(status, failure.httpStatus)
            assertEquals(C.TIME_UNSET, castRetryDelayMs(failure, 1))
        }
    }

    @Test fun failedTlsValidationIsPermanentAndDoesNotLeakThePeerName() {
        val failure = classifyCastFailure(SSLHandshakeException("certificate for secret-host.kugou.com"))
        assertEquals(CastStreamFailure.SECURE_CONNECTION, failure.kind)
        assertEquals(C.TIME_UNSET, castRetryDelayMs(failure, 1))
        assertFalse(failure.toString().contains("secret-host"))
        assertNull(failure.cause)
    }

    @Test fun tlsTransportTimeoutDoesNotBecomeAPermanentCertificateFailure() {
        val timeout = SSLHandshakeException("signed URL").apply { initCause(SocketTimeoutException("secret")) }
        val failure = classifyCastFailure(timeout)
        assertEquals(CastStreamFailure.NETWORK, failure.kind)
        assertTrue(failure.kind.retryable)
        assertNull(failure.cause)
        val untrusted = SSLHandshakeException("secret").apply { initCause(java.security.cert.CertificateException("secret")) }
        assertEquals(CastStreamFailure.SECURE_CONNECTION, classifyCastFailure(untrusted).kind)
    }

    @Test fun interruptedCancellationIsNotMistakenForAReadTimeout() {
        assertEquals(CastStreamFailure.NETWORK, classifyCastFailure(InterruptedIOException()).kind)
        try {
            Thread.currentThread().interrupt()
            val failure = classifyCastFailure(InterruptedIOException("private URL"))
            assertEquals(CastStreamFailure.CANCELLED, failure.kind)
            assertEquals(C.TIME_UNSET, castRetryDelayMs(failure, 1))
        } finally { Thread.interrupted() }
    }

    @Test fun retriesHaveFiniteCountAndCappedExponentialDelays() {
        val failure = CastStreamException(CastStreamFailure.NETWORK)
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 4_000L, 4_000L), (1..6).map { castRetryDelayMs(failure, it) })
        listOf(-1, 0, 7, Int.MAX_VALUE).forEach { assertEquals(C.TIME_UNSET, castRetryDelayMs(failure, it)) }
    }

    @Test fun stopInvalidationAndPermanentFailuresNeverEnterTheRetryLoop() {
        CastStreamFailure.entries.filterNot { it.retryable }.forEach { kind ->
            for (count in 1..6) assertEquals(C.TIME_UNSET, castRetryDelayMs(CastStreamException(kind), count))
        }
        val wrapped = IOException("Media3 wrapper", CastStreamException(CastStreamFailure.CANCELLED))
        assertEquals(C.TIME_UNSET, castRetryDelayMs(wrapped, 1))
    }

    @Test fun castFactoryGetsSixRetriesWhileDefaultPolicyRetainsItsOwnMinimum() {
        assertEquals(6, CastLoadErrorPolicy().getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        assertEquals(3, DefaultLoadErrorHandlingPolicy().getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }

    @Test fun rangeAtTheExactDocumentEndIsALegalZeroLengthResponse() {
        assertEquals(0L, head("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */100\r\nContent-Length: 37\r\n\r\n").audioLength(100))
        assertEquals(0L, head("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */0\r\n\r\n").audioLength(0))
    }

    @Test fun otherUnsatisfiedRangesArePermanentRatherThanEndOfInput() {
        val response = head("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */100\r\n\r\n")
        listOf(0L, 99L, 101L, Long.MAX_VALUE).forEach { position ->
            val failure = assertThrows(CastStreamException::class.java) { response.audioLength(position) }
            assertEquals(CastStreamFailure.POSITION_OUT_OF_RANGE, failure.kind)
            assertFalse(failure.kind.retryable)
        }
    }

    @Test fun endOfInputRequiresStrictUnambiguousContentRange() {
        listOf("", "Content-Range: bytes */*\r\n", "Content-Range: bytes */-1\r\n",
            "Content-Range: bytes */9223372036854775808\r\n", "Content-Range: items */100\r\n",
            "Content-Range: bytes */100\r\nContent-Range: bytes */100\r\n").forEach { headers ->
            val failure = assertThrows(CastStreamException::class.java) { head("HTTP/1.1 416 Range Not Satisfiable\r\n$headers\r\n").audioLength(100) }
            assertFalse(failure.kind.retryable)
        }
    }

    private fun input(value: String) = ByteArrayInputStream(value.toByteArray(Charsets.ISO_8859_1))
    private fun head(value: String) = CastHttpHead.read(input(value))
}
