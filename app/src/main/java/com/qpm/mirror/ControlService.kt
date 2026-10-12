package com.qpm.mirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 接收端的点击/滑动最终由这里在本机（发送端）执行。
 * dispatchGesture 不需要 root，Android 7.0+ 可用。
 */
class ControlService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ControlService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun runGesture(path: Path, durationMs: Long) {
        if (durationMs < 1L) return
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    fun tap(x: Float, y: Float) {
        runGesture(Path().apply { moveTo(x, y) }, 60L)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int) {
        runGesture(
            Path().apply { moveTo(x1, y1); lineTo(x2, y2) },
            durationMs.toLong().coerceAtLeast(1L)
        )
    }

    /** 向当前焦点输入框写文字。 */
    fun sendText(text: String) {
        val root = rootInActiveWindow ?: return
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun key(keycode: Int) {
        when (keycode) {
            4 -> performGlobalAction(GLOBAL_ACTION_BACK)
            3 -> performGlobalAction(GLOBAL_ACTION_HOME)
            187 -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }
}
