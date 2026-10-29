package com.watchlisten.music

import com.watchlisten.music.data.HttpTransport
import com.watchlisten.music.model.MusicError
import com.watchlisten.music.model.MusicFailure
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

class HttpConsentTest {
    private val request = Request.Builder().url("https://consent-fixture.invalid/music").build()

    @Test fun apiIsBlockedBeforeDnsWithoutConsent() {
        assertBlockedBeforeDns(HttpTransport { false }.api)
    }

    @Test fun mediaIsBlockedBeforeDnsWithoutConsent() {
        assertBlockedBeforeDns(HttpTransport { false }.media)
    }

    @Test fun revokingConsentAlsoBlocksAnExistingMediaClient() {
        var allowed = true
        var fixtureResponses = 0
        val transport = HttpTransport { allowed }
        val client = transport.media.newBuilder().addInterceptor { chain ->
            fixtureResponses++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("local fixture").body("fixture".toResponseBody()).build()
        }.build()
        client.newCall(request).execute().use { assertEquals(200, it.code) }
        allowed = false
        val error = runCatching { client.newCall(request).execute().close() }.exceptionOrNull()
        assertEquals(MusicError.ConsentRequired, (error as? MusicFailure)?.error)
        assertEquals(1, fixtureResponses)
    }

    private fun assertBlockedBeforeDns(client: OkHttpClient) {
        val lookups = AtomicInteger()
        val observed = client.newBuilder().dns(Dns {
            lookups.incrementAndGet()
            throw UnknownHostException("DNS must not run without consent")
        }).build()
        val error = runCatching { observed.newCall(request).execute().close() }.exceptionOrNull()
        assertEquals(MusicError.ConsentRequired, (error as? MusicFailure)?.error)
        assertEquals(0, lookups.get())
    }
}
