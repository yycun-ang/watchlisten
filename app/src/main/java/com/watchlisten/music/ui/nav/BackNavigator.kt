package com.watchlisten.music.ui.nav

/**
 * 返回优先处理当前页面，再逐层回退；首页根页一次返回到表盘。
 */
sealed interface BackDecision {
    /** 当前页面自己消化了返回（例如搜索页收起输入法、歌词页关闭）。 */
    data object ConsumedByPage : BackDecision

    /** 回退页面栈一页。 */
    data object PopStack : BackDecision

    /** 已在首页根页，本次退到表盘。 */
    data object ExitNow : BackDecision

    /** 根容器回到前一标签页。 */
    data object DispatchToPage : BackDecision
}

/**
 * 所有返回来源共用同一决策，不能因厂商先上报键事件而改变首页退出规则。
 *
 * @param onExit 首页根页退到表盘，只关闭界面、不停止音乐。
 * @param dispatchToPage 播放／控制根页回到前一标签。
 */
class BackNavigator(
    private val onExit: () -> Unit,
    private val dispatchToPage: () -> Unit
) {
    /** 页面栈深度。1 表示只剩根页（首页容器）。 */
    var stackDepth: Int = 1
        private set

    fun pushPage() { stackDepth++ }

    /** 页面自己关闭一层时同步深度，与 [onBack] 的回退保持同一份计数。 */
    fun popPage() { if (stackDepth > 1) stackDepth-- }

    fun popToRoot() { stackDepth = 1 }

    fun restoreDepth(depth: Int) {
        stackDepth = depth.coerceAtLeast(1)
    }

    /**
     * @param pageConsumes 当前页面是否已自行处理返回。
     * @param onRootTab 当前是否停在首页；只有首页根页才退到表盘。
     */
    fun onBack(pageConsumes: Boolean, onRootTab: Boolean): BackDecision {
        if (pageConsumes) return BackDecision.ConsumedByPage

        if (stackDepth > 1) {
            stackDepth--
            return BackDecision.PopStack
        }

        // 栈只剩根页时，先返回前一标签，不能跨过首页直接退出。
        if (!onRootTab) {
            dispatchToPage()
            return BackDecision.DispatchToPage
        }

        onExit()
        return BackDecision.ExitNow
    }
}
