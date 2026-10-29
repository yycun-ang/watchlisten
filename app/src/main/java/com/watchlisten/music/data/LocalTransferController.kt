package com.watchlisten.music.data

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.security.SecureRandom
import java.util.Locale
import java.io.File
import java.util.UUID

data class LocalTransferState(
    val enabled: Boolean = false, val starting: Boolean = false,
    val address: String = "", val port: Int = 0, val code: String = "",
    val expiresAtElapsedMs: Long = 0, val uploading: Boolean = false,
    val fileName: String = "", val receivedBytes: Long = 0, val totalBytes: Long = 0,
    val completed: Int = 0, val error: String? = null, val message: String? = null
) {
    val ready: Boolean get() = enabled && !starting && port > 0
    val url: String get() = if (ready) "http://$address:$port/" else ""
}

/** 用户显式开启的 15 分钟局域网会话；验证码不落盘，后台不会自动重启。 */
class LocalTransferController(
    private val context: Context, private val localMusic: LocalMusicStore,
    private val scope: CoroutineScope
) {
    private val mutableState = MutableStateFlow(LocalTransferState())
    val state = mutableState.asStateFlow()
    private val connections = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var generation = 0L
    @Volatile private var sessionId = 0L
    internal val sessionRevision: Long get() = sessionId
    private var startup: Job? = null
    private var expiry: Job? = null
    private var server: LocalTransferServer? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    fun start() {
        if (state.value.enabled) return
        val session = ++sessionId
        val code = String.format(Locale.ROOT, "%08d", SecureRandom().nextInt(100_000_000))
        mutableState.value = LocalTransferState(enabled = true, starting = true, code = code,
            expiresAtElapsedMs = SystemClock.elapsedRealtime() + SESSION_MS)
        try { ContextCompat.startForegroundService(context, Intent(context, LocalTransferService::class.java)
            .putExtra(LocalTransferService.EXTRA_SESSION, session)) }
        catch (_: Exception) { stopRuntime("无法启动传歌，请保持应用在前台后重试") }
    }

    fun stop() {
        stopRuntime()
        context.stopService(Intent(context, LocalTransferService::class.java))
    }

    internal fun startRuntime() {
        if (!state.value.enabled || startup?.isActive == true || server != null) return
        val revision = ++generation
        startup = scope.launch {
            var created: LocalTransferServer? = null
            try {
                val binding = findWifi() ?: throw IllegalStateException("请先将手表和手机连接到同一个 Wi-Fi")
                val temporary = prepareResumeDirectory()
                val receiver = LocalTransferServer(binding.address, state.value.code, scope,
                    import = { input, name ->
                        currentCoroutineContext().ensureActive()
                        localMusic.importStream(input, name)
                    },
                    progress = { name, received, total -> if (current(revision)) mutableState.update {
                        it.copy(uploading = true, fileName = name, receivedBytes = received,
                            totalBytes = total, error = null, message = null)
                    } },
                    completed = { track -> if (current(revision)) mutableState.update {
                        it.copy(uploading = false, completed = it.completed + 1,
                            message = "已导入《${track.title}》", error = null)
                    } },
                    failed = { message -> if (current(revision)) mutableState.update {
                        it.copy(uploading = false, error = message)
                    } },
                    fatal = { message -> scope.launch { if (current(revision)) fail(message) } },
                    authLimit = { scope.launch { if (current(revision)) fail("验证码错误次数过多，传歌已关闭，请重新开启") } },
                    hasSpace = { length -> localMusic.directory.usableSpace - RESERVED_BYTES >= length },
                    resumeDirectory = temporary,
                    sessionDurationMs = (state.value.expiresAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0),
                    paused = { message -> if (current(revision)) mutableState.update {
                        it.copy(uploading = false, error = null, message = message)
                    } })
                created = receiver
                val port = withContext(Dispatchers.IO) { receiver.start() }
                ensureActive()
                if (!current(revision)) { receiver.close(); return@launch }
                server = receiver
                acquireLocks()
                mutableState.update { it.copy(starting = false, address = binding.address.hostAddress.orEmpty(),
                    port = port, message = "在手机浏览器打开地址并输入验证码") }
                watchNetwork(binding, revision)
                expiry = scope.launch {
                    delay((state.value.expiresAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                    if (current(revision)) fail("15 分钟已到，传歌已自动关闭")
                }
            } catch (cancelled: CancellationException) {
                created?.close()
                throw cancelled
            } catch (error: Exception) {
                created?.close()
                if (current(revision)) fail(if (findWifi() == null) "请先将手表和手机连接到同一个 Wi-Fi"
                    else "传歌暂时无法启动，请关闭后重试")
            } finally { if (revision == generation) startup = null }
        }
    }

    internal fun stopRuntime(error: String? = null) {
        sessionId++
        generation++
        startup?.cancel(); startup = null
        expiry?.cancel(); expiry = null
        networkCallback?.let { runCatching { connections.unregisterNetworkCallback(it) } }
        networkCallback = null
        server?.close(); server = null
        wifiLock?.let { runCatching { if (it.isHeld) it.release() } }; wifiLock = null
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }; wakeLock = null
        mutableState.value = LocalTransferState(completed = state.value.completed,
            error = error, message = if (error == null) "传歌已关闭" else null)
    }

    internal fun stopIfSession(session: Long, error: String? = null): Boolean {
        if (session != sessionId) return false
        stopRuntime(error)
        return true
    }

    private fun fail(message: String) {
        stopRuntime(message)
        context.stopService(Intent(context, LocalTransferService::class.java))
    }

    private fun current(revision: Long): Boolean = revision == generation && state.value.enabled

    private suspend fun prepareResumeDirectory(): File = withContext(Dispatchers.IO) {
        val parent = File(context.cacheDir, "transfer-parts").canonicalFile
        if (!parent.mkdirs() && !parent.isDirectory) throw java.io.IOException("无法创建传歌暂存目录")
        parent.listFiles()?.filter { it.name.matches(Regex("session-[a-f0-9-]{36}")) &&
            it.canonicalFile.parentFile == parent }?.forEach { old ->
            old.listFiles()?.filter { it.name.matches(Regex("[a-f0-9]{32}\\.part")) &&
                it.canonicalFile.parentFile == old.canonicalFile }?.forEach(File::delete)
            old.delete()
        }
        File(parent, "session-${UUID.randomUUID()}")
    }

    private fun acquireLocks() {
        @Suppress("DEPRECATION")
        val lock = context.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "watchlisten-transfer")
        wifiLock = lock.apply { setReferenceCounted(false); acquire() }
        wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "watchlisten:transfer")
            .apply { setReferenceCounted(false); acquire(SESSION_MS + 10_000) }
    }

    private data class WifiBinding(val network: Network, val address: Inet4Address, val interfaceName: String)

    private fun findWifi(): WifiBinding? = runCatching {
        connections.allNetworks.firstNotNullOfOrNull { network ->
            val capabilities = connections.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return@firstNotNullOfOrNull null
            val properties = connections.getLinkProperties(network) ?: return@firstNotNullOfOrNull null
            val address = properties.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress }
                ?: return@firstNotNullOfOrNull null
            WifiBinding(network, address, properties.interfaceName.orEmpty())
        }
    }.getOrNull()

    private fun watchNetwork(binding: WifiBinding, revision: Long) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (network == binding.network) changed()
            }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                if (network == binding.network && (properties.interfaceName != binding.interfaceName ||
                        properties.linkAddresses.none { it.address == binding.address })) changed()
            }
            private fun changed() { scope.launch { if (current(revision)) fail("Wi-Fi 连接已变化，请重新开启传歌") } }
        }
        networkCallback = callback
        connections.requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
    }

    companion object {
        const val SESSION_MS = 15 * 60_000L
        private const val RESERVED_BYTES = 8L * 1024 * 1024
    }
}
