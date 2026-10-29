package com.watchlisten.music

import com.watchlisten.music.playback.CastChunkedInputStream
import com.watchlisten.music.playback.CastHttpHead
import com.watchlisten.music.playback.CastLengthInputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class CastHttpStreamTest {
    @Test fun interimHeadersDoNotReplaceTheFinalMediaResponse() {
        val head = head("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 103 Early Hints\r\nLink: ignored\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 8\r\n\r\n")
        assertEquals(200, head.status)
        assertEquals(8L, head.audioLength(0))
        assertFalse(head.headers.containsKey("link"))
    }

    @Test fun responseHeadersAreBoundedAndRequireValidHttpSyntax() {
        listOf(
            "HTTP/2 200 OK\r\n\r\n", "HTTP/1.1 200 OK\n\n", "HTTP/1.1 101 Switching Protocols\r\n\r\n",
            "HTTP/1.1 200 OK\r\n Folded: invalid\r\n\r\n", "HTTP/1.1 200 OK\r\nBad Header: invalid\r\n\r\n",
            "HTTP/1.1 200 OK\r\nX: " + "a".repeat(32 * 1024) + "\r\n\r\n"
        ).forEach { response -> assertThrows(IOException::class.java) { head(response) } }
    }

    @Test fun conflictingOrInvalidFramingIsRejected() {
        listOf(
            "Content-Length: 4\r\nContent-Length: 4\r\n", "Content-Length: -1\r\n",
            "Content-Length: 9223372036854775808\r\n", "Content-Length: 4, 4\r\n",
            "Content-Length: 4\r\nTransfer-Encoding: chunked\r\n", "Transfer-Encoding: gzip, chunked\r\n",
            "Content-Encoding: gzip\r\n", "Transfer-Encoding: chunked\r\nTransfer-Encoding: chunked\r\n"
        ).forEach { headers -> assertThrows(IOException::class.java) { head("HTTP/1.1 200 OK\r\n$headers\r\n").body(input("data")) } }
    }

    @Test fun validPartialResponseMatchesTheRequestedOffsetAndLength() {
        val response = head("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 10-13/100\r\nContent-Length: 4\r\n\r\n")
        assertEquals(4L, response.audioLength(10))
        assertEquals(4L, head("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 10-13/*\r\n\r\n").audioLength(10))
    }

    @Test fun partialResponsesCannotClaimAnotherRangeOrAnImpossibleTotal() {
        listOf(
            "Content-Range: bytes 0-3/100\r\n", "Content-Range: bytes 10-9/100\r\n",
            "Content-Range: bytes 10-13/13\r\n", "Content-Range: bytes 10-13/100\r\nContent-Length: 3\r\n",
            "Content-Range: bytes 10-9223372036854775808/*\r\n", "Content-Range: items 10-13/100\r\n", ""
        ).forEach { headers -> assertThrows(IOException::class.java) { head("HTTP/1.1 206 Partial Content\r\n$headers\r\n").audioLength(10) } }
        assertThrows(IOException::class.java) {
            head("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 0-9223372036854775807/*\r\n\r\n").audioLength(0)
        }
    }

    @Test fun fullResponsesExposeTheRemainingLengthAfterAnIgnoredRange() {
        val response = head("HTTP/1.1 200 OK\r\nContent-Length: 12\r\n\r\n")
        assertEquals(7L, response.audioLength(5))
        assertEquals(0L, response.audioLength(12))
        assertThrows(IOException::class.java) { response.audioLength(13) }
        assertEquals(-1L, head("HTTP/1.1 200 OK\r\n\r\n").audioLength(5))
    }

    @Test fun signedRedirectsAndCookiesAreNeverPublishedToMedia3Listeners() {
        val response = head("HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nContent-Length: 4\r\nLocation: /?signature=secret\r\nSet-Cookie: token=secret\r\n\r\n")
        assertEquals(setOf("Content-Type", "Content-Length"), response.publicHeaders().keys)
        assertEquals(listOf("audio/mpeg"), response.publicHeaders()["Content-Type"])
        assertFalse(response.publicHeaders().toString().contains("secret"))
    }

    @Test fun rangeRequestHonorsExplicitLengthAndDetectsOverflow() {
        assertNull(CastHttpHead.rangeHeader(0, -1))
        assertEquals("bytes=100-", CastHttpHead.rangeHeader(100, -1))
        assertEquals("bytes=100-119", CastHttpHead.rangeHeader(100, 20))
        assertEquals("bytes=0-0", CastHttpHead.rangeHeader(0, 1))
        assertThrows(IOException::class.java) { CastHttpHead.rangeHeader(Long.MAX_VALUE, 2) }
        assertThrows(IOException::class.java) { CastHttpHead.rangeHeader(-1, 3) }
        assertThrows(IOException::class.java) { CastHttpHead.rangeHeader(0, 0) }
    }

    @Test fun contentLengthDoesNotConsumeTheNextMessageAndDetectsTruncation() {
        val source = input("abcdNEXT")
        assertEquals("abcd", CastLengthInputStream(source, 4).readBytes().decodeToString())
        assertEquals('N'.code, source.read())
        assertThrows(IOException::class.java) { CastLengthInputStream(input("abc"), 4).readBytes() }
    }

    @Test fun chunkedAudioSupportsExtensionsTrailersAndSmallReads() {
        val source = CastChunkedInputStream(input("2;media=yes\r\nab\r\n3\r\ncde\r\n0\r\nX-Checksum: ignored\r\n\r\n"))
        assertEquals('a'.code, source.read())
        assertEquals("bcde", source.readBytes().decodeToString())
        assertEquals(-1, source.read())
        assertEquals(0, source.read(ByteArray(1), 0, 0))
    }

    @Test fun malformedAndTruncatedChunkedResponsesFailInsteadOfEndingNormally() {
        listOf(
            "1\r\n", "2\r\na", "1\r\naX\r\n0\r\n\r\n", "xyz\r\na\r\n0\r\n\r\n", "-1\r\n",
            "8000000000000000\r\n", "0\r\n", "0\r\nX: " + "a".repeat(32 * 1024) + "\r\n\r\n",
            "1;" + "a".repeat(8192) + "\r\na\r\n0\r\n\r\n"
        ).forEach { chunked -> assertThrows(IOException::class.java) { CastChunkedInputStream(input(chunked)).readBytes() } }
    }

    @Test fun framingHandlesValidZeroLengthReadsAndRejectsInvalidBufferSlices() {
        val streams: List<InputStream> = listOf(CastLengthInputStream(input("a"), 1), CastChunkedInputStream(input("1\r\na\r\n0\r\n\r\n")))
        streams.forEach {
            assertEquals(0, it.read(ByteArray(0), 0, 0))
            assertThrows(IndexOutOfBoundsException::class.java) { it.read(ByteArray(1), -1, 1) }
            assertThrows(IndexOutOfBoundsException::class.java) { it.read(ByteArray(1), 1, Int.MAX_VALUE) }
            assertEquals('a'.code, it.read())
            assertEquals(-1, it.read())
        }
    }

    private fun input(text: String) = ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1))
    private fun head(text: String): CastHttpHead = CastHttpHead.read(input(text))
}
