package com.watchlisten.music.ui.nav

enum class BackInput { EDGE_GESTURE, KEY, SYSTEM }

/** 合并厂商从触摸、BACK 键和系统回调重复上报的同一次返回。 */
class BackInputGate(private val duplicateWindowMs: Long = 350L) {
    private var lastInput: BackInput? = null
    private var lastAcceptedAt = 0L
    private var lastKeyPressId: Long? = null
    private var edgeGestureActive = false
    private var handledDuringEdgeGesture = false

    fun beginEdgeGesture() {
        edgeGestureActive = true
        handledDuringEdgeGesture = false
    }

    fun endEdgeGesture(nowMs: Long) {
        if (edgeGestureActive && handledDuringEdgeGesture) {
            // 慢手势的去重窗口从抬手算起，不能从厂商先报的键事件算起。
            lastInput = BackInput.EDGE_GESTURE
            lastAcceptedAt = nowMs
        }
        edgeGestureActive = false
        handledDuringEdgeGesture = false
    }

    fun acceptKeyDown(pressId: Long, repeatCount: Int, nowMs: Long): Boolean {
        if (repeatCount != 0 || pressId == lastKeyPressId) return false
        lastKeyPressId = pressId
        return accept(BackInput.KEY, nowMs)
    }

    fun accept(input: BackInput, nowMs: Long): Boolean {
        if (edgeGestureActive && handledDuringEdgeGesture) return false
        val sinceLastInput = nowMs - lastAcceptedAt
        val duplicate = lastInput != null && sinceLastInput in 0 until duplicateWindowMs &&
            (input != lastInput || input == BackInput.SYSTEM)
        if (duplicate) return false
        lastInput = input
        lastAcceptedAt = nowMs
        if (edgeGestureActive) handledDuringEdgeGesture = true
        return true
    }
}
