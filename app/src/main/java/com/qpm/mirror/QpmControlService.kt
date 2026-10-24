package com.qpm.mirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 接收端的点击/滑动最终由这里在本机（发送端）执行。
 * dispatchGesture 不需要 root，Android 7.0+ 可用。
 *
 * 注意：本服务使用应用自身命名空间 com.qpm.mirror。
 * 早期版本曾把类名伪装成 Google 系统组件以绕过小米澎湃OS 对侧载应用
 * 无障碍服务的白名单过滤，现出于合规考虑已移除该做法。
 * 若在小米/澎湃OS 的「已下载的服务」里看不到本服务，请按 README
 * 「小米 / 澎湃OS 无障碍权限」章节用 adb 命令启用。
 */
class QpmControlService : AccessibilityService() {

    companion object {
        private const val TAG = "QpmControlService"

        @Volatile
        var instance: QpmControlService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "onServiceConnected: 服务已连接，反向控制可用")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        Log.w(TAG, "onDestroy: 服务被回收，反向控制失效，直到系统重新拉起")
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
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "sendText: rootInActiveWindow 为 null（无活动窗口），丢弃")
            return
        }
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: run {
            Log.w(TAG, "sendText: 当前没有聚焦的输入框，丢弃")
            return
        }
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
            else -> Log.w(TAG, "key: 不支持的 keycode=$keycode")
        }
    }
}
