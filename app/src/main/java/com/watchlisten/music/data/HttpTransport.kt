package com.watchlisten.music.data

import com.watchlisten.music.model.MusicError
import com.watchlisten.music.model.MusicFailure
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpTransport(configureClient: (OkHttpClient.Builder) -> Unit = {},
                    private val allowNetwork: () -> Boolean = { true }) {
    private val consentGate = Interceptor { chain ->
        if (!allowNetwork()) throw MusicFailure(MusicError.ConsentRequired)
        chain.proceed(chain.request())
    }
    val api = OkHttpClient.Builder().fastFallback(true).connectTimeout(5, TimeUnit.SECONDS)
        // Application gate runs before DNS. Network gate also checks redirected requests.
        .addInterceptor(consentGate).addNetworkInterceptor(consentGate)
        .apply(configureClient)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(22, TimeUnit.SECONDS).build()
    val media = api.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    fun cancelAll() {
        api.dispatcher.cancelAll()
        media.dispatcher.cancelAll()
    }

    suspend fun json(request: Request): JSONObject = try {
        val payload = bytes(request)
        withContext(Dispatchers.Default) { JSONObject(String(payload, Charsets.UTF_8)) }
    }
        catch (error: org.json.JSONException) { throw MusicFailure(MusicError.InvalidResponse) }

    /**
     * 手表上 DNS 失败常是切换网络后的瞬时问题，等待系统解析器刷新后重试一次；
     * 仍然失败才向上返回 [MusicError.DnsUnavailable]。
     */
    suspend fun bytes(request: Request, limit: Int = 2 * 1024 * 1024): ByteArray = try {
        send(request, limit)
    } catch (error: MusicFailure) {
        if (error.error != MusicError.DnsUnavailable) throw error
        delay(600)
        send(request, limit)
    }

    private suspend fun send(request: Request, limit: Int): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = api.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(mapFailure(error))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use { read(it, limit) }
                    if (continuation.isActive) continuation.resume(value)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(mapFailure(error))
                }
            }
        })
    }

    private fun read(response: Response, limit: Int): ByteArray {
        if (!response.isSuccessful) throw MusicFailure(when (response.code) {
            401 -> MusicError.AuthenticationExpired
            403 -> MusicError.PermissionDenied
            else -> MusicError.NetworkUnavailable
        })
        val body = response.body ?: throw MusicFailure(MusicError.InvalidResponse)
        body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > limit) throw MusicFailure(MusicError.InvalidResponse)
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private fun mapFailure(error: Exception): Exception = when (error) {
        is MusicFailure -> error
        // OkHttp 用 UnknownHostException 表示 DNS 解析失败，与"设备没连网"是不同的可恢复路径。
        is java.net.UnknownHostException -> MusicFailure(MusicError.DnsUnavailable)
        is javax.net.ssl.SSLException -> MusicFailure(MusicError.InvalidResponse)
        is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> MusicFailure(MusicError.SlowNetwork)
        else -> MusicFailure(MusicError.NetworkUnavailable)
    }
}
