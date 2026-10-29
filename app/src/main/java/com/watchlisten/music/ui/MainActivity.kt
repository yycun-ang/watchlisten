package com.watchlisten.music.ui

import android.Manifest
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import android.net.Uri
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watchlisten.music.WatchApplication
import com.watchlisten.music.model.isCollectionRoute
import com.watchlisten.music.model.isArtistRoute
import com.watchlisten.music.model.artistIdFromRoute
import com.watchlisten.music.ui.nav.BackDecision
import com.watchlisten.music.ui.nav.BackInput
import com.watchlisten.music.ui.nav.BackInputGate
import com.watchlisten.music.ui.nav.BackNavigator
import com.watchlisten.music.ui.nav.EdgeBackGesture
import com.watchlisten.music.ui.nav.MainTab
import com.watchlisten.music.ui.nav.PageStack
import com.watchlisten.music.ui.nav.PlaybackSource
import com.watchlisten.music.ui.nav.restoreNavigation
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WatchViewModel(application: android.app.Application) : AndroidViewModel(application) {
    val graph = (application as WatchApplication).graph
}

/**
 * 单一宿主 Activity。
 *
 * 使用 [PageStack] 在同一个 Activity 内管理页面与来源返回，
 * 保留页面栈、根标签和媒体入口之间一致的导航语义，
 * 并由单一宿主统一处理系统返回键与边缘返回手势。
 *
 * [PageActivity] 保留为同宿主的别名，媒体通知等既有入口不受影响。
 */
open class WatchActivity : ComponentActivity() {
    private val model: WatchViewModel by viewModels()
    private val route = mutableStateOf("home")
    private var graphEntered = false
    private var pendingSearch: String? = null
    private var returnToSourceOnBack = false

    /** 页面栈，根页为首页容器。 */
    private val stack = PageStack("home")

    /** 当前根标签；详情页压栈后仍保留来源标签。 */
    private val tab = mutableStateOf(MainTab.HOME)

    private val backInputs = BackInputGate()
    private val edgeBack by lazy {
        EdgeBackGesture(EdgeBackGesture.Thresholds(
            edgeWidthPx = EDGE_BACK_WIDTH_DP * resources.displayMetrics.density,
            touchSlopPx = ViewConfiguration.get(this).scaledTouchSlop.toFloat(),
            minimumDistancePx = EDGE_BACK_DISTANCE_DP * resources.displayMetrics.density
        ))
    }
    private val edgeBackRegion = Rect()

    /** 页面栈、根标签与回表盘共用一次返回决策。 */
    private val back = BackNavigator(
        onExit = { if (!moveTaskToBack(true)) finish() },
        dispatchToPage = {
            when (tab.value) {
                MainTab.CONTROLS -> selectTab(MainTab.PLAYER)
                MainTab.PLAYER -> selectTab(MainTab.HOME)
                MainTab.HOME -> Unit
            }
        })

    /** 仅用于恢复系统媒体入口；拒绝通知不影响播放本身。 */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { markNotificationAsked() }

    private val voiceSearch = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                ?.trim()?.takeIf(String::isNotEmpty)?.let { query ->
                    open("search")
                    if (model.graph.consent.accepted.value) model.graph.music.search(query)
                }
        }
    }

    private val importMusic = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            model.graph.localMusic.importUris(uris)
            open("local-music")
        }
    }

    private val importLyrics = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.graph.localMusic.importLyrics(uri)
        else model.graph.localMusic.cancelLyricsImport()
    }
    private val exportMusic = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val track = model.graph.localMusic.pendingExportTrack
        if (uri != null) model.graph.localMusic.exportTo(track, uri)
        else model.graph.localMusic.cancelExport()
    }
    private val exportDiagnostics = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch {
            val saved = try { withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    output.write(model.graph.diagnosticReport().toByteArray(Charsets.UTF_8))
                } ?: throw java.io.IOException("No destination")
            }; true } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            model.graph.diagnostics.exportFinished(saved)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        installBackHandling()
        if (savedInstanceState == null) handleNavigationIntent(intent)
        else {
            val pages = savedInstanceState.getStringArrayList("navigation_stack")
                ?.filter(::isSupportedRoute).orEmpty()
            val restored = restoreNavigation(pages, savedInstanceState.getInt("navigation_tab", 0))
            stack.restore(restored.pages)
            tab.value = restored.tab
            route.value = stack.current
            back.restoreDepth(stack.depth)
            pendingSearch = savedInstanceState.getString("pending_search")
            returnToSourceOnBack = savedInstanceState.getBoolean("return_to_source_on_back", false)
            savedInstanceState.getStringArrayList("playback_source_pages")?.filter(::isSupportedRoute)
                ?.takeIf { it.size > 1 }?.let { pages ->
                    model.graph.playbackSource.value = PlaybackSource(pages,
                        MainTab.of(savedInstanceState.getInt("playback_source_tab", 0)),
                        savedInstanceState.getString("playback_source_label") ?: "歌曲列表")
                }
        }
        setContent {
            val accepted by model.graph.consent.accepted.collectAsStateWithLifecycle()
            if (accepted) {
                WatchScreen(route.value, stack.depth, tab.value, ::selectTab, model.graph, ::open, ::close)
            } else {
                ConsentScreen(onAccept = { model.graph.consent.accept() }, onDecline = ::finish)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.graph.consent.accepted.collect { accepted ->
                    if (accepted) {
                        enterGraphIfAllowed()
                        ensureNotificationPermission()
                    } else {
                        leaveGraphIfEntered()
                        edgeBack.cancel()
                        backInputs.endEdgeGesture(SystemClock.uptimeMillis())
                    }
                    updateEdgeBackRegion()
                }
            }
        }
        window.decorView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateEdgeBackRegion()
        }
        updateEdgeBackRegion()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("navigation_stack", ArrayList(stack.snapshot()))
        outState.putInt("navigation_tab", tab.value.id)
        outState.putString("pending_search", pendingSearch)
        outState.putBoolean("return_to_source_on_back", returnToSourceOnBack)
        model.graph.playbackSource.value?.let { source ->
            outState.putStringArrayList("playback_source_pages", ArrayList(source.pages))
            outState.putInt("playback_source_tab", source.tab.id)
            outState.putString("playback_source_label", source.label)
        }
        super.onSaveInstanceState(outState)
    }

    private fun handleNavigationIntent(intent: Intent) {
        intent.getStringExtra("page")?.takeIf(String::isNotBlank)?.let(::open)
        if (intent.action == Intent.ACTION_SEARCH) {
            intent.getStringExtra(android.app.SearchManager.QUERY)?.let {
                open("search")
                if (model.graph.consent.accepted.value) model.graph.music.search(it)
                else pendingSearch = it
            }
        }
    }

    override fun onStart() { super.onStart(); enterGraphIfAllowed() }
    override fun onStop() {
        edgeBack.cancel()
        backInputs.endEdgeGesture(SystemClock.uptimeMillis())
        leaveGraphIfEntered()
        super.onStop()
    }

    private fun enterGraphIfAllowed() {
        if (graphEntered || !model.graph.consent.accepted.value) return
        graphEntered = true
        model.graph.enter()
        pendingSearch?.let { query ->
            pendingSearch = null
            model.graph.music.search(query)
        }
    }

    private fun leaveGraphIfEntered() {
        if (!graphEntered) return
        graphEntered = false
        model.graph.leave()
    }

    /** 只接管 BACK；DOWN 触发一次，UP 和长按重复也消费，避免厂商 UP 继续关闭窗口。 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN &&
            backInputs.acceptKeyDown(event.downTime, event.repeatCount, SystemClock.uptimeMillis())) {
            handleBack()
        }
        return true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startEdgeBack(event)
            MotionEvent.ACTION_MOVE -> if (moveEdgeBack(event)) return true
            MotionEvent.ACTION_UP -> if (finishEdgeBack(event)) return true
            MotionEvent.ACTION_CANCEL -> if (cancelEdgeBack()) return true
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 多指从不触发返回；已接管的序列仍须消费到结束，不能把半截手势交回页面。
                edgeBack.cancelNavigation()
                if (!edgeBack.isActive) backInputs.endEdgeGesture(SystemClock.uptimeMillis())
            }
        }
        if (edgeBack.isCaptured) return true
        return super.dispatchTouchEvent(event)
    }

    private fun startEdgeBack(event: MotionEvent) {
        val fromHome = stack.isRootVisible && tab.value == MainTab.HOME
        edgeBack.start(event.x, event.y,
            canHandleEdgeBack() && (fromHome || event.y >= edgeBackRegion.top && event.y < edgeBackRegion.bottom),
            fromAnywhere = fromHome)
        if (edgeBack.isActive) backInputs.beginEdgeGesture() else backInputs.endEdgeGesture(SystemClock.uptimeMillis())
    }

    private fun moveEdgeBack(event: MotionEvent): Boolean {
        val wasCaptured = edgeBack.isCaptured
        val captured = edgeBack.move(event.x, event.y)
        if (captured && !wasCaptured) cancelPageTouch(event)
        if (!edgeBack.isActive) backInputs.endEdgeGesture(SystemClock.uptimeMillis())
        return captured
    }

    private fun finishEdgeBack(event: MotionEvent): Boolean {
        val captured = edgeBack.isCaptured
        val navigateBack = edgeBack.finish(event.x, event.y)
        if (navigateBack && backInputs.accept(BackInput.EDGE_GESTURE, SystemClock.uptimeMillis())) {
            handleBack()
        }
        backInputs.endEdgeGesture(SystemClock.uptimeMillis())
        return captured
    }

    private fun cancelEdgeBack(): Boolean {
        val captured = edgeBack.isCaptured
        edgeBack.cancel()
        backInputs.endEdgeGesture(SystemClock.uptimeMillis())
        return captured
    }

    private fun cancelPageTouch(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    private fun canHandleEdgeBack(): Boolean = model.graph.consent.accepted.value

    /** Android 限制每侧排除区域高度为 200dp；圆屏取中部可触达的左边缘。 */
    private fun updateEdgeBackRegion() {
        val decor = window.decorView
        val density = resources.displayMetrics.density
        val height = minOf(decor.height, (EDGE_BACK_EXCLUSION_HEIGHT_DP * density).toInt())
        val top = (decor.height - height) / 2
        edgeBackRegion.set(0, top, minOf(decor.width, (EDGE_BACK_WIDTH_DP * density).toInt()), top + height)
        decor.systemGestureExclusionRects = if (canHandleEdgeBack() && !edgeBackRegion.isEmpty) {
            listOf(Rect(edgeBackRegion))
        } else emptyList()
    }

    /**
     * 接管系统返回键，依次处理详情页面、根标签和返回表盘，
     * 使系统返回与边缘手势使用同一套优先级。
     *
     * 保留 [androidx.activity.OnBackPressedDispatcher] 作为系统返回入口，
     * 并过滤厂商与 BACK 键、边缘触摸重复上报的回调。
     */
    private fun installBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (backInputs.accept(BackInput.SYSTEM, SystemClock.uptimeMillis())) handleBack()
            }
        })
    }

    /**
     * 返回优先级：当前页 → 页面栈 → 前一根标签 → 首页回表盘。
     *
     * 系统回调、BACK 键和左边缘手势共用此入口，保证每次只回退一层。
     */
    private fun handleBack() {
        if (!model.graph.consent.accepted.value) {
            finish()
            return
        }
        if (stack.isRootVisible && tab.value == MainTab.PLAYER && returnToSourceOnBack && returnToPlaybackSource()) return
        when (back.onBack(pageConsumes = false, onRootTab = tab.value == MainTab.HOME)) {
            // 非首页根标签已在 BackNavigator 的回调中切换一次。
            BackDecision.DispatchToPage -> Unit
            // 导航器已把深度减一，这里同步弹出真实页面栈。
            BackDecision.PopStack -> if (stack.pop() != null) route.value = stack.current
            BackDecision.ExitNow -> Unit // BackNavigator 已把界面退到后台，音乐继续播放。
            BackDecision.ConsumedByPage -> Unit
        }
        updateEdgeBackRegion()
    }

    /** 所有主页面入口都复用根 Pager，搜索与列表不再创建第二个播放器。 */
    private fun open(page: String) {
        if (page == "voice-search") { launchVoiceSearch(); return }
        if (page == "import-music") { launchFilePicker { importMusic.launch(arrayOf("audio/*")) }; return }
        if (page == "import-lyrics") {
            if (model.graph.localMusic.pendingLyricsTrack == null || !launchFilePicker {
                importLyrics.launch(arrayOf("text/*", "application/x-lrc", "application/lrc", "application/octet-stream"))
            }) model.graph.localMusic.cancelLyricsImport()
            return
        }
        if (page == "export-music") {
            val track = model.graph.localMusic.pendingExportTrack
            if (track == null || !launchFilePicker {
                exportMusic.launch(model.graph.localMusic.exportFileName(track))
            }) model.graph.localMusic.cancelExport()
            return
        }
        if (page == "export-diagnostics") {
            launchFilePicker { exportDiagnostics.launch("腕上听-诊断-${System.currentTimeMillis()}.json") }
            return
        }
        if (page == "return-source") { returnToPlaybackSource(); return }
        if (!isSupportedRoute(page)) return
        val targetTab = MainTab.ofRoute(page)
        if (targetTab != null) {
            if (targetTab == MainTab.PLAYER && stack.depth > 1 && stack.current != "lyrics") {
                model.graph.playbackSource.value = PlaybackSource(stack.snapshot(), tab.value,
                    sourceLabel(stack.current))
                returnToSourceOnBack = true
            }
            stack.popToRoot()
            back.popToRoot()
            route.value = stack.current
            selectTab(targetTab)
        } else {
            if (stack.push(page)) back.pushPage()
            route.value = stack.current
        }
        updateEdgeBackRegion()
    }

    private fun sourceLabel(page: String): String = when (page) {
        "search" -> "搜索结果"
        "favorites" -> "酷狗我喜欢"
        "history" -> "最近播放"
        "local-music" -> "离线音乐"
        "queue" -> "播放队列"
        "cloud-manage" -> "酷狗歌单"
        else -> if (isArtistRoute(page)) artistIdFromRoute(page)?.let { model.graph.discovery.state.value.artists[it]?.profile?.name } ?: "歌手主页"
            else model.graph.browse.detail(page)?.collection?.title ?: "歌曲列表"
    }

    private fun returnToPlaybackSource(): Boolean {
        val source = model.graph.playbackSource.value ?: return false
        val pages = source.pages.filter(::isSupportedRoute)
        if (pages.size <= 1) return false
        stack.restore(pages)
        returnToSourceOnBack = false
        route.value = stack.current
        tab.value = source.tab
        back.restoreDepth(stack.depth)
        updateEdgeBackRegion()
        return true
    }

    private fun launchVoiceSearch() {
        if (!model.graph.consent.accepted.value) return
        val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "说出歌曲或歌手")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        try { voiceSearch.launch(request) }
        catch (_: android.content.ActivityNotFoundException) {
            open("search")
            Toast.makeText(this, "手表暂无可用语音服务，请使用文字搜索", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchFilePicker(launch: () -> Unit): Boolean {
        return try { launch(); true }
        catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "系统文件界面不可用；音频可通过手机传歌导入", Toast.LENGTH_LONG).show()
            false
        } catch (_: SecurityException) {
            Toast.makeText(this, "系统暂不允许打开文件，请稍后重试", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun selectTab(next: MainTab) {
        if (next == MainTab.HOME) returnToSourceOnBack = false
        if (tab.value == next) return
        tab.value = next
        updateEdgeBackRegion()
    }

    /**
     * 返回栈上一页。
     *
     * 栈内回退已由 [handleBack] 统一处理（返回键与边缘手势共用），这里只
     * 承担"页面自己发起的关闭"（歌词页收起、迷你播放器的返回箭头）。
     */
    private fun close() {
        if (stack.depth > 1) {
            stack.pop()
            route.value = stack.current
            back.popPage()
            updateEdgeBackRegion()
        } else {
            handleBack()
        }
    }

    private fun notificationSettled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return getSharedPreferences("permissions", MODE_PRIVATE)
            .getBoolean("notification_asked", false)
    }

    private fun markNotificationAsked() {
        getSharedPreferences("permissions", MODE_PRIVATE)
            .edit().putBoolean("notification_asked", true).apply()
    }

    private fun ensureNotificationPermission() {
        if (!model.graph.consent.accepted.value) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationSettled()) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) { markNotificationAsked(); return }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private const val EDGE_BACK_WIDTH_DP = 48f
private const val EDGE_BACK_DISTANCE_DP = 48f
private const val EDGE_BACK_EXCLUSION_HEIGHT_DP = 200f

private val supportedRoutes = setOf("home", "player", "lyrics", "search", "queue", "library",
    "favorites", "history", "account", "more", "info", "settings", "about", "privacy", "dlna",
    "discover", "rankings", "featured", "cloud", "pinned", "hot-search", "local-music", "transfer", "cloud-manage", "diagnostics", "catalog-search")
private fun isSupportedRoute(route: String) = route in supportedRoutes || isCollectionRoute(route) || isArtistRoute(route)

class MainActivity : WatchActivity()

/** 兼容既有入口：媒体通知的 PendingIntent 仍指向这里，实际由同一宿主承载。 */
class PageActivity : WatchActivity()
