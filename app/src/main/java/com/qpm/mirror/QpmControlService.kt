package com.qpm.mirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
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

    /**
     * 连续手势状态。旧实现每个 ACTION_MOVE 都 dispatchGesture 一条独立 stroke，
     * 而每条 stroke 自带 DOWN→UP，拖动实际变成一串「按下抬起」，且无障碍同一时刻
     * 只执行一个手势，高频 MOVE 会在队列里越积越厚（反向控制不跟手的主因）。
     * API 26+ 用 willContinue + continueStroke 串成一条真正的连续手势。
     */
    private val gestureLock = Any()
    private var activeStroke: GestureDescription.StrokeDescription? = null
    private var lastX = 0f
    private var lastY = 0f

    /** 每段续接的时长：接收端按 32ms 批量发点，留足窗口避免 stroke 提前结束。 */
    private val segmentMs = 120L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        QpmLog.i(TAG, "onServiceConnected: 服务已连接，反向控制可用")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        QpmLog.w(TAG, "onDestroy: 服务被回收，反向控制失效，直到系统重新拉起")
        super.onDestroy()
    }

    private fun runGesture(path: Path, durationMs: Long) {
        if (durationMs < 1L) return
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        runCatching { dispatchGesture(gesture, null, null) }
            .onFailure { QpmLog.w(TAG, "手势下发失败: ${it.message}") }
    }

    /** 下发一条 stroke 并回报是否成功；任何异常都只让本次手势退化，不影响连接。 */
    private fun dispatchStroke(stroke: GestureDescription.StrokeDescription): Boolean =
        runCatching {
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
            true
        }.getOrDefault(false)

    fun tap(x: Float, y: Float) {
        runGesture(Path().apply { moveTo(x, y) }, 60L)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int) {
        runGesture(
            Path().apply { moveTo(x1, y1); lineTo(x2, y2) },
            durationMs.toLong().coerceAtLeast(1L)
        )
    }

    /** 拖动开始：建立一条 willContinue 的 stroke；API < 26 只记录起点，走逐段退化路径。 */
    fun touchDown(x: Float, y: Float) {
        synchronized(gestureLock) {
            activeStroke = null
            lastX = x
            lastY = y
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val stroke = GestureDescription.StrokeDescription(
                    Path().apply { moveTo(x, y) }, 0, segmentMs, true
                )
                if (dispatchStroke(stroke)) activeStroke = stroke
            }
        }
    }

    /** 一批 MOVE 点：优先续接当前 stroke；失败立刻退回「整段一次手势」，绝不中断拖动。 */
    fun touchMove(points: FloatArray) {
        if (points.size < 2) return
        synchronized(gestureLock) {
            val path = Path().apply {
                moveTo(lastX, lastY)
                var i = 0
                while (i + 1 < points.size) {
                    lineTo(points[i], points[i + 1])
                    i += 2
                }
            }
            lastX = points[points.size - 2]
            lastY = points[points.size - 1]

            val cur = activeStroke
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && cur != null) {
                val next = runCatching {
                    cur.continueStroke(path, 0, segmentMs, true)
                }.getOrNull()
                if (next != null && dispatchStroke(next)) {
                    activeStroke = next
                    return
                }
                // 续接失败（超时 / 机型不支持 / 手势已被系统结束）：清理状态走退化路径
                activeStroke = null
            }
            runGesture(path, 40L)
        }
    }

    /** 拖动结束：用 willContinue=false 收尾，抬起手指。 */
    fun touchUp() {
        synchronized(gestureLock) {
            val cur = activeStroke
            activeStroke = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && cur != null) {
                // Path 不能为空：原地一点即可，仅用于让系统结束这条手势
                val endPath = Path().apply { moveTo(lastX, lastY); lineTo(lastX, lastY) }
                val last = runCatching {
                    cur.continueStroke(endPath, 0, 16L, false)
                }.getOrNull()
                if (last != null) dispatchStroke(last)
            }
        }
    }

    /** 向当前焦点输入框写文字。 */
    fun sendText(text: String) {
        val root = rootInActiveWindow ?: run {
            QpmLog.w(TAG, "sendText: rootInActiveWindow 为 null（无活动窗口），丢弃")
            return
        }
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: run {
            QpmLog.w(TAG, "sendText: 当前没有聚焦的输入框，丢弃")
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
            else -> QpmLog.w(TAG, "key: 不支持的 keycode=$keycode")
        }
    }
}
