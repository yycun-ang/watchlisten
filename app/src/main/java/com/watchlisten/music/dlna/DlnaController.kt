package com.watchlisten.music.dlna

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.watchlisten.music.AppGraph
import com.watchlisten.music.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

data class DlnaStatus(
    val enabled: Boolean = false, val starting: Boolean = false, val ready: Boolean = false,
    val name: String = "腕上听 · ${Build.MODEL}", val address: String = "", val port: Int = 0,
    val peer: String? = null, val error: String? = null,
    val notice: String? = null, val lastDisconnect: String? = null
)

/** Explicit, foreground-only receiver lifetime. No boot listener, sticky restart or saved enable flag. */
class DlnaController(private val context: Context, private val graph: AppGraph) {
    private val preferences = context.getSharedPreferences("dlna", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(DlnaStatus(lastDisconnect = preferences.getString("last_disconnect", null)))
    val state = mutableState.asStateFlow()
    val sources = CastSourceStore()
    private val connections = context.getSystemService(ConnectivityManager::class.java)
    private var generation = 0L
    private var server: DlnaServer? = null
    private var target: DlnaPlaybackTarget? = null
    private var startup: Job? = null
    private var monitoring: Job? = null
    private var networkCheck: Job? = null
    private var closing: Job? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var wifiWake: WifiManager.WifiLock? = null
    private var wake: PowerManager.WakeLock? = null
    private var lastActivity = 0L
    private var bound: Binding? = null
    private val uuid: String = preferences.let { prefs ->
        prefs.getString("uuid", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("uuid", it).apply() }
    }

    init {
        graph.scope.launch { graph.consent.accepted.collect { if (!it && state.value.enabled) disable() } }
        graph.scope.launch {
            var previous = graph.sessions.session.value
            graph.sessions.session.collect { current ->
                if (previous != null && (current?.userId != previous?.userId || current?.token != previous?.token)) disable()
                previous = current
            }
        }
    }

    fun enable() {
        if (state.value.enabled || !graph.consent.accepted.value) return
        mutableState.value = DlnaStatus(enabled = true, starting = true, lastDisconnect = state.value.lastDisconnect)
        try { ContextCompat.startForegroundService(context, Intent(context, DlnaReceiverService::class.java)) }
        catch (_: Exception) { stopRuntime("无法启动投放，请保持应用在前台后重试") }
    }

    fun disable() {
        stopRuntime()
        context.stopService(Intent(context, DlnaReceiverService::class.java))
    }

    fun disconnect() {
        target?.disconnect()
        lastActivity = SystemClock.elapsedRealtime()
        server?.notifyState()
    }

    internal fun startRuntime() {
        if (!state.value.enabled || !graph.consent.accepted.value || startup != null || server != null) return
        val revision = ++generation
        startup = graph.scope.launch(start = CoroutineStart.LAZY) {
            var created: DlnaServer? = null
            try {
                val wifi = findWifi() ?: throw IllegalStateException("请先将手表和手机连接到同一个 Wi-Fi")
                closing?.join()
                val player = graph.playback.ready()
                ensureActive()
                if (revision != generation) return@launch
                bound = wifi
                sources.bind(wifi.network)
                val bridge = DlnaPlaybackTarget(graph, context, sources,
                    { revision == generation && state.value.enabled && graph.consent.accepted.value },
                    { peer -> mutableState.value = state.value.copy(peer = peer) },
                    { lastActivity = SystemClock.elapsedRealtime(); mutableState.value = state.value.copy(notice = null); server?.notifyState() })
                bridge.attach(player)
                target = bridge
                multicast = context.getSystemService(WifiManager::class.java).createMulticastLock("watchlisten-dlna").apply {
                    setReferenceCounted(false); acquire()
                }
                @Suppress("DEPRECATION")
                val radioLock = context.getSystemService(WifiManager::class.java)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "watchlisten-dlna-radio")
                wifiWake = radioLock.apply { setReferenceCounted(false); acquire() }
                wake = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "watchlisten:dlna").apply {
                    setReferenceCounted(false); acquire(65_000)
                }
                val receiver = DlnaServer(DlnaProtocol(DlnaIdentity(uuid, state.value.name, version = BuildConfig.VERSION_NAME), bridge),
                    { revision == generation && state.value.enabled && graph.consent.accepted.value },
                    { message -> graph.scope.launch { if (revision == generation && state.value.enabled) {
                        mutableState.value = state.value.copy(notice = message)
                        Log.w(LOG_TAG, message)
                    } } },
                    eventSocketFactory = wifi.network.socketFactory,
                    bindDatagram = { wifi.network.bindSocket(it) },
                    onFatalError = { message -> graph.scope.launch { if (revision == generation) fail(message) } })
                created = receiver
                val preferredPort = preferences.getInt("http_port", 0)
                val port = withContext(Dispatchers.IO) { receiver.start(wifi.address, wifi.device, preferredPort) }
                if (revision != generation) { withContext(Dispatchers.IO) { receiver.close() }; return@launch }
                server = receiver
                preferences.edit().putInt("http_port", port).apply()
                mutableState.value = state.value.copy(starting = false, ready = true, address = wifi.address.hostAddress.orEmpty(), port = port, error = null)
                Log.i(LOG_TAG, "接收服务已启动，控制与音频绑定 Wi-Fi")
                lastActivity = SystemClock.elapsedRealtime()
                watchNetwork(wifi, revision)
                monitoring = launch {
                    var wasPlaying = false
                    var wakeRefreshed = SystemClock.elapsedRealtime()
                    var networkChecked = SystemClock.elapsedRealtime()
                    while (isActive) {
                        delay(1000)
                        if (!graph.playback.state.value.connected) { fail("播放器连接已断开，请重新开启投放"); break }
                        val playing = graph.playback.state.value.let { it.current?.track?.isRemote == true && it.playing }
                        if (playing || wasPlaying) lastActivity = SystemClock.elapsedRealtime()
                        wasPlaying = playing
                        val limit = if (state.value.peer == null) 10 * 60_000L else 30 * 60_000L
                        if (SystemClock.elapsedRealtime() - lastActivity > limit) { fail("长时间没有投放，接收已自动关闭"); break }
                        if (SystemClock.elapsedRealtime() - wakeRefreshed >= 30_000) {
                            wake?.acquire(65_000)
                            wakeRefreshed = SystemClock.elapsedRealtime()
                        }
                        if (SystemClock.elapsedRealtime() - networkChecked >= 5_000) {
                            if (!bindingIsActive(wifi)) checkNetworkAfterGrace(wifi, revision)
                            networkChecked = SystemClock.elapsedRealtime()
                        }
                        target?.localSelection()
                    }
                }
            } catch (_: TimeoutCancellationException) {
                created?.let { withContext(NonCancellable + Dispatchers.IO) { it.close() } }
                if (revision == generation) fail("播放器连接超时，请重新开启投放")
            } catch (cancelled: CancellationException) {
                created?.let { withContext(NonCancellable + Dispatchers.IO) { it.close() } }
                throw cancelled
            } catch (_: Exception) {
                created?.let { withContext(Dispatchers.IO) { it.close() } }
                if (revision == generation) fail(if (findWifi() == null) "请先将手表和手机连接到同一个 Wi-Fi" else "投放服务暂时无法启动，请关闭后重试")
            } finally { if (revision == generation) startup = null }
        }
        startup?.start()
    }

    internal fun stopRuntime(error: String? = null) {
        generation++
        val lastDisconnect = error?.let {
            val stamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date())
            "$stamp · $it".also { reason -> preferences.edit().putString("last_disconnect", reason).apply() }
        } ?: state.value.lastDisconnect
        mutableState.value = DlnaStatus(error = error, lastDisconnect = lastDisconnect)
        Log.i(LOG_TAG, error ?: "接收服务已关闭")
        startup?.cancel(); startup = null
        monitoring?.cancel(); monitoring = null
        networkCheck?.cancel(); networkCheck = null
        callback?.let { runCatching { connections.unregisterNetworkCallback(it) } }; callback = null
        target?.release(); target = null
        sources.unbind()
        val oldServer = server; server = null
        if (oldServer != null) closing = graph.scope.launch(Dispatchers.IO) { oldServer.close() }
        multicast?.let { if (it.isHeld) it.release() }; multicast = null
        wifiWake?.let { if (it.isHeld) it.release() }; wifiWake = null
        wake?.let { if (it.isHeld) it.release() }; wake = null
        bound = null
    }

    private fun fail(message: String) {
        stopRuntime(message)
        context.stopService(Intent(context, DlnaReceiverService::class.java))
    }

    private fun watchNetwork(binding: Binding, revision: Long) {
        val observer = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) { if (network == binding.network) changed() }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                if (network == binding.network && (properties.interfaceName != binding.device.name || properties.linkAddresses.none { it.address == binding.address })) changed()
            }
            private fun changed() { graph.scope.launch { checkNetworkAfterGrace(binding, revision) } }
        }
        callback = observer
        // An active request retains Wi-Fi even when Bluetooth tethering is the default network.
        connections.requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), observer)
        if (!bindingIsActive(binding)) checkNetworkAfterGrace(binding, revision)
    }

    private fun checkNetworkAfterGrace(binding: Binding, revision: Long) {
        if (revision != generation || !state.value.enabled || networkCheck?.isActive == true) return
        networkCheck = graph.scope.launch {
            // Confirm the latest snapshot outside the platform callback: roaming/DHCP can publish partial properties.
            delay(2_000)
            if (revision != generation || !state.value.enabled) return@launch
            if (!bindingIsActive(binding)) fail("Wi-Fi 已断开或切换，请重新开启手机投放")
        }
    }

    private fun bindingIsActive(binding: Binding): Boolean {
        if (connections.getNetworkCapabilities(binding.network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return false
        val properties = connections.getLinkProperties(binding.network) ?: return false
        return properties.interfaceName == binding.device.name && properties.linkAddresses.any { it.address == binding.address }
    }

    private data class Binding(val network: Network, val address: Inet4Address, val device: NetworkInterface)
    private fun findWifi(): Binding? = connections.allNetworks.firstNotNullOfOrNull { network ->
        val capabilities = connections.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@firstNotNullOfOrNull null
        val properties = connections.getLinkProperties(network) ?: return@firstNotNullOfOrNull null
        val address = properties.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull {
            runCatching { RemoteUrlPolicy.controllerAddress(it.hostAddress.orEmpty()) }.isSuccess
        } ?: return@firstNotNullOfOrNull null
        val device = properties.interfaceName?.let { runCatching { NetworkInterface.getByName(it) }.getOrNull() }
            ?: return@firstNotNullOfOrNull null
        Binding(network, address, device)
    }

    companion object { private const val LOG_TAG = "WatchCast" }
}
