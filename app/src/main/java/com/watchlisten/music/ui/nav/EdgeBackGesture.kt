package com.watchlisten.music.ui.nav

import kotlin.math.abs

/** 越过触摸容差并确认横向右滑后取得所有权；默认仅左边缘，首页根页可从正文起手。 */
class EdgeBackGesture(private val thresholds: Thresholds) {
    data class Thresholds(
        val edgeWidthPx: Float,
        val touchSlopPx: Float,
        val minimumDistancePx: Float,
        val horizontalDominance: Float = 1.25f
    )

    private enum class State { IDLE, TRACKING, CAPTURED, CAPTURED_CANCELLED, REJECTED }

    private var state = State.IDLE
    private var startX = 0f
    private var startY = 0f

    val isCaptured: Boolean get() = state == State.CAPTURED || state == State.CAPTURED_CANCELLED
    val isActive: Boolean get() = state == State.TRACKING || isCaptured

    fun start(x: Float, y: Float, enabled: Boolean, fromAnywhere: Boolean = false) {
        startX = x
        startY = y
        state = if (enabled && x >= 0f && (fromAnywhere || x < thresholds.edgeWidthPx)) {
            State.TRACKING
        } else State.IDLE
    }

    /** 返回 true 后宿主应给子页面发 CANCEL，余下事件由宿主消费。 */
    fun move(x: Float, y: Float): Boolean {
        if (state != State.TRACKING) return isCaptured
        val dx = x - startX
        val dy = abs(y - startY)
        state = when {
            dx > thresholds.touchSlopPx && dx > dy * thresholds.horizontalDominance -> State.CAPTURED
            dx < -thresholds.touchSlopPx ||
                (dy > thresholds.touchSlopPx && dy >= abs(dx)) -> State.REJECTED
            else -> State.TRACKING
        }
        return isCaptured
    }

    /** 已取得手势后，抬手距离仍须达标；回拉和纵向偏离都取消返回。 */
    fun finish(x: Float, y: Float): Boolean {
        val dx = x - startX
        val dy = abs(y - startY)
        val navigateBack = state == State.CAPTURED && dx >= thresholds.minimumDistancePx &&
            dx > dy * thresholds.horizontalDominance
        cancel()
        return navigateBack
    }

    fun cancel() { state = State.IDLE }

    /** 多指取消导航，但已经取消子页面触摸的序列仍由宿主消费。 */
    fun cancelNavigation() {
        state = if (isCaptured) State.CAPTURED_CANCELLED else State.IDLE
    }
}
